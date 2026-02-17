#include "KonataLog.hpp"
#include "../sim.hpp"
#include <functional>

#include <tracers.hpp>
#include "../memory/mem.hpp"

using namespace DirectSignals;

using SignalBoolType = unsigned char;

struct HandshakeBus {
  SignalBoolType *valid = nullptr;
  SignalBoolType *ready = nullptr;
  HandshakeBus() = default;
  HandshakeBus(SignalBoolType *valid, SignalBoolType *ready)
      : valid(valid), ready(ready) {}
  bool fire() const {
    if (!valid || !ready)
      return false;
    return *valid && *ready;
  }
};

template <typename T>
concept PipeStage = requires(T stage) {
  { &stage.io_in_valid } -> std::convertible_to<SignalBoolType *>;
  { &stage.io_in_ready } -> std::convertible_to<SignalBoolType *>;
  { &stage.io_out_valid } -> std::convertible_to<SignalBoolType *>;
  { &stage.io_out_ready } -> std::convertible_to<SignalBoolType *>;
};

struct Stage {
  HandshakeBus in;
  HandshakeBus out;

  uint32_t *iid = nullptr;

  std::string name;

  Stage() = default;
  Stage(HandshakeBus in, HandshakeBus out, std::string name, uint32_t *iid)
      : in(in), out(out), name(name), iid(iid) {}
  template <PipeStage T>
  Stage(T &stage, std::string name, uint32_t *iid)
      : in(&stage.io_in_valid, &stage.io_in_ready),
        out(&stage.io_out_valid, &stage.io_out_ready), name(name), iid(iid) {}
};

void KonataLogger::readSignalsAndLog() {
  static auto &cpu = *GetCPU();
  static auto &ifu = *GetIFU();
  static auto &idu = *GetIDU();
  static auto &exu = *GetEXU();
  static auto &lsu = *GetLSU();
  static auto &wbu = *GetCPU()->wbu;
  static std::vector<Stage> stages = {
      Stage({&ifu.io_pc_valid, &ifu.io_pc_ready},
            {&ifu.io_out_valid, &ifu.io_out_ready}, "IF", &ifu.io_out_bits_iid),
      Stage(idu, "DE", &idu.io_out_bits_iid),
      Stage(exu, "EX", &exu.io_out_bits_exuWriteBack_iid),
      Stage(lsu, "LS", &lsu.io_out_bits_iid),
      Stage({&wbu.io_in_valid, &wbu.io_in_ready}, {nullptr, nullptr}, "WB",
            &wbu.io_in_bits_iid),
  };

  auto &ifu_stage = stages[0];
  if (ifu_stage.in.fire()) {
    declare(*ifu_stage.iid);
		sdb::vlen_inst_code code(4);
		read_guest_mem(ifu.io_pc_bits, (uint32_t*)code.data());
		auto disasm = sdb::default_inst_disasm(ifu.io_pc_bits, code);
		std::ranges::replace(disasm, '\t', ' ');
    addLabel(*ifu_stage.iid, disasm);
		addLabel(*ifu_stage.iid, fmt::format("{}ps", sim_get_time()), true);
		// IFU always issue start stage event, even when the instruction is later
		// flushed in IDU, to make log easier to read
		stageStart(*ifu_stage.iid, ifu_stage.name);
  }

  for (auto &stage : stages) {
    if (stage.in.fire() && (!cpu.isFlushIDU)) {
      stageStart(*stage.iid, stage.name);
    }
  }

  auto &wbu_stage = stages.back();

  if (wbu_stage.in.fire()) {
    retire(*wbu_stage.iid, 0);
    // stageEnd(*wbu_stage.iid, wbu_stage.name);
  }

  auto &idu_stage = stages[1];
  if (cpu.isFlushIDU && idu_stage.in.valid) {
    retire(*idu_stage.iid, 0, true);
  }
}
