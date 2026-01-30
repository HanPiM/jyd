#pragma once
#include "vpi_user.h"
#include <cstddef>
#include <cstdint>
#include <iterator>
#include <numeric>
#include <vector>
#include <verilated.h>
#include <verilated_vpi.h>

#include <variant>

#include "vsrc.hpp"

#include "common.hpp"

#include "sim.hpp"

inline const std::string &cpu_vpi_path_prefix() {
  static std::string prefix;
  if (prefix.empty()) {
    prefix = std::string("TOP.") + std::string(_STR(TOP_NAME)).substr(1) + '.';
    if (is_soc()) {
      prefix += "asic.cpu.cpu.";
    }
  }
  return prefix;
}

namespace _PerfCtrImp {

inline auto _FullPath(const std::string &pathWithoutValidOrReady,
                      const std::string &suffix = "") {
  return cpu_vpi_path_prefix() + pathWithoutValidOrReady + suffix;
}
inline auto _DebugPath(const std::string &pathWithoutValidOrReady,
                       const std::string &suffix = "") {
  return "`cpu." + pathWithoutValidOrReady + suffix;
}
} // namespace _PerfCtrImp

struct SignalHandle {
  vpiHandle handle;
  ~SignalHandle() {
    if (handle) {
      vpi_release_handle(handle);
    }
  }
  SignalHandle() : handle(nullptr) {}
  SignalHandle(vpiHandle h) : handle(h) {}

  SignalHandle(const SignalHandle &) = delete;
  SignalHandle &operator=(const SignalHandle &) = delete;
  SignalHandle(SignalHandle &&other) : handle(other.handle) {
    other.handle = nullptr;
  }
  SignalHandle &operator=(SignalHandle &&other) {
    if (this != &other) {
      if (handle) {
        vpi_release_handle(handle);
      }
      handle = other.handle;
      other.handle = nullptr;
    }
    return *this;
  }

  SignalHandle(std::string barePath);

  uint32_t getUint32Value() {
    s_vpi_value val;
    val.format = vpiIntVal;
    vpi_get_value(handle, &val);
    return static_cast<uint32_t>(val.value.integer);
  }
};

class PerfCounterBase {
public:
  std::string ctrName;
  struct Field {
    std::string label;
    size_t value;
  };
  std::vector<Field> fields;
  virtual void fillFields() = 0;
  virtual void dumpStatistics() = 0;
  void clearFields() { fields.clear(); }
};

class HandShakeCounterManager : public PerfCounterBase {
public:
  using callback_t = std::function<void()>;
  struct ValidReadyBus {
    SignalHandle hValid;
    SignalHandle hReady;

    std::string pathWithoutValidOrReady;
    std::string description;

    size_t shake_count = 0;
    callback_t onShakeCallback = nullptr;

    bool shakeHappened();
    void dumpStatus();
  };
  std::shared_ptr<spdlog::logger> logger;
  std::vector<ValidReadyBus> bus_list;

  void init();
  ValidReadyBus &add(std::string pathWithoutValidOrReady,
                     std::string description = "",
                     callback_t onShake = nullptr);

  void update();
  void fillFields() override {
    ctrName = "HandShakeCounter";
    for (auto &bus : bus_list) {
      fields.push_back(Field{bus.pathWithoutValidOrReady, bus.shake_count});
    }
  }
  void dumpStatistics() override;
};

struct EXUPerfCounter : public PerfCounterBase {
  // in common_def.scala
  //   val imm, reg, store, upper, jump, branch = Value
  //   val branch, arithmetic, load, store, jalr, jal, lui, auipc, system =
  enum InstFmt { I_TYPE, R_TYPE, S_TYPE, U_TYPE, J_TYPE, B_TYPE, FMT_NUM };
  enum InstType {
    branch,
    arithmetic,
    load,
    store,
    jalr,
    jal,
    lui,
    auipc,
    system,
    TYPE_NUM
  };
  static inline bool isValidFmt(InstFmt fmt) { return fmt < FMT_NUM; }
  static inline bool isValidType(InstType type) { return type < TYPE_NUM; }

  static const char *nameOfFmt(int fmt);
  static const char *nameOfTyp(int type);

  size_t instCountOfFmt[FMT_NUM] = {0};
  size_t instCountOfTyp[TYPE_NUM] = {0};

  size_t totalCycleOfTyp[TYPE_NUM] = {0};
  size_t totalCycleOfFmt[FMT_NUM] = {0};

  bool lastCycOutValid = false;
  sim_cycle_t instStartCycle = 0;

  SignalHandle hInstType;
  SignalHandle hInstFmt;
  SignalHandle hOutValid;
  SignalHandle hOutReady;

  std::shared_ptr<spdlog::logger> logger;

  void bind(std::string path);
  void update();

  void _dump(size_t *instCnts, size_t *cycCnts, size_t num,
             const char *(*nameFunc)(int));
  void dumpStatistics() override;

  void fillFields() override {
    ctrName = "EXUInstTypeFmtCounter";
    for (size_t i = 0; i < TYPE_NUM; i++) {
      fields.push_back(
          Field{std::string("typ_") + nameOfTyp((int)i), instCountOfTyp[i]});
    }
    for (size_t i = 0; i < FMT_NUM; i++) {
      fields.push_back(
          Field{std::string("fmt_") + nameOfFmt((int)i), instCountOfFmt[i]});
    }
  }
};

struct AXI4CounterBase : public PerfCounterBase {
  struct LatencyRecord {
    sim_time_t startTime;
    sim_time_t endTime;
    size_t cycles;
  };
  size_t transaction_count = 0;

  size_t total_latency_cycles = 0;

  LatencyRecord currentRecord;
  LatencyRecord maxRecord;

  std::string name;

  std::shared_ptr<spdlog::logger> logger;

  void init_logger();
  static void dumpStatisticsTitle();
  void dumpStatistics();
  void fillFields() {
    ctrName = name;
    fields.push_back(Field{"txn", transaction_count});
    fields.push_back(Field{"lat_cyc", total_latency_cycles});
    fields.push_back(Field{"lat_max", maxRecord.cycles});
  }
};

struct AXI4ReadPerfCounter : public AXI4CounterBase {
  SignalHandle hARValid;
  SignalHandle hARReady;
  SignalHandle hRValid;
  SignalHandle hRReady;

  // count arvalid becoming high to rvalid & rready handshake

  enum State {
    IDLE,
    WAIT_DATA,
  } state = IDLE;

  void bind(std::string path);
  void update();
};
struct AXI4WritePerfCounter : public AXI4CounterBase {
  SignalHandle hAWValid;
  SignalHandle hAWReady;
  SignalHandle hWValid;
  SignalHandle hWReady;
  SignalHandle hBValid;
  SignalHandle hBReady;

  // count awvalid becoming high to bvalid & bready handshake

  enum State {
    IDLE,
    WAIT_RESP,
  } state = IDLE;

  void bind(std::string path);
  void update();
};

class AXI4PerfCounterManager : public PerfCounterBase {
public:
  std::vector<AXI4ReadPerfCounter> rdCounters;
  std::vector<AXI4WritePerfCounter> wrCounters;
  void update();
  void dumpStatistics() override;

  void addRead(std::string channelPath, std::string name);
  void addWrite(std::string channelPath, std::string name);

  void fillFields() override {
    ctrName = "AXI4PerfCounters";
    for (auto &ctr : rdCounters) {
      for (auto &f : ctr.fields) {
        fields.push_back(Field{"rd_" + ctr.ctrName + "_" + f.label, f.value});
      }
    }
    for (auto &ctr : wrCounters) {
      for (auto &f : ctr.fields) {
        fields.push_back(Field{"wr_" + ctr.ctrName + "_" + f.label, f.value});
      }
    }
  }
};

struct IFUStateCounter : public PerfCounterBase {
  // check fetch handshake happened
  SignalHandle hRValid;
  SignalHandle hRReady;
  // ifu fsm state
  SignalHandle hState;

  enum State {
    IDLE,
    WAIT_INST,
    WAIT_DOWNSTREAM,

    STATE_NUM
  };

  size_t countOfState[STATE_NUM] = {0};
  size_t countOfStateWhenNoFetch[STATE_NUM] = {0};
  size_t totalFetchCount = 0;

  const char *nameOfState(int state);

  void bind(std::string ifupath);
  void update();
  void dumpStatistics() override;

  void fillFields() override {
    ctrName = "IFUStateCounter";
    for (size_t i = 0; i < STATE_NUM; i++) {
      fields.push_back(
          Field{std::string("state_") + nameOfState((int)i), countOfState[i]});
    }
  }
};

using PerfCounterVariant =
    std::variant<HandShakeCounterManager, EXUPerfCounter,
                 AXI4PerfCounterManager, IFUStateCounter>;

void initPerfCounters();
void dumpPerfCountersStatistics();
void updatePerfCounters();

std::string dumpPerfCounterAsCSV();
