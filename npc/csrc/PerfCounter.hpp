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

	static InstFmt OneHotToFmt(uint32_t onehot);
	static InstType OneHotToType(uint32_t onehot);

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

  void bind();
  void update();

  void _dump(size_t *instCnts, size_t *cycCnts, size_t num,
             const char *(*nameFunc)(int), std::ostream &os);
  void dumpStatistics(std::ostream &) override;

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

  void bind();
  void update();
  void dumpStatistics(std::ostream &) override;

  void fillFields() override {
    ctrName = "IFUStateCounter";
    for (size_t i = 0; i < STATE_NUM; i++) {
      fields.push_back(
          Field{std::string("state_") + nameOfState((int)i), countOfState[i]});
    }
  }
};

class CachePerfCounter : public PerfCounterBase {
public:
  enum State { idle, checkCache, sendFetch, waitMem, respCPU };

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
    return hitCount == 0
               ? NAN
               : (double)totalHitAccessCycles / (double)hitCount;
  }
  double avgMissPenaltyCycles() const { return rdMemCtr.avgLatency(); }

  // avg memory access time in cycles
  double AMAT() const {
    return avgHitAccessCycles() + (1.0 - hitRate()) * avgMissPenaltyCycles();
  }
};

using PerfCounterVariant =
    std::variant<HandShakeCounterManager, EXUPerfCounter,
                 AXI4PerfCounterManager, IFUStateCounter, CachePerfCounter>;

void initPerfCounters();
void dumpPerfCountersStatistics(std::ostream &os);
void updatePerfCounters();

void dumpPerfCounterAsCSV(std::ostream &os);

void dumpPerfReportOnDir(const std::string &dir);
