#include "PerfCounter.hpp"
#include "sim.hpp"
#include "spdlog/fmt/bundled/format.h"
#include "spdlog/spdlog.h"
#include <fstream>
#include <iostream>
#include <vector>

#include <nlohmann/json.hpp>

using namespace DirectSignals;

HandShakeCounterManager::ValidReadyBus &
HandShakeCounterManager::add(SignalHandle hValid, SignalHandle hReady,
                             std::string description) {
  bus_list.emplace_back(ValidReadyBus{
      .hValid = hValid,
      .hReady = hReady,
      .description = description,
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
    }
  }
}

void PipeStagePerfCounter::update() {
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
  // Cache is currently disabled in the simple-bus refactor.
}

void CachePerfCounter::update() {
  // Cache is currently disabled in the simple-bus refactor.
}

void CachePerfCounter::dumpStatistics(std::ostream &os) {
  os << "Cache Performance Counter Statistics: disabled\n";
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
  hIsConflictEXU = &GetIDU()->bypassMux->isConflictWithEXU;
  hIsConflictLSU = &GetIDU()->bypassMux->isConflictWithLSU;
  hIsConflictWBU = &GetIDU()->bypassMux->isConflictWithWBU;
  hIsIDUStall = &GetIDU()->bypassMux->isStall;
}
IDUFlushPerfCounter::IDUFlushReason IDUFlushPerfCounter::getCurReason() const {
  auto &exu = *GetEXU();
  IDUFlushReason reason;
  if (exu.dbgIsBranch)
    reason = IDUFlushReason::BranchTaken;
  else if (exu.dbgIsJAL)
    reason = IDUFlushReason::JAL;
  else if (exu.dbgIsJALR)
    reason = IDUFlushReason::JALR;
  else if (exu.dbgIsCSRJmp)
    reason = IDUFlushReason::Exception;
  else if (exu.io_predWrong)
    reason = IDUFlushReason::PredRecover;
  else {
    reason = IDUFlushReason::Unknown;
    spdlog::warn("Unknown flush reason at {}ps", sim_get_time());
  }

  return reason;
}
void IDUFlushPerfCounter::update() {
  bool isRedirectNowRaisingEdge =
      (!lastCycRedirectNow && hRedirectNow.get());
  lastCycIsFlush = hIsFlushIDU.get();
  lastCycRedirectNow = hRedirectNow.get();

  if (isRedirectNowRaisingEdge) {
    lastFlushReason = getCurReason();
  }

  if (hIsFlushIDU.get()) {
    cycIDUFlush++;
    cycFlushOfReason[lastFlushReason]++;
  }
}
void IDUFlushPerfCounter::bind() {
  hIsFlushIDU = &GetCPU()->activeRedirectValid;
  hRedirectNow = &GetCPU()->redirectNow;
}

void BranchPredPerfCounter::bind() {
  hValid = &GetEXU()->io_in_valid;
  hReady = &GetEXU()->io_in_ready;
}

int BranchPredPerfCounter::getCurJmpType() const {
  auto &exu = *GetEXU();
  if (exu.dbgIsBranch)
    return JmpType::Branch;
  else if (exu.dbgIsJAL)
    return JmpType::JAL;
  else if (exu.dbgIsJALR)
    return JmpType::JALR;
  else if (exu.dbgIsCSRJmp)
    return JmpType::Exception;

  return JmpType::JmpTypeNum;
}

void BranchPredPerfCounter::update() {
  if (hValid.get() && hReady.get()) {
    auto jmpType = getCurJmpType();
    if (jmpType >= JmpType::JmpTypeNum) {
      return;
    }
    totCountOfType[jmpType]++;
    if (GetEXU()->io_predWrong) {
      totMispredictOfType[jmpType]++;
    }
  }
}

std::vector<PerfCounterVariant> perf_counters;

void initPerfCounters() {
  PipePerfManager pipeCtr;
  RAWStallPerfCounter rawStallCtr;
  IDUFlushPerfCounter iduFlushCtr;
  BranchPredPerfCounter branchPredCtr;

  pipeCtr.add(PipeStagePerfCounter().bind(
                  &GetIFU()->io_mem_req_valid, &GetIFU()->io_mem_req_ready,
                  &GetIFU()->io_out_valid, &GetIFU()->io_out_ready),
              "IFU");
  pipeCtr.add(PipeStagePerfCounter().BIND_PIPE_STAGE_BASE(GetIDU()->io), "IDU");
  pipeCtr.add(PipeStagePerfCounter().BIND_PIPE_STAGE_BASE(GetEXU()->io), "EXU");
  pipeCtr.add(PipeStagePerfCounter().BIND_PIPE_STAGE_BASE(GetLSU()->io), "LSU");

  iduFlushCtr.bind();
  rawStallCtr.bind();
  branchPredCtr.bind();

  perf_counters.push_back(std::move(pipeCtr));
  perf_counters.push_back(std::move(rawStallCtr));
  perf_counters.push_back(std::move(iduFlushCtr));
  perf_counters.push_back(std::move(branchPredCtr));
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
	os << fmt::format("  total instruction count: {} ({}M)\n", inst_count, inst_count / 1000000);

  if (cycle_count == 0) {
    spdlog::warn("cycle count is 0, cannot calc IPC");
  } else {
    double ipc = (double)inst_count / (double)cycle_count;
    os << fmt::format("  IPC: {:.4f}\n", ipc);
  }
  if (inst_count == 0) {
    spdlog::warn("no instruction executed, cannot calc CPI");
  } else {
    double cpi = (double)cycle_count / (double)inst_count;
    os << fmt::format("  CPI: {:.4f}\n", cpi);
  }

	os << "excution time estimate:\n";
	os << fmt::format("  {:>8} {:>10}\n", "Clk(Mhz)", "Time(s)");
	double clk_freqs[] = {50e6, 100e6, 200e6, 250e6};
	for (double freq : clk_freqs) {
		double time_sec = (double)cycle_count / freq;
		os << fmt::format("  {:>8.0f} {:>10.5f}\n", freq / 1e6, time_sec);
	}

  for (auto &ctr : perf_counters) {
    std::visit([&](auto &c) { c.dumpStatistics(os); }, ctr);
  }
}

NLOHMANN_DEFINE_TYPE_NON_INTRUSIVE(HandShakeCounterManager::ValidReadyBus,
                                   description, shake_count)
NLOHMANN_DEFINE_TYPE_NON_INTRUSIVE(HandShakeCounterManager, bus_list)

NLOHMANN_DEFINE_TYPE_NON_INTRUSIVE(AXI4CounterBase::LatencyRecord, startTime,
                                   endTime, cycles)

NLOHMANN_DEFINE_TYPE_NON_INTRUSIVE(AXI4CounterBase, ctrName, transaction_count,
                                   total_latency_cycles, maxRecord)

NLOHMANN_DEFINE_TYPE_NON_INTRUSIVE(AXI4PerfCounterManager, rdCounters,
                                   wrCounters)

void to_json(nlohmann::json &j, const PipeStagePerfCounter &c) {
  j["ctrName"] = c.ctrName;
  for (int s = 0; s < PipeStagePerfCounter::STATE_NUM; s++) {
    j["countOfState"][s] = {
        {"state", PipeStagePerfCounter::nameOfState(s)},
        {"count", c.countOfState[s]},
    };
  }
}

NLOHMANN_DEFINE_TYPE_NON_INTRUSIVE(PipePerfManager, stageCtrs)

NLOHMANN_DEFINE_TYPE_NON_INTRUSIVE(CachePerfCounter, totalVisitCount, hitCount,
                                   totalHitAccessCycles, rdMemCtr)
NLOHMANN_DEFINE_TYPE_NON_INTRUSIVE(RAWStallPerfCounter, cycConflictEXU,
                                   cycConflictLSU, cycConflictWBU, cycIDUStall)

void to_json(nlohmann::json &j, const IDUFlushPerfCounter &c) {
  j["ctrName"] = c.ctrName;
  j["cycIDUFlush"] = c.cycIDUFlush;
  for (int r = 0; r < IDUFlushPerfCounter::IDUFlushReason::REASON_NUM; r++) {
    j["cycFlushOfReason"][r] = {
        {"reason", IDUFlushPerfCounter::nameOfReason(r)},
        {"count", c.cycFlushOfReason[r]},
    };
  }
}
void to_json(nlohmann::json &j, const BranchPredPerfCounter &c) {
  j["ctrName"] = c.ctrName;
  for (int t = 0; t < BranchPredPerfCounter::JmpType::JmpTypeNum; t++) {
    j["totCountOfType"][t] = {
        {"type", BranchPredPerfCounter::nameOf(t)},
        {"count", c.totCountOfType[t]},
    };
    j["totMispredictOfType"][t] = {
        {"type", BranchPredPerfCounter::nameOf(t)},
        {"count", c.totMispredictOfType[t]},
    };
  }
}

void dumpPerfCounterTo(std::ostream &os) {
  // std::string title_row;
  std::string value_row;

  nlohmann::json j;

  bool first = true;
  for (auto &ctr : perf_counters) {
    std::visit([&](auto &c) { j[c.ctrName] = c; }, ctr);
  }
  // os << "\n" << value_row;
  // os << j.dump(2);
  os << j;
}
void dumpPerfReportOnDir(const std::string &dir) {
  std::string prefix = "counters";
  std::string reportPath = dir + '/' + prefix + ".report.txt";
  std::ofstream reportFile(reportPath);
  if (!reportFile.is_open()) {
    spdlog::error("cannot open perf counter report file {}", reportPath);
    return;
  }
  dumpPerfCountersStatistics(reportFile);
  reportFile.close();
  spdlog::info("perf counter report dumped to {}", reportPath);
  std::string dataPath = dir + '/' + prefix + ".rawdata.json";
  std::ofstream dataFile(dataPath);
  if (!dataFile.is_open()) {
    spdlog::error("cannot open perf counter csv file {}", dataPath);
    return;
  }
  // dumpPerfCounterTo(std::cout);
  dumpPerfCounterTo(dataFile);
  dataFile.close();
  spdlog::info("perf counter csv dumped to {}", dataPath);
}
