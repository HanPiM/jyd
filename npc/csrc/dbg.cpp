#include "elf_tool.hpp"
#include "sim.hpp"
#include "tracers.hpp"
#include <sdb.hpp>

std::shared_ptr<sdb::debuger> dbg;
sdb::difftest_trace_handler_ptr diff_handler;

void dbg_skip_difftest_ref() {
  if (diff_handler)
    diff_handler->skip_ref();
}

void dbg_exec(std::string_view cmd, bool *quit) {
  dbg->exec_command(cmd);
  if (dbg->state().state == sdb::run_state::quit) {
    if (quit)
      *quit = true;
  }
}

bool dbg_is_hitbadtrap() { return dbg->state().is_badexit(); }

void dbg_set_halt(int a0) { dbg->state().halt(a0); }
void dbg_dump_recent_info() { dbg->dump_all(); }

namespace sdbwrap {
sdb::paddr_t cpu_exec(size_t n) {
  while (n-- > 0) {
    sim_step_inst();
    if (sim_halted())
      break;
  }
  return sim_current_pc();
}
void shot_regsnap(sdb::reg_snapshot_t &regsnap) {
  for (size_t i = 0; i < 32; i++) {
    regsnap[i] = sim_current_gpr()[i];
  }
}
sdb::vlen_inst_code inst_fetcher(sdb::paddr_t pc) {
  sdb::word_t inst;
  sim_read_vmem(pc, &inst);
  uint8_t *p = (uint8_t *)&inst;
  return sdb::vlen_inst_code(p, p + 4);
}
uint8_t *loadmem(sdb::paddr_t addr, size_t nbyte) {
  return sim_guest_to_host(addr);
}
} // namespace sdbwrap
using namespace sdb;
std::array<std::string_view, 32> reg_names = {
    "$0", "ra", "sp", "gp", "tp",  "t0",  "t1", "t2", "s0", "s1", "a0",
    "a1", "a2", "a3", "a4", "a5",  "a6",  "a7", "s2", "s3", "s4", "s5",
    "s6", "s7", "s8", "s9", "s10", "s11", "t3", "t4", "t5", "t6"};
// void dump_regs() {
//   for (int i = 0; i < 32; i++) {
//     printf("%s: %08x\n", reg_names[i].data(), gpr_snap[i]);
//   }
// }
void dbg_init(word_t init_pc, size_t img_size, const char *img_file,
              sim_setting setting) {
  dbg = std::make_shared<sdb::debuger>(
      init_pc, init_pc, img_size, sdbwrap::cpu_exec, sdbwrap::loadmem,
      sdbwrap::shot_regsnap,
      std::vector<std::string_view>(reg_names.begin(), reg_names.end()),
      sdbwrap::inst_fetcher);

  dbg->enable_inst_trace = setting.en_inst_trace;

  if (setting.en_inst_trace) {
    if (setting.showdisasm) {
      size_t inst_show_limit = setting.always_showdisasm ? SIZE_MAX : 16;
      dbg->add_trace(sdb::make_disasm_trace_handler(sdb::default_inst_disasm,
                                                    inst_show_limit));
    }
    if (setting.etrace)
      dbg->add_trace(sdb::make_etrace_handler());
    if (setting.iringbuf)
      dbg->add_trace(sdb::make_iringbuf_trace_handler());

    if (img_file && setting.ftrace) {
      auto elf_file = try_find_elf_file_of(img_file);

      if (!elf_file.empty()) {
        printf("Found ELF file: %s\n", elf_file.c_str());
        dbg->add_trace(sdb::make_ftrace_handler(elf_file));
      }
    }

    if (setting.difftest) {
      diff_handler = sdb::make_difftest_trace_handler(
          "../nemu/build/riscv32-nemu-interpreter-so", 0);
      dbg->add_trace(diff_handler);
    }
  }

}
