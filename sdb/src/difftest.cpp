#include "sdb.hpp"
#include <assert.h>
#include <dlfcn.h>
#include <optional>
#include <tracers.hpp>

#include "ansi_col.h"

using namespace std;
using namespace sdb;

#define COND_ENABLE if (enable_difftest)

typedef void (*ref_difftest_init_t)(int port);
typedef void (*ref_difftest_memcpy_t)(paddr_t addr, void *buf, size_t n,
                                      bool direction);
typedef void (*ref_difftest_regcpy_t)(void *regs, bool direction);
typedef void (*ref_difftest_exec_t)(size_t n);

struct sdb::difftest_trace_handler::difftest_imp {
  void *handle = nullptr;
  ref_difftest_init_t ref_init = nullptr;
  ref_difftest_memcpy_t ref_memcpy = nullptr;
  ref_difftest_regcpy_t ref_regcpy = nullptr;
  difftest_fp_regcpy_t ref_fp_regcpy = nullptr;
  ref_difftest_exec_t ref_exec = nullptr;

  bool is_skip_ref = false;

  template <typename Fn> void _meta_load(Fn &fn, const char *name) {
    fn = (Fn)dlsym(handle, name);
    if (!fn)
      throw runtime_error(format("load {} failed: {}", name, dlerror()));
  }

  ~difftest_imp() {
    if (handle) {
      dlclose(handle);
      handle = nullptr;
    }
  }
  void load(string_view so_file) {
    handle = dlopen(so_file.data(), RTLD_LAZY);
    if (!handle) {
      throw runtime_error(format("dlopen {} failed: {}", so_file, dlerror()));
    }
    _meta_load(ref_init, "difftest_init");
    _meta_load(ref_memcpy, "difftest_memcpy");
    _meta_load(ref_regcpy, "difftest_regcpy");
    _meta_load(ref_exec, "difftest_exec");
    ref_fp_regcpy =
        reinterpret_cast<difftest_fp_regcpy_t>(dlsym(handle, "difftest_fp_regcpy"));
  }

  void require_fp_regcpy() {
    if (!ref_fp_regcpy)
      throw runtime_error(
          "DUT provides floating-point state but reference does not export "
          "difftest_fp_regcpy");
  }
};

using hander_t = sdb::difftest_trace_handler;

void hander_t::_imp_deleter::operator()(difftest_imp *p) { delete p; }

hander_t::difftest_trace_handler(string_view ref_so_file, int port) {
  _imp = unique_ptr<difftest_imp, _imp_deleter>(new difftest_imp());
  _imp->load(ref_so_file);
  _log("Difftest load ref from {}\n", ref_so_file);
  _require_call_after_inst_exec = true;
  _imp->ref_init(port);
  this->name = "difftest-handler";
}

static reg_snapshot_t pack_regs_and_pc(const trace_context &ctx) {
  reg_snapshot_t regs(ctx.regs.begin(), ctx.regs.end());
  regs.push_back(ctx.pc);
  return regs;
}

void hander_t::init(_ctx_ref ctx, std::span<uint8_t> img_data,
                    paddr_t img_base) {
  // std::cout<<std::format("Difftest init with img_base={:#x},
  // img_size={:#x}\n", img_base, img_data.size());
  _imp->ref_memcpy(img_base, img_data.data(), img_data.size(), DIFFTEST_TO_REF);
  auto regs = pack_regs_and_pc(ctx);
  _imp->ref_regcpy(regs.data(), DIFFTEST_TO_REF);
  if (ctx.fp_state) {
    _imp->require_fp_regcpy();
    auto fp_state = *ctx.fp_state;
    _imp->ref_fp_regcpy(&fp_state, DIFFTEST_TO_REF);
  }
}

void hander_t::memcpy_to_ref(std::span<uint8_t> data, paddr_t addr){
	_imp->ref_memcpy(addr, data.data(), data.size(), DIFFTEST_TO_REF);
}

void hander_t::skip_ref() { _imp->is_skip_ref = true; }
void hander_t::handle(_ctx_ref ctx) {
  auto &imp = *_imp;
  if (imp.is_skip_ref) {
    auto regs = pack_regs_and_pc(ctx);
    imp.ref_regcpy(regs.data(), DIFFTEST_TO_REF);
    if (ctx.fp_state) {
      auto fp_state = *ctx.fp_state;
      imp.ref_fp_regcpy(&fp_state, DIFFTEST_TO_REF);
    }
    imp.is_skip_ref = false;
    return;
  }
  auto regs = pack_regs_and_pc(ctx);
  imp.ref_exec(1);
  reg_snapshot_t ref_regs(regs.size());
  imp.ref_regcpy(ref_regs.data(), DIFFTEST_TO_DUT);
  std::optional<riscv_fp_state> ref_fp_state;
  if (ctx.fp_state) {
    ref_fp_state.emplace();
    imp.ref_fp_regcpy(&*ref_fp_state, DIFFTEST_TO_DUT);
  }

  std::vector<size_t> diff_regs;
  for (size_t i = 0; i < regs.size(); i++) {
    if (ref_regs[i] != regs[i]) {
      diff_regs.push_back(i);
    }
  }

  if (!diff_regs.empty()) {
    _log("Difftest failed at pc = {:#x}{}, {} items not match:\n", ctx.pc,
         ref_regs.back() == regs.back()
             ? ""
             : std::format(" (ref pc = {:#x})", ref_regs.back()),
         diff_regs.size());
		_log("{:4} {:10} {:10}\n", "reg", "dut", "ref");
    for (auto i : diff_regs) {
      _log(ANSI_FG_BLUE"{:4}" ANSI_FG_RED " 0x{:08x}" ANSI_FG_GREEN " 0x{:08x}\n" ANSI_NONE,
           i < ctx.reg_names.size() ? ctx.reg_names[i] : "pc", regs[i],
           ref_regs[i]);
    }
    this->_req_abort();
    return;
  }

  if (ref_fp_state) {
    std::vector<size_t> diff_fprs;
    for (size_t i = 0; i < 32; i++) {
      const auto &dut = ctx.fp_state->fpr[i];
      const auto &ref = ref_fp_state->fpr[i];
      if (dut.v[0] != ref.v[0] || dut.v[1] != ref.v[1])
        diff_fprs.push_back(i);
    }
    bool diff_fcsr = ctx.fp_state->fcsr != ref_fp_state->fcsr;
    if (!diff_fprs.empty() || diff_fcsr) {
      _log("Difftest failed at pc = {:#x}, {} floating-point items not match:\n",
           ctx.pc, diff_fprs.size() + diff_fcsr);
      _log("{:4} {:34} {:34}\n", "reg", "dut", "ref");
      for (auto i : diff_fprs) {
        const auto &dut = ctx.fp_state->fpr[i];
        const auto &ref = ref_fp_state->fpr[i];
        _log(ANSI_FG_BLUE "f{:<3}" ANSI_FG_RED " 0x{:016x}{:016x}"
             ANSI_FG_GREEN " 0x{:016x}{:016x}\n" ANSI_NONE,
             i, dut.v[1], dut.v[0], ref.v[1], ref.v[0]);
      }
      if (diff_fcsr) {
        _log(ANSI_FG_BLUE "{:4}" ANSI_FG_RED " 0x{:032x}"
             ANSI_FG_GREEN " 0x{:032x}\n" ANSI_NONE,
             "fcsr", ctx.fp_state->fcsr, ref_fp_state->fcsr);
      }
      this->_req_abort();
    }
  }
}
