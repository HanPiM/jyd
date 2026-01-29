#pragma once
#include "vpi_user.h"
#include <cstddef>
#include <cstdint>
#include <numeric>
#include <vector>
#include <verilated.h>
#include <verilated_vpi.h>

#include "vsrc.hpp"

#include "common.hpp"

#include "sim.hpp"

inline const std::string &cpu_vpi_path_prefix() {
  static std::string prefix;
  if (prefix.empty()) {
    prefix = std::string("TOP.") + std::string(_STR(TOP_NAME)).substr(1);
    prefix += ".asic.cpu.cpu.";
  }
  return prefix;
}

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


class HandShakeDetector {
public:
  using callback_t = std::function<void()>;
  struct ValidReadyBus {
    SignalHandle hValid;
    SignalHandle hReady;

    std::string description;

    size_t shake_count = 0;
    callback_t onShakeCallback = nullptr;

    bool shakeHappened();
		void dumpStatus();
  };
  std::shared_ptr<spdlog::logger> logger;
  std::vector<ValidReadyBus> bus_list;

  HandShakeDetector();

  void init();
  ValidReadyBus& add(std::string pathWithoutValidOrReady, std::string description = "",
           callback_t onShake = nullptr);

  void checkAndCountAll();
};

struct InstTypeCounter {
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

  static const char *name_of_fmt(InstFmt fmt);
  static const char *name_of_type(InstType type);

  size_t fmt_count[FMT_NUM] = {0};
  size_t type_count[TYPE_NUM] = {0};

  size_t tot_cycle_of_type[TYPE_NUM] = {0};
  size_t tot_cycle_of_fmt[FMT_NUM] = {0};

  // init with invalid value
  InstFmt lastInstFmt = FMT_NUM;
  InstType lastInstType = TYPE_NUM;

  uint64_t lastInstFetchCyc = 0;

  SignalHandle hInstType;
  SignalHandle hInstFmt;

  std::shared_ptr<spdlog::logger> logger;

  void init();

  void newInstFetched(uint64_t cycle);

  size_t totalInstCountSumByFmt() {
    return std::accumulate(fmt_count, fmt_count + FMT_NUM, 0ull);
  }
  size_t totalInstCountSumByType() {
    return std::accumulate(type_count, type_count + TYPE_NUM, 0ull);
  }
	double averageCPIOfType(InstType type){
		if(type_count[type]==0) return NAN;
		return (double)tot_cycle_of_type[type]/(double)type_count[type];
	}
	double averageCPIOfFmt(InstFmt fmt){
		if(fmt_count[fmt]==0) return NAN;
		return (double)tot_cycle_of_fmt[fmt]/(double)fmt_count[fmt];
	}
};


struct AXI4CounterBase {
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

	void dumpStatistics();
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

