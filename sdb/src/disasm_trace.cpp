#include <ansi_col.h>
#include <tracers.hpp>

#include <llvm/ADT/ArrayRef.h>
#include <llvm/ADT/SmallString.h>
#include <llvm/MC/MCAsmInfo.h>
#include <llvm/MC/MCContext.h>
#include <llvm/MC/MCDisassembler/MCDisassembler.h>
#include <llvm/MC/MCInst.h>
#include <llvm/MC/MCInstPrinter.h>
#include <llvm/MC/MCInstrInfo.h>
#include <llvm/MC/MCRegisterInfo.h>
#include <llvm/MC/MCSubtargetInfo.h>
#include <llvm/MC/TargetRegistry.h>
#include <llvm/Support/TargetSelect.h>
#include <llvm/Support/raw_ostream.h>
#include <llvm/TargetParser/Triple.h>

#include <algorithm>
#include <cassert>
#include <memory>
#include <string_view>

namespace {
constexpr const char *kRiscvTriple = "riscv32-unknown-unknown";
constexpr const char *kRiscvCpu = "generic-rv32";
constexpr const char *kRiscvFeatures =
    "+m,+a,+f,+d,+c,+zicsr,+zifencei,+zmmul,+zba,+zbb,+zbc,+zbs,+zbkb,+zbkc,"
    "+zbkx";

class llvm_riscv_disassembler {
private:
  std::unique_ptr<llvm::MCRegisterInfo> mri;
  std::unique_ptr<llvm::MCAsmInfo> mai;
  std::unique_ptr<llvm::MCInstrInfo> mii;
  std::unique_ptr<llvm::MCSubtargetInfo> sti;
  std::unique_ptr<llvm::MCContext> ctx;
  std::unique_ptr<llvm::MCDisassembler> disasm;
  std::unique_ptr<llvm::MCInstPrinter> printer;

public:
  llvm_riscv_disassembler() {
    llvm::InitializeAllTargetInfos();
    llvm::InitializeAllTargetMCs();
    llvm::InitializeAllDisassemblers();

    std::string err;
    const llvm::Target *target =
        llvm::TargetRegistry::lookupTarget(kRiscvTriple, err);
    assert(target && "failed to find LLVM RISC-V target");

    mri.reset(target->createMCRegInfo(kRiscvTriple));
    assert(mri);
    mai.reset(
        target->createMCAsmInfo(*mri, kRiscvTriple, llvm::MCTargetOptions()));
    assert(mai);
    mii.reset(target->createMCInstrInfo());
    assert(mii);
    sti.reset(
        target->createMCSubtargetInfo(kRiscvTriple, kRiscvCpu, kRiscvFeatures));
    assert(sti);

    ctx = std::make_unique<llvm::MCContext>(llvm::Triple(kRiscvTriple),
                                            mai.get(), mri.get(), nullptr);
    disasm.reset(target->createMCDisassembler(*sti, *ctx));
    assert(disasm);
    printer.reset(target->createMCInstPrinter(llvm::Triple(kRiscvTriple), 0,
                                             *mai, *mii, *mri));
    assert(printer);
  }

  std::string disassemble(uint64_t pc, const uint8_t *code, int nbyte) {
    llvm::MCInst inst;
    uint64_t inst_size = 0;
    auto status = disasm->getInstruction(
        inst, inst_size, llvm::ArrayRef<uint8_t>(code, nbyte), pc,
        llvm::nulls());
    if (status == llvm::MCDisassembler::Fail || inst_size == 0) {
      return "(invalid)";
    }

    llvm::SmallString<128> out;
    llvm::raw_svector_ostream os(out);
    printer->printInst(&inst, pc, "", *sti, os);

    std::string_view view(out.data(), out.size());
    auto first = std::ranges::find_if_not(view, [](char c) {
      return c == ' ' || c == '\t';
    });
    view.remove_prefix(static_cast<size_t>(first - view.begin()));
    return std::string(view);
  }
};

llvm_riscv_disassembler &get_disassembler() {
  static llvm_riscv_disassembler disasm;
  return disasm;
}
} // namespace

using namespace sdb;
using namespace std;

string sdb::default_inst_disasm(paddr_t pc, vlen_inst_view inst) {
  if (inst.size() == 4) {
    uint32_t code = *(uint32_t *)inst.data();
    if (code == 0) {
      return "(null)";
    }
  }
  return get_disassembler().disassemble(pc, inst.data(), inst.size());
}

string sdb::_impl::expand_tabs(std::string_view in, int tabsize) {
  string out;
  out.reserve(in.size() * tabsize);
  int col = 0;
  for (char c : in) {
    if (c == '\t') {
      int spaces = tabsize - (col % tabsize);
      out.append(spaces, ' ');
      col += spaces;
    } else {
      out.push_back(c);
      col++;
    }
  }
  return out;
}

string sdb::disasm_trace_handler::_dump_inst(disasm_trace_handler::_ctx_ref ctx,
                                             bool highlight_disasm) {
#define ANSI_FG_LIGHTGRAY "\e[38;2;149;164;192m"
  string res;
  auto as_u32code = *(uint32_t *)ctx.inst.data();
  res += format(ANSI_FG_LIGHTGRAY"{:08x}" ANSI_FG_GRAY ": {:08x} {}{:25}", ctx.pc,
                as_u32code, highlight_disasm ? ANSI_FG_RED : ANSI_NONE,
                _impl::expand_tabs(_disasm(ctx.pc, ctx.inst)));
  // for(size_t j=0;j<ctx.inst.size();j++){
  // 	if(j) res+=format(" ");
  //   res+=format("{:02X}",ctx.inst[j]);
  // }
  // res+=format("`{:08X}",as_u32code);
  // res+="" ANSI_NONE ;
  return res;
}
