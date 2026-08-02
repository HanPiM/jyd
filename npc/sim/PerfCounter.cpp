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
  // The current DCache lookup result is already exposed in the EXU-to-LSU
  // payload; no extra RTL instrumentation is needed.
}

void CachePerfCounter::update() {
  const auto *exu = GetEXU();
  const bool issued = exu->io_in_valid && exu->io_in_ready;
  if (issued && exu->io_out_bits_cacheableLoad) {
    totalVisitCount++;
    if (exu->io_out_bits_dcacheHit) {
      hitCount++;
    }
  }
}

void CachePerfCounter::dumpStatistics(std::ostream &os) {
  os << "DCache Cacheable Load Performance Counter Statistics:\n";
  os << "  definition: EXU-issued cacheable loads; hit is the dcacheHit "
        "captured for that EXU-to-LSU payload\n";
  os << "  accesses: " << totalVisitCount << "\n";
  os << "  hits: " << hitCount << "\n";
  os << "  misses: " << missCount() << "\n";
  os << fmt::format("  hit rate: {:.4f}%\n", hitRate() * 100.0);
}

void RAWStallPerfCounter::update() {
  const bool isAnyConflict = hIsAnyConflict.get();
  const bool isConflictEXU = hIsConflictEXU.get();
  const bool isConflictLSU = hIsConflictLSU.get();
  const bool isConflictWBU = hIsConflictWBU.get();
  const bool isConflictOnlyEXU = hIsConflictOnlyEXU.get();
  const bool isConflictOnlyLSU = hIsConflictOnlyLSU.get();
  const bool isConflictOnlyWBU = hIsConflictOnlyWBU.get();

  const bool isStallEXU = hIsStallEXU.get();
  const bool isStallLSU = hIsStallLSU.get();
  const bool isStallWBU = hIsStallWBU.get();
  const bool isStallOnlyEXU = hIsStallOnlyEXU.get();
  const bool isStallOnlyLSU = hIsStallOnlyLSU.get();
  const bool isStallOnlyWBU = hIsStallOnlyWBU.get();
  const bool isIDUStall = hIsIDUStall.get();
  const bool actualStall = hActualStall.get();

  if (isAnyConflict) {
    cycAnyConflict++;
  }
  if (isConflictEXU) {
    cycAllConflictEXU++;
  }
  if (isConflictLSU) {
    cycAllConflictLSU++;
  }
  if (isConflictWBU) {
    cycAllConflictWBU++;
  }
  if (isConflictOnlyEXU) {
    cycConflictOnlyEXU++;
  }
  if (isConflictOnlyLSU) {
    cycConflictOnlyLSU++;
  }
  if (isConflictOnlyWBU) {
    cycConflictOnlyWBU++;
  }

  if (isStallEXU) {
    cycStallEXU++;
  }
  if (isStallLSU) {
    cycStallLSU++;
  }
  if (isStallWBU) {
    cycStallWBU++;
  }
  if (isStallOnlyEXU) {
    cycStallOnlyEXU++;
  }
  if (isStallOnlyLSU) {
    cycStallOnlyLSU++;
  }
  if (isStallOnlyWBU) {
    cycStallOnlyWBU++;
  }

  if (isIDUStall) {
    cycIDUStall++;

    if (isConflictEXU) {
      cycConflictEXU++;
    }
    if (isConflictLSU) {
      cycConflictLSU++;
    }
    if (isConflictWBU) {
      cycConflictWBU++;
    }
  }
  if (actualStall) {
    cycActualStall++;
    cycActualBypassStall += hActualBypassStall.get();
    cycActualReg1AddImmEXUStall += hActualReg1AddImmEXUStall.get();
    cycActualReg1AddImmWBUStall += hActualReg1AddImmWBUStall.get();
    const uint32_t inst = hStalledInst.get();
    const unsigned bucket = (inst & 0x7f) | (((inst >> 12) & 7) << 7);
    actualStallByOpcodeFunc3[bucket]++;
    if (isStallEXU) {
      cycActualEXUStall++;
      actualEXUStallByOpcodeFunc3[bucket]++;
    }
    if (isStallLSU) {
      cycActualLSUStall++;
      actualLSUStallByOpcodeFunc3[bucket]++;
    }
    cycActualWBUStall += isStallWBU;
  }
}
void RAWStallPerfCounter::bind() {
  hIsAnyConflict = &GetIDU()->perfCounterLayer->rawStallPerfTap->io_isAnyConflict;
  hIsConflictEXU = &GetIDU()->perfCounterLayer->rawStallPerfTap->io_isConflictEXU;
  hIsConflictLSU = &GetIDU()->perfCounterLayer->rawStallPerfTap->io_isConflictLSU;
  hIsConflictWBU = &GetIDU()->perfCounterLayer->rawStallPerfTap->io_isConflictWBU;
  hIsConflictOnlyEXU =
      &GetIDU()->perfCounterLayer->rawStallPerfTap->io_isConflictOnlyEXU;
  hIsConflictOnlyLSU =
      &GetIDU()->perfCounterLayer->rawStallPerfTap->io_isConflictOnlyLSU;
  hIsConflictOnlyWBU =
      &GetIDU()->perfCounterLayer->rawStallPerfTap->io_isConflictOnlyWBU;

  hIsStallEXU = &GetIDU()->perfCounterLayer->rawStallPerfTap->io_isNeedStallEXU;
  hIsStallLSU = &GetIDU()->perfCounterLayer->rawStallPerfTap->io_isNeedStallLSU;
  hIsStallWBU = &GetIDU()->perfCounterLayer->rawStallPerfTap->io_isNeedStallWBU;
  hIsStallOnlyEXU =
      &GetIDU()->perfCounterLayer->rawStallPerfTap->io_isNeedStallOnlyEXU;
  hIsStallOnlyLSU =
      &GetIDU()->perfCounterLayer->rawStallPerfTap->io_isNeedStallOnlyLSU;
  hIsStallOnlyWBU =
      &GetIDU()->perfCounterLayer->rawStallPerfTap->io_isNeedStallOnlyWBU;
  hIsIDUStall = &GetIDU()->perfCounterLayer->rawStallPerfTap->io_isAnyStall;
  hActualStall = &GetIDU()->perfCounterLayer->rawStallPerfTap->io_actualStall;
  hActualBypassStall = &GetIDU()->perfCounterLayer->rawStallPerfTap->io_actualBypassStall;
  hActualReg1AddImmEXUStall =
      &GetIDU()->perfCounterLayer->rawStallPerfTap->io_actualReg1AddImmEXUStall;
  hActualReg1AddImmWBUStall =
      &GetIDU()->perfCounterLayer->rawStallPerfTap->io_actualReg1AddImmWBUStall;
  hStalledInst = &GetIDU()->perfCounterLayer->rawStallPerfTap->io_stalledInst;
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

void OptimizationDirectionPerfCounter::update() {
  const auto *exu = GetEXU();
  if (exu->io_in_valid && exu->io_in_ready) {
    const uint32_t inst = exu->io_in_bits_code;
    const uint32_t opcode = inst & 0x7f;
    const uint32_t func3 = (inst >> 12) & 0x7;
    const uint32_t func7 = (inst >> 25) & 0x7f;
    if (opcode == 0x33 && func7 == 0x01) {
      mOpCount[func3]++;
      if (func3 == 0) {
        const uint32_t lhs = exu->io_in_bits_info_reg1;
        const uint32_t rhs = exu->io_in_bits_info_reg2;
        mulOperandZero += lhs == 0 || rhs == 0;
        mulOperandOne += lhs == 1 || rhs == 1;
        const auto isPowerOfTwo = [](uint32_t value) {
          return value != 0 && (value & (value - 1)) == 0;
        };
        mulOperandPowerOfTwo += isPowerOfTwo(lhs) || isPowerOfTwo(rhs);
        mulBothUnsigned16 += lhs <= 0xffff && rhs <= 0xffff;
      }
    }
    if (opcode == 0x33 && func7 == 0x05 && func3 == 0x03 &&
        exu->io_in_bits_info_reg2 == 0x00014002) {
      coreMarkCRCClmulhCount++;
    }

    const bool lateRs1 = exu->io_in_bits_info_lateLoadRs1;
    const bool lateRs2 = exu->io_in_bits_info_lateLoadRs2;
    if (lateRs1 || lateRs2) {
      lateLoadAddCount[lateRs1 && lateRs2
                           ? BothRs
                           : (lateRs1 ? Rs1Only : Rs2Only)]++;
    }
  }

  if (exu->io_dcache_storeUpdate && exu->memWMask == 0xf) {
    cacheableFullWordStores++;
  }

  const auto *idu = GetIDU();
  const auto *rawTap = idu->perfCounterLayer->rawStallPerfTap;
  const uint32_t stalledInst = rawTap->io_stalledInst;
  const uint32_t stalledOpcode = stalledInst & 0x7f;
  const int addrKind = stalledOpcode == 0x03 ? 0 : (stalledOpcode == 0x23 ? 1 : 2);
  if (rawTap->io_lateLoadAddrCandidate) {
    lateLoadAddrCandidate[addrKind]++;
    lateLoadAddrPending = true;
    lateLoadAddrPendingKind = addrKind;
  }
  if (lateLoadAddrPending && rawTap->io_lateLoadAddrHit) {
    lateLoadAddrHit[lateLoadAddrPendingKind]++;
    lateLoadAddrPending = false;
  }
  if (idu->io_out_valid && idu->io_out_ready) {
    lateLoadAddrPending = false;
    const bool lateAddRs1 = idu->bypassMux->lateAddSelect;
    const bool lateAddRs2 = idu->bypassMux->lateAddSelect_1;
    if (lateAddRs1 || lateAddRs2) {
      lateAddSuccessorCount[lateAddRs1 && lateAddRs2
                                ? LateAddBothRs
                                : (lateAddRs1 ? LateAddRs1Only
                                              : LateAddRs2Only)]++;
    }
  }
}

std::vector<PerfCounterVariant> perf_counters;

void initPerfCounters() {
  PipePerfManager pipeCtr;
  RAWStallPerfCounter rawStallCtr;
  IDUFlushPerfCounter iduFlushCtr;
  BranchPredPerfCounter branchPredCtr;
  OptimizationDirectionPerfCounter optimizationDirectionCtr;
  CachePerfCounter dcacheLoadCtr;

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
  dcacheLoadCtr.bind();

  perf_counters.push_back(std::move(pipeCtr));
  perf_counters.push_back(std::move(rawStallCtr));
  perf_counters.push_back(std::move(iduFlushCtr));
  perf_counters.push_back(std::move(branchPredCtr));
  perf_counters.push_back(std::move(dcacheLoadCtr));
  perf_counters.push_back(std::move(optimizationDirectionCtr));
}

void updatePerfCounters() {
  for (auto &ctr : perf_counters) {
    std::visit([&](auto &c) { c.update(); }, ctr);
  }
}
void dumpPerfCountersStatistics(std::ostream &os, bool printFullPerf) {
  auto cycle_count = sim_get_cycle();
  auto inst_count = sim_get_inst_count();

  os << "Perf Counters Report\n";
  os << "Git commit: " << _STR(GIT_COMMIT_HASH) << "\n\n";

  os << "Statistics:\n";
  const auto instruction_limit = sim_get_config()->setting.max_instructions;
  const bool partial_run = instruction_limit != 0 && inst_count == instruction_limit && !sim_halted();
  os << "run completion:\n";
  os << "  partial run: " << (partial_run ? "yes" : "no") << "\n";
  if (instruction_limit != 0) {
    os << "  instruction limit: " << instruction_limit << "\n";
  }
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

  if (!printFullPerf) {
    for (auto &ctr : perf_counters) {
      if (auto *branchPredCtr = std::get_if<BranchPredPerfCounter>(&ctr)) {
        branchPredCtr->dumpStatistics(os);
      }
    }
    return;
  }

	os << "excution time estimate:\n";
	os << fmt::format("  {:>8} {:>10}\n", "Clk(Mhz)", "Time(s)");
	double clk_freqs[] = {50e6, 100e6, 200e6, 250e6, 280e6};
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

void to_json(nlohmann::json &j, const CachePerfCounter &c) {
  j = {
      {"ctrName", c.ctrName},
      {"definition", "EXU-issued cacheable loads; hit is the dcacheHit captured for that EXU-to-LSU payload"},
      {"accesses", c.totalVisitCount},
      {"hits", c.hitCount},
      {"misses", c.missCount()},
      {"hit_rate", c.hitRate()},
  };
}
NLOHMANN_DEFINE_TYPE_NON_INTRUSIVE(RAWStallPerfCounter, cycAnyConflict,
                                   cycAllConflictEXU, cycAllConflictLSU,
                                   cycAllConflictWBU, cycConflictEXU,
                                   cycConflictLSU, cycConflictWBU,
                                   cycConflictOnlyEXU, cycConflictOnlyLSU,
                                   cycConflictOnlyWBU, cycIDUStall,
                                   cycStallEXU, cycStallLSU, cycStallWBU,
                                   cycStallOnlyEXU, cycStallOnlyLSU,
                                   cycStallOnlyWBU)

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

void to_json(nlohmann::json &j, const OptimizationDirectionPerfCounter &c) {
  static const char *mOpNames[] = {"mul",  "mulh", "mulhsu", "mulhu",
                                   "div",  "divu", "rem",    "remu"};
  static const char *lateLoadNames[] = {"rs1_only", "rs2_only", "both"};
  static const char *lateAddNames[] = {"rs1_only", "rs2_only", "both"};
  j["ctrName"] = c.ctrName;
  for (int i = 0; i < OptimizationDirectionPerfCounter::MOpNum; i++) {
    j["m_ops"][mOpNames[i]] = c.mOpCount[i];
  }
  j["cacheable_full_word_stores"] = c.cacheableFullWordStores;
  for (int i = 0; i < OptimizationDirectionPerfCounter::LateLoadUseNum; i++) {
    j["late_load_add"][lateLoadNames[i]] = c.lateLoadAddCount[i];
  }
  for (int i = 0; i < OptimizationDirectionPerfCounter::LateAddUseNum; i++) {
    j["late_add_successor"][lateAddNames[i]] = c.lateAddSuccessorCount[i];
  }
}

void dumpPerfCounterTo(std::ostream &os) {
  // std::string title_row;
  std::string value_row;

  nlohmann::json j;

  const auto instruction_limit = sim_get_config()->setting.max_instructions;
  j["run"] = {
      {"partial", instruction_limit != 0 && sim_get_inst_count() == instruction_limit && !sim_halted()},
      {"instruction_limit", instruction_limit},
      {"instruction_count", sim_get_inst_count()},
      {"cycle_count", sim_get_cycle()},
  };

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
  dumpPerfCountersStatistics(reportFile, true);
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
