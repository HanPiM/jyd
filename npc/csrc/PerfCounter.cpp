#include "PerfCounter.hpp"
#include "sim.hpp"
#include <vector>

#include "VysyxSoCFull__Syms.h"

auto _GetCPU() {
  return &get_dut()->ysyxSoCFull->vlSymsp->TOP__ysyxSoCFull__asic__cpu__cpu;
}

auto _GetIFU() { return _GetCPU()->ifu; }
auto _GetEXU() { return _GetCPU()->exu; }
auto _GetALU() { return _GetEXU()->alu; }
auto _GetIDU() { return _GetCPU()->idu; }

using namespace _PerfCtrImp;

void HandShakeCounterManager::init() {
  logger = spdlog::stdout_color_mt("HandShakeDetector");
  set_logger_pattern_with_simtime(logger);
  logger->set_level(spdlog::level::info);
}

HandShakeCounterManager::ValidReadyBus &
HandShakeCounterManager::add(SignalHandle hValid, SignalHandle hReady,
                             std::string barePath, std::string description,
                             callback_t onShake) {
  bus_list.emplace_back(ValidReadyBus{
      .hValid = hValid,
      .hReady = hReady,
      .pathWithoutValidOrReady = barePath,
      .description = description,
      .onShakeCallback = onShake,
  });
  return bus_list.back();
}

void HandShakeCounterManager::dumpStatistics() {
  spdlog::info(">handshake counts:");
  for (auto &bus : bus_list) {
    bus.dumpStatus();
  }
}

bool HandShakeCounterManager::ValidReadyBus::shakeHappened() {
  return hValid.get() && hReady.get();
}
void HandShakeCounterManager::ValidReadyBus::dumpStatus() {
  fmt::println("  {:18} happened {:>7} times (freq {:.4f})", description,
               shake_count, (double)shake_count / (double)sim_get_cycle());
}

void HandShakeCounterManager::update() {
  for (auto &bus : bus_list) {
    if (bus.shakeHappened()) {
      bus.shake_count++;
      // logger->trace("Handshake happened on {} (total count {})",
      //               _DebugPath(bus.description), bus.shake_count);
      if (bus.onShakeCallback) {
        bus.onShakeCallback();
      }
    }
  }
}

const char *EXUPerfCounter::nameOfTyp(int type) {
  static const char *type_names[] = {
      "branch", "arithmetic", "load",  "store",  "jalr",
      "jal",    "lui",        "auipc", "system",
  };
  if (type < sizeof(type_names) / sizeof(type_names[0])) {
    return type_names[type];
  } else {
    return "unknown";
  }
}
const char *EXUPerfCounter::nameOfFmt(int fmt) {
  static const char *fmt_names[] = {
      "I_TYPE", "R_TYPE", "S_TYPE", "U_TYPE", "J_TYPE", "B_TYPE",
  };
  if (fmt < sizeof(fmt_names) / sizeof(fmt_names[0])) {
    return fmt_names[fmt];
  } else {
    return "unknown";
  }
}
void EXUPerfCounter::bind() {
  logger = spdlog::stdout_color_mt("InstTypeCounter");
  set_logger_pattern_with_simtime(logger);
  logger->set_level(spdlog::level::info);
  hInstType = &_GetIDU()->io_out_bits_info_typ;
  hInstFmt = &_GetIDU()->io_out_bits_info_fmt;
  hOutValid = &_GetIDU()->io_out_valid;
  hOutReady = &_GetIDU()->io_out_ready;
}
void EXUPerfCounter::update() {

  bool isOutValidRasingEdge = (!lastCycOutValid && hOutValid.get());
  lastCycOutValid = hOutValid.get();

  //
  // For history reason the timing is wired
	//
	// the exu cost time is calculated from idu
  //
  // a inst start execution approximately when out_valid rises
  // (TODO: figure out the exact timing)
  // and ends when out_ready highs
  //

  if (isOutValidRasingEdge) {
    instStartCycle = sim_get_cycle();
  }

  if (hOutReady.get() && hOutValid.get()) {
    // instruction finished execution
    InstType type = (InstType)hInstType.get();
    InstFmt fmt = (InstFmt)hInstFmt.get();

    assert(isValidType(type));
    assert(isValidFmt(fmt));

    instCountOfTyp[type]++;
    instCountOfFmt[fmt]++;

    auto instEndCycle = sim_get_cycle();
		// fmt::println("Instruction executed: type {} fmt {} cycles {}",
		// 						 nameOfTyp(type), nameOfFmt(fmt),
		// 						 instEndCycle - instStartCycle);
    auto instCycles = instEndCycle - instStartCycle;
    totalCycleOfTyp[type] += instCycles;
    totalCycleOfFmt[fmt] += instCycles;

    // logger->trace("inst executed: type {} fmt {} cycles {}", nameOfTyp(type),
    //               nameOfFmt(fmt), instCycles);
  }
}

void IFUStateCounter::bind() {
  hRValid = &_GetIFU()->io_mem_rvalid;
  hRReady = &_GetIFU()->io_mem_rready;
  hState = &_GetIFU()->fsm->state;
}
void IFUStateCounter::update() {
  bool fetchInstHappened = (hRReady.get() && hRValid.get());
  State s = (State)hState.get();
  countOfState[s]++;
  if (fetchInstHappened) {
    totalFetchCount++;
  } else {
    countOfStateWhenNoFetch[s]++;
  }
}

void EXUPerfCounter::dumpStatistics() {
  spdlog::info(">instruction type counts:");

  spdlog::info("  by type:");
  _dump(instCountOfTyp, totalCycleOfTyp, TYPE_NUM, nameOfTyp);
  spdlog::info("  by format:");
  _dump(instCountOfFmt, totalCycleOfFmt, FMT_NUM, nameOfFmt);
}

std::vector<PerfCounterVariant> perf_counters;

void initPerfCounters() {

  HandShakeCounterManager handshakeCtr;
  EXUPerfCounter exuCtr;
  AXI4PerfCounterManager axi4Ctr;
  IFUStateCounter ifuStateCtr;

  handshakeCtr.init();
  handshakeCtr.add(&_GetIFU()->io_mem_rvalid, &_GetIFU()->io_mem_rready,
                   "ifu.io_mem_r", "IFU fetch inst");
  handshakeCtr.add(&_GetEXU()->io_mem_rvalid, &_GetEXU()->io_mem_rready,
                   "exu.io_mem_r", "EXU load data");
  handshakeCtr.add(&_GetALU()->io_out_valid, &_GetALU()->io_out_ready,
                   "exu.alu.io_out", "EXU calc");
  handshakeCtr.add(&_GetIDU()->io_out_valid, &_GetIDU()->io_out_ready,
                   "idu.io_out", "IDU decode inst");

  axi4Ctr.add(AXI4WritePerfCounter().BIND_AXI4_W_BASE(_GetEXU()->io_mem),
              "exu_mem_write");
  axi4Ctr.add(AXI4ReadPerfCounter().BIND_AXI4_R_BASE(_GetEXU()->io_mem),
              "exu_mem_read");
  axi4Ctr.add(AXI4ReadPerfCounter().BIND_AXI4_R_BASE(_GetIFU()->io_mem),
              "ifu_mem_read");

  // axi4Ctr.addRead("exu.io_mem", "EXU load data");
  // axi4Ctr.addWrite("exu.io_mem", "EXU store data");
  //
  // axi4Ctr.addRead("ifu.io_mem", "IFU fetch inst");

  exuCtr.bind();
  ifuStateCtr.bind();

  perf_counters.push_back(std::move(handshakeCtr));
  perf_counters.push_back(std::move(exuCtr));
  perf_counters.push_back(std::move(axi4Ctr));
  perf_counters.push_back(std::move(ifuStateCtr));
}

void updatePerfCounters() {
  for (auto &ctr : perf_counters) {
    std::visit([&](auto &c) { c.update(); }, ctr);
  }
}
void dumpPerfCountersStatistics() {
  auto cycle_count = sim_get_cycle();
  auto inst_count = sim_get_inst_count();
  spdlog::info("simulation statistics:");
  fmt::println(">cycle and instruction counts:");
  fmt::println("  total cycle count: {}", cycle_count);
  fmt::println("  total instruction count: {}", inst_count);
  if (cycle_count == 0) {
    spdlog::warn("cycle count is 0, cannot calc IPC");
  } else {
    double ipc = (double)inst_count / (double)cycle_count;
    fmt::println("  IPC: {:.4f}", ipc);
  }
  if (inst_count == 0) {
    spdlog::warn("no instruction executed, cannot calc CPI");
  } else {
    double cpi = (double)cycle_count / (double)inst_count;
    fmt::println("  CPI: {:.4f}", cpi);
  }

  for (auto &ctr : perf_counters) {
    std::visit([&](auto &c) { c.dumpStatistics(); }, ctr);
  }
}

std::string dumpPerfCounterAsCSV() {
  std::string title_row;
  std::string value_row;
  for (auto &ctr : perf_counters) {
    std::visit(
        [&](auto &c) {
          c.fillFields();
          for (auto &f : c.fields) {
            if (!title_row.empty()) {
              title_row += ",";
              value_row += ",";
            }
            title_row += c.ctrName + "_" + f.label;
            value_row += std::to_string(f.value);
          }
          c.clearFields();
        },
        ctr);
  }
  return title_row + "\n" + value_row;
}
