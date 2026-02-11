#include "PerfCounter.hpp"
#include "sim.hpp"
#include "spdlog/fmt/bundled/format.h"
#include <fstream>
#include <vector>

auto _GetCPU() {
  // use vlSymsp to get inner module/signal
#ifdef SIM_SOC
  return &get_dut()->ysyxSoCFull->vlSymsp->TOP__ysyxSoCFull__asic__cpu__cpu;
#else
  return &get_dut()->ysyx_25100261->vlSymsp->TOP__ysyx_25100261;
#endif
}

auto _GetIFU() { return _GetCPU()->ifu; }
auto _GetEXU() { return _GetCPU()->exu; }
auto _GetLSU() { return _GetCPU()->lsu; }
auto _GetALU() { return _GetEXU()->alu; }
auto _GetIDU() { return _GetCPU()->idu; }

auto _GetICache() { return _GetCPU()->icache; }

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

bool HandShakeCounterManager::ValidReadyBus::shakeHappened() {
  return hValid.get() && hReady.get();
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

// void IFUStateCounter::bind() {
//   hInValid = &_GetIFU()->io_mem_rvalid;
//   hInReady = &_GetIFU()->io_mem_rready;
//   // hState = &_GetIFU()->state;
//
//   hOutValid = &_GetIFU()->io_out_valid;
//   hOutReady = &_GetIFU()->io_out_ready;
// }
void PipeStagePerfCounter::update() {
  // bool fetchInstHappened = (hInReady.get() && hInValid.get());
  // if (fetchInstHappened) {
  //   totalFetchCount++;
  // }

  State s;
  if (hOutReady.get()) {
    if (hOutValid.get()) {
      s = Fire;
    } else {
      s = Bubble;
    }
  } else {
    s = Backpressure;
  }
  countOfState[s]++;
}

void CachePerfCounter::bind() {
  hARValid = &_GetICache()->io_cpu_arvalid;
  hARReady = &_GetICache()->io_cpu_arready;
  hCacheHit = &_GetICache()->cacheHit;
  hState = &_GetICache()->state;

  rdMemCtr.BIND_AXI4_R_BASE(_GetICache()->io_mem);
}

void CachePerfCounter::update() {
  rdMemCtr.update();
  if (hARValid.get() && hARReady.get()) {
    totalVisitCount++;
    currentHitAccessStartCycle = sim_get_cycle();
    auto s = (State)hState.get();
    if (s == idle && hCacheHit.get()) {
      hitCount++;
      totalHitAccessCycles += sim_get_cycle() - currentHitAccessStartCycle;
    }
  }
}

void CachePerfCounter::dumpStatistics(std::ostream &os) {
  os << "Cache Performance Counter Statistics:\n";
  os << "hit rate: " << hitCount << " / " << totalVisitCount << " = "
     << hitRate() * 100.0 << " %\n";
  os << "average hit access cycles: " << avgHitAccessCycles() << "\n";
  os << "average miss access cycles: " << avgMissPenaltyCycles() << "\n";
  os << "AMAT : " << AMAT() << "\n";
}

void RAWStallPerfCounter::update() {
  if (hIsIDUStall.get()) {
    cycIDUStall++;

    if (hIsConflictEXU.get()) {
      cycConflictEXU++;
    }
    if (hIsConflictLSU.get()) {
      cycConflictLSU++;
    }
    if (hIsConflictWBU.get()) {
      cycConflictWBU++;
    }
  }
}
void RAWStallPerfCounter::bind() {
  hIsConflictEXU = &_GetCPU()->isConflictWithEXU;
  hIsConflictLSU = &_GetCPU()->isConflictWithLSU;
  hIsConflictWBU = &_GetCPU()->isConflictWithWBU;
  hIsIDUStall = &_GetCPU()->isIDUStall;
}

std::vector<PerfCounterVariant> perf_counters;

void initPerfCounters() {

  HandShakeCounterManager handshakeCtr;
  // EXUPerfCounter exuCtr;
  AXI4PerfCounterManager axi4Ctr;

  PipePerfManager pipeCtr;
  RAWStallPerfCounter rawStallCtr;

  CachePerfCounter cacheCtr;

  handshakeCtr.init();
  handshakeCtr.add(&_GetIFU()->io_mem_rvalid, &_GetIFU()->io_mem_rready,
                   "ifu.io_mem_r", "IFU fetch inst");
  handshakeCtr.add(&_GetLSU()->io_mem_rvalid, &_GetLSU()->io_mem_rready,
                   "lsu.io_mem_r", "EXU load data");
  // ALU now is combintional logic, no handshake
  //
  // handshakeCtr.add(&_GetALU()->io_out_valid, &_GetALU()->io_out_ready,
  //                  "exu.alu.io_out", "EXU calc");
  handshakeCtr.add(&_GetIDU()->io_out_valid, &_GetIDU()->io_out_ready,
                   "idu.io_out", "IDU decode inst");

  axi4Ctr.add(AXI4WritePerfCounter().BIND_AXI4_W_BASE(_GetLSU()->io_mem),
              "lsu_mem_write");
  axi4Ctr.add(AXI4ReadPerfCounter().BIND_AXI4_R_BASE(_GetLSU()->io_mem),
              "lsu_mem_read");
  axi4Ctr.add(AXI4ReadPerfCounter().BIND_AXI4_R_BASE(_GetIFU()->io_mem),
              "ifu_mem_read");

  pipeCtr.add(PipeStagePerfCounter().bind(
                  &_GetIFU()->io_pc_valid, &_GetIFU()->io_pc_ready,
                  &_GetIFU()->io_out_valid, &_GetIFU()->io_out_ready),
              "IFU");
  pipeCtr.add(PipeStagePerfCounter().BIND_PIPE_STAGE_BASE(_GetIDU()->io),
              "IDU");
  pipeCtr.add(PipeStagePerfCounter().BIND_PIPE_STAGE_BASE(_GetEXU()->io),
              "EXU");
  pipeCtr.add(PipeStagePerfCounter().BIND_PIPE_STAGE_BASE(_GetLSU()->io),
              "LSU");

  cacheCtr.bind();
  rawStallCtr.bind();

  perf_counters.push_back(std::move(handshakeCtr));
  // perf_counters.push_back(std::move(exuCtr));
  perf_counters.push_back(std::move(axi4Ctr));
  perf_counters.push_back(std::move(pipeCtr));
  perf_counters.push_back(std::move(rawStallCtr));
  perf_counters.push_back(std::move(cacheCtr));
}

void updatePerfCounters() {
  for (auto &ctr : perf_counters) {
    std::visit([&](auto &c) { c.update(); }, ctr);
  }
}
void dumpPerfCountersStatistics(std::ostream &os) {
  auto cycle_count = sim_get_cycle();
  auto inst_count = sim_get_inst_count();

  os << "Perf Counters Report\n";
  os << "Git commit: " << _STR(GIT_COMMIT_HASH) << "\n\n";

  os << "Statistics:\n";
  os << "cycle and instruction counts:\n";
  os << "  total cycle count: " << cycle_count << "\n";
  os << "  total instruction count: " << inst_count << "\n";
  if (cycle_count == 0) {
    spdlog::warn("cycle count is 0, cannot calc IPC");
  } else {
    double ipc = (double)inst_count / (double)cycle_count;
    os << fmt::format("  IPC: {:.4f}\n", ipc);
    // fmt::println("  IPC: {:.4f}", ipc);
  }
  if (inst_count == 0) {
    spdlog::warn("no instruction executed, cannot calc CPI");
  } else {
    double cpi = (double)cycle_count / (double)inst_count;
    // fmt::println("  CPI: {:.4f}", cpi);
    os << fmt::format("  CPI: {:.4f}\n", cpi);
  }

  for (auto &ctr : perf_counters) {
    std::visit([&](auto &c) { c.dumpStatistics(os); }, ctr);
  }
}

void dumpPerfCounterAsCSV(std::ostream &os) {
  // std::string title_row;
  std::string value_row;

  bool first = true;
  for (auto &ctr : perf_counters) {
    std::visit(
        [&](auto &c) {
          c.fillFields();
          for (auto &f : c.fields) {
            if (!first) {
              os << ",";
              value_row += ",";
            } else {
              first = false;
            }
            // title_row += c.ctrName + "_" + f.label;
            os << c.ctrName + "_" + f.label;
            value_row += std::to_string(f.value);
          }
          c.clearFields();
        },
        ctr);
  }
  os << "\n" << value_row;
}
void dumpPerfReportOnDir(const std::string &dir) {
  std::string prefix = "new_test_pipe";
  std::string reportPath = dir + '/' + prefix + ".counter.rpt";
  std::ofstream reportFile(reportPath);
  if (!reportFile.is_open()) {
    spdlog::error("cannot open perf counter report file {}", reportPath);
    return;
  }
  dumpPerfCountersStatistics(reportFile);
  reportFile.close();
  spdlog::info("perf counter report dumped to {}", reportPath);
  std::string csvPath = dir + '/' + prefix + "rawdata.csv";
  std::ofstream csvFile(csvPath);
  if (!csvFile.is_open()) {
    spdlog::error("cannot open perf counter csv file {}", csvPath);
    return;
  }
  dumpPerfCounterAsCSV(csvFile);
  csvFile.close();
  spdlog::info("perf counter csv dumped to {}", csvPath);
}
