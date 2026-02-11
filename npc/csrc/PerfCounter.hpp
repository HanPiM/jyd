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
  using vDataPtr = std::variant<CData *, SData *, IData *, QData *>;
  vDataPtr ptr;
  uint64_t get() {
    return std::visit([](auto &&arg) { return static_cast<uint64_t>(*arg); },
                      ptr);
  }
  SignalHandle() = default;
  SignalHandle(auto *newPtr) { ptr = newPtr; }
  SignalHandle operator=(auto *newPtr) {
    ptr = newPtr;
    return *this;
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
  void clearFields() { fields.clear(); }
  virtual void dumpStatistics(std::ostream &) = 0;
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
  };
  std::shared_ptr<spdlog::logger> logger;
  std::vector<ValidReadyBus> bus_list;

  void init();
  ValidReadyBus &add(SignalHandle hValid, SignalHandle hReady,
                     std::string barePath, std::string description = "",
                     callback_t onShake = nullptr);

  void update();
  void fillFields() override {
    ctrName = "HandShakeCounter";
    for (auto &bus : bus_list) {
      fields.push_back(Field{bus.pathWithoutValidOrReady, bus.shake_count});
    }
  }
  void dumpStatistics(std::ostream &os) override;
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

  std::shared_ptr<spdlog::logger> logger;

  double avgLatency() const {
    return transaction_count == 0
               ? NAN
               : (double)total_latency_cycles / (double)transaction_count;
  }

  void init_logger();
  void dumpStatistics(std::ostream &) override;
  void fillFields() override {
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

  AXI4ReadPerfCounter &bind(SignalHandle arv, SignalHandle arr, SignalHandle rv,
                            SignalHandle rr) {
    hARValid = arv;
    hARReady = arr;
    hRValid = rv;
    hRReady = rr;
    return *this;
  }
#define BIND_AXI4_R_BASE(base)                                                 \
  bind(&base##_arvalid, &base##_arready, &base##_rvalid, &base##_rready)
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

  AXI4WritePerfCounter &bind(SignalHandle awv, SignalHandle awr,
                             SignalHandle wv, SignalHandle wr, SignalHandle bv,
                             SignalHandle br) {
    hAWValid = awv;
    hAWReady = awr;
    hWValid = wv;
    hWReady = wr;
    hBValid = bv;
    hBReady = br;
    return *this;
  }
#define BIND_AXI4_W_BASE(base)                                                 \
  bind(&base##_awvalid, &base##_awready, &base##_wvalid, &base##_wready,       \
       &base##_bvalid, &base##_bready)
  void update();
};

class AXI4PerfCounterManager : public PerfCounterBase {
public:
  std::vector<AXI4ReadPerfCounter> rdCounters;
  std::vector<AXI4WritePerfCounter> wrCounters;
  void update();
  void dumpStatistics(std::ostream &) override;

  void add(AXI4ReadPerfCounter ctr, std::string path) {
    ctr.ctrName = path;
    ctr.init_logger();
    spdlog::debug("added AXI4 read perf counter '{}'", ctr.ctrName);
    rdCounters.push_back(std::move(ctr));
  }
  void add(AXI4WritePerfCounter ctr, std::string path) {
    ctr.ctrName = path;
    ctr.init_logger();
    spdlog::debug("added AXI4 write perf counter '{}'", ctr.ctrName);
    wrCounters.push_back(std::move(ctr));
  }

  void fillFields() override {
    ctrName = "AXI4PerfCounters";
    for (auto &ctr : rdCounters) {
      ctr.fillFields();
      for (auto &f : ctr.fields) {
        fields.push_back(Field{"rd_" + ctr.ctrName + "_" + f.label, f.value});
      }
    }
    for (auto &ctr : wrCounters) {
      ctr.fillFields();
      for (auto &f : ctr.fields) {
        fields.push_back(Field{"wr_" + ctr.ctrName + "_" + f.label, f.value});
      }
    }
  }
};

struct PipeStagePerfCounter : public PerfCounterBase {
  // check fetch handshake happened

  SignalHandle hInValid;
  SignalHandle hInReady;

  SignalHandle hOutValid;
  SignalHandle hOutReady;

  enum State { Backpressure, Bubble, Fire, STATE_NUM };

  size_t countOfState[STATE_NUM] = {0};

  static const char *nameOfState(int state);

  PipeStagePerfCounter &bind(SignalHandle inValid, SignalHandle inReady,
                             SignalHandle outValid, SignalHandle outReady) {
    hInValid = inValid;
    hInReady = inReady;
    hOutValid = outValid;
    hOutReady = outReady;
    return *this;
  }
#define BIND_PIPE_STAGE_BASE(base)                                             \
  bind(&base##_in_valid, &base##_in_ready, &base##_out_valid, &base##_out_ready)

  void update();
  void dumpStatistics(std::ostream &) override;

  size_t totalCount() const {
    return std::accumulate(countOfState, countOfState + STATE_NUM, 0ull);
  }

  void fillFields() override {
    for (size_t i = 0; i < STATE_NUM; i++) {
      fields.push_back(
          Field{std::string("state_") + nameOfState((int)i), countOfState[i]});
    }
  }
};

class PipePerfManager : public PerfCounterBase {
public:
  std::vector<PipeStagePerfCounter> stageCtrs;
  void add(PipeStagePerfCounter ctr, std::string name) {
    ctr.ctrName = name;
    stageCtrs.push_back(std::move(ctr));
  }
  void update() {
    for (auto &ctr : stageCtrs) {
      ctr.update();
    }
  }
  void dumpStatistics(std::ostream &) override;
  void fillFields() override {
    ctrName = "PipePerfManager";
    for (auto &ctr : stageCtrs) {
      ctr.fillFields();
      for (auto &f : ctr.fields) {
        fields.push_back(Field{ctr.ctrName + "_" + f.label, f.value});
      }
    }
  }
};

class CachePerfCounter : public PerfCounterBase {
public:
  enum State { idle, sendFetch, waitMem };

  SignalHandle hCacheHit;
  SignalHandle hState;

  SignalHandle hARValid, hARReady;

  // cache miss penalty counter
  AXI4ReadPerfCounter rdMemCtr;

  size_t totalVisitCount = 0;
  size_t hitCount = 0;

  sim_cycle_t currentHitAccessStartCycle = 0;
  size_t totalHitAccessCycles = 0;

  void bind();
  void update();
  void dumpStatistics(std::ostream &) override;
  void fillFields() override {
    ctrName = "CachePerfCounter";
    fields.push_back(Field{"total_visits", totalVisitCount});
    fields.push_back(Field{"hit_count", hitCount});
    fields.push_back(Field{"total_hit_cycles", totalHitAccessCycles});
    rdMemCtr.fillFields();
    for (auto &f : rdMemCtr.fields) {
      fields.push_back(Field{"mem_rd_" + f.label, f.value});
    }
  }

  double hitRate() const {
    return totalVisitCount == 0 ? NAN
                                : (double)hitCount / (double)totalVisitCount;
  }
  double avgHitAccessCycles() const {
    return hitCount == 0 ? NAN
                         : (double)totalHitAccessCycles / (double)hitCount;
  }
  double avgMissPenaltyCycles() const { return rdMemCtr.avgLatency(); }

  // avg memory access time in cycles
  double AMAT() const {
    return avgHitAccessCycles() + (1.0 - hitRate()) * avgMissPenaltyCycles();
  }
};

struct RAWStallPerfCounter : public PerfCounterBase {
  SignalHandle hIsConflictEXU;
  SignalHandle hIsConflictLSU;
  SignalHandle hIsConflictWBU;
  SignalHandle hIsIDUStall;

  size_t cycConflictEXU = 0;
  size_t cycConflictLSU = 0;
  size_t cycConflictWBU = 0;
  size_t cycIDUStall = 0;

  void bind();
  void update();

  void dumpStatistics(std::ostream &) override;
  void fillFields() override {
    ctrName = "RAWStallPerfCounter";
    fields.push_back(Field{"cyc_conflict_exu", cycConflictEXU});
    fields.push_back(Field{"cyc_conflict_lsu", cycConflictLSU});
    fields.push_back(Field{"cyc_conflict_wbu", cycConflictWBU});
    fields.push_back(Field{"cyc_idu_stall", cycIDUStall});
  }
};

struct IDUFlushPerfCounter : public PerfCounterBase {
  SignalHandle hIsFlushIDU;
  bool lastCycIsFlush = false;

  enum IDUFlushReason {
    BranchTaken,
    JALR,
    JAL,
    Exception,
    Unknown,
    REASON_NUM
  };
  IDUFlushReason lastFlushReason = IDUFlushReason::Unknown;

  size_t cycIDUFlush = 0;
  size_t cycFlushOfReason[REASON_NUM] = {0};

  void bind();
  void update();

  IDUFlushReason getCurReason() const;

  void dumpStatistics(std::ostream &) override;
  void fillFields() override {
    ctrName = "IDUFlushPerfCounter";
    fields.push_back(Field{"cyc_idu_flush", cycIDUFlush});
  }
};

struct BranchPredPerfCounter : public PerfCounterBase {
  SignalHandle hIsBranch;
  SignalHandle hValid;
  SignalHandle hReady;

  size_t totBranchCount = 0;
  size_t totMispredictCount = 0;

  void bind();
  void update();

  void dumpStatistics(std::ostream &) override;
  void fillFields() override {
    ctrName = "BranchPredPerfCounter";
    fields.push_back(Field{"tot_branch", totBranchCount});
    fields.push_back(Field{"tot_mispredict", totMispredictCount});
  }
};

using PerfCounterVariant =
    std::variant<HandShakeCounterManager, AXI4PerfCounterManager,
                 PipePerfManager, CachePerfCounter, RAWStallPerfCounter,
                 IDUFlushPerfCounter, BranchPredPerfCounter>;

void initPerfCounters();
void dumpPerfCountersStatistics(std::ostream &os);
void updatePerfCounters();

void dumpPerfCounterAsCSV(std::ostream &os);

void dumpPerfReportOnDir(const std::string &dir);
