#include "KonataLog.hpp"
#include "../sim.hpp"
#include <functional>

#include "../memory/mem.hpp"
#include <tracers.hpp>

using namespace DirectSignals;

using SignalBoolType = unsigned char;

struct HandshakeBus {
  SignalBoolType *hValid = nullptr;
  SignalBoolType *hReady = nullptr;
  HandshakeBus() = default;
  HandshakeBus(SignalBoolType *valid, SignalBoolType *ready)
      : hValid(valid), hReady(ready) {}
  bool valid() const { return hValid && *hValid; }
  bool ready() const { return hReady && *hReady; }
  bool fire() const { return valid() && ready(); }
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
  uint32_t *inIID = nullptr;

  std::string name;

  Stage() = default;
  Stage(HandshakeBus in, HandshakeBus out, std::string name, uint32_t *inIID)
      : in(in), out(out), inIID(inIID), name(name) {}
  template <PipeStage T>
  Stage(T &stage, std::string name, uint32_t *inIID)
      : in(&stage.io_in_valid, &stage.io_in_ready),
        out(&stage.io_out_valid, &stage.io_out_ready), inIID(inIID), name(name) {}
};

void KonataLogger::_output(const std::string &str) {
  constexpr size_t _maxLogFileSize = 64 * 1024 * 1024;
  if (_fileStream.tellp() >= static_cast<std::streampos>(_maxLogFileSize)) {
    _fileStream.close();
    spdlog::warn("Log file size exceeded {} MB. KonataLogger stopped logging",
                 _maxLogFileSize / (1024 * 1024));
  } else {
    _fileStream << str << std::endl;
  }
}

void KonataLogger::capturePreEdge() {
  static auto &cpu = *GetCPU();
  static auto &ifu = *GetIFU();
  static auto &idu = *GetIDU();
  static auto &exu = *GetEXU();
  static auto &lsu = *GetLSU();
  static auto &wbu = *GetCPU()->wbu;
  static std::vector<Stage> stages = {
      Stage({&ifu.io_pc_valid, &ifu.io_pc_ready},
            {&ifu.io_out_valid, &ifu.io_out_ready}, "IF", nullptr),
      Stage(idu, "DE", &idu.io_in_bits_iid),
      Stage(exu, "EX", &exu.io_in_bits_iid),
      Stage(lsu, "LS", &lsu.io_in_bits_exuWriteBack_iid),
      Stage({&wbu.io_in_valid, &wbu.io_in_ready}, {nullptr, nullptr}, "WB",
            &wbu.io_in_bits_iid),
  };

  _snapshot = {};
  _snapshot.isValid = true;
  _snapshot.needFlushPipeline = cpu.needFlushPipeline;
  _snapshot.ifFire = stages[0].in.fire();
  _snapshot.ifIID = static_cast<InstFileIDType>(ifu.instID) + 1;
  _snapshot.ifPC = ifu.io_pc_bits;
  _snapshot.iduInputValid = idu.io_in_valid;
  _snapshot.iduInputIID = idu.io_in_bits_iid;
  _snapshot.stages.reserve(stages.size());

  for (const auto &stage : stages) {
    _snapshot.stages.push_back(_StageSnapshot{
        .valid = stage.in.valid(),
        .ready = stage.in.ready(),
        .iid = stage.inIID ? *stage.inIID : 0,
        .name = stage.name,
    });
  }
}

void KonataLogger::update() {
  if (!_snapshot.isValid) {
    return;
  }

  if (_snapshot.ifFire) {
    auto iid = _snapshot.ifIID;
    declare(iid, iid);
    sdb::vlen_inst_code code(4);
    auto pc = _snapshot.ifPC;
    read_guest_mem(pc, (uint32_t *)code.data());
    auto disasm = sdb::default_inst_disasm(pc, code);
    std::ranges::replace(disasm, '\t', ' ');
    addLabel(iid, std::format("{:08x}: {}", pc, disasm));
    addLabel(iid, std::format("{}ps", sim_get_time()), true);
  }

  std::ranges::for_each(_snapshot.stages, [&](const auto &stage) {
    bool isIFU = stage.name == "IF";
    bool isIDU = stage.name == "DE";
    if (isIDU && _snapshot.needFlushPipeline) {
      return;
    }
    if (isIFU) {
      if (_snapshot.ifFire) {
        stageStart(_snapshot.ifIID, stage.name);
      }
    } else if (stage.fire()) {
      stageStart(stage.iid, stage.name);
    }
  });

  // bool iduStall = cpu.isIDUStall;
  // bool isIDUStallBegin = iduStall && !lastCycIDUStall;
  // bool isIDUStallEnd = !iduStall && lastCycIDUStall;
  // lastCycIDUStall = iduStall;
  //
  // if (isIDUStallBegin) {
  // 	stageStart(*idu_stage.iid, "IDU_STALL");
  // 	addLabel(*idu_stage.iid, std::format("IDU_STALL beg@{}ps",
  // sim_get_time()), true); } else if (isIDUStallEnd) {
  // 	// stageEnd(*idu_stage.iid, "IDU_STALL");
  // }

  if (_snapshot.stages.back().fire()) {
    retire(_snapshot.stages.back().iid, _GenNextRetireID());
    // stageEnd(*wbu_stage.iid, wbu_stage.name);
  }

  if (_snapshot.needFlushPipeline && _snapshot.iduInputValid) {
    // addLabel(*idu_stage.iid, std::format("FLUSHED@{}ps", sim_get_time()),
    // true);
    retire(_snapshot.iduInputIID, 0, true);
  }

  _snapshot.isValid = false;
}
