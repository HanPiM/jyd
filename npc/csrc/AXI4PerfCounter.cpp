#include "PerfCounter.hpp"
using namespace _PerfCtrImp;

void AXI4CounterBase::init_logger() {
  // assert(!name.empty());
  logger = spdlog::stdout_color_mt(ctrName);
  set_logger_pattern_with_simtime(logger);
  logger->set_level(spdlog::level::info);
}

void AXI4CounterBase::dumpStatisticsTitle(){
	fmt::println("  {:18} : {:>8} {:>10} {:>8} {:>8}",
							 "name", "txns", "cycles", "avg_lat", "max_lat");
}
void AXI4CounterBase::dumpStatistics() {
  // name : total_txns total_cycles avg_latency max_latency max_time_beg
  // max_time_end
  double avg_latency = transaction_count == 0 ? NAN
                                              : (double)total_latency_cycles /
                                                    (double)transaction_count;
  fmt::println("  {:18} : {:>8} {:>10} {:>8.2f} {:>8} (at sim time {} to {})", ctrName,
               transaction_count, total_latency_cycles, avg_latency,
               maxRecord.cycles, maxRecord.startTime, maxRecord.endTime);
}


void AXI4ReadPerfCounter::update() {
  auto sim_time = sim_get_time();
  switch (state) {
  case IDLE: {
    if (hARValid.get()){
      state = WAIT_DATA;
      transaction_count++;
      currentRecord.startTime = sim_time;
      currentRecord.cycles = 0;
      // logger->trace("ARVALID high, starting transaction {}", transaction_count);
    }
    break;
  }
  case WAIT_DATA: {
    currentRecord.cycles++;
    if (hRValid.get() == 1 && hRReady.get() == 1) {
      // handshake happened
      currentRecord.endTime = sim_time;
      total_latency_cycles += currentRecord.cycles;
      if (currentRecord.cycles > maxRecord.cycles) {
        maxRecord = currentRecord;
      }
      // logger->trace(
      //     "RVALID & RREADY handshake for transaction {} after {} cycles",
      //     transaction_count, currentRecord.cycles);
      state = IDLE;
    }
    break;
  }
  }
}
void AXI4WritePerfCounter::update() {
  auto sim_time = sim_get_time();
  switch (state) {
  case IDLE: {
    if (hAWValid.get() == 1) {
      state = WAIT_RESP;
      transaction_count++;
      currentRecord.startTime = sim_time;
      currentRecord.cycles = 0;
      // logger->trace("AWVALID high, starting transaction {}", transaction_count);
    }
    break;
  }
  case WAIT_RESP: {
    currentRecord.cycles++;
    if (hBValid.get() == 1 && hBReady.get() == 1) {
      // handshake happened
      currentRecord.endTime = sim_time;
      total_latency_cycles += currentRecord.cycles;
      if (currentRecord.cycles > maxRecord.cycles) {
        maxRecord = currentRecord;
      }
      // logger->trace(
      //     "BVALID & BREADY handshake for transaction {} after {} cycles",
      //     transaction_count, currentRecord.cycles);
      state = IDLE;
    }
    break;
  }
  }
}

void AXI4PerfCounterManager::update() {
  for (auto &ctr : rdCounters) {
    ctr.update();
  }
  for (auto &ctr : wrCounters) {
    ctr.update();
  }
}


void AXI4PerfCounterManager::dumpStatistics() {
  spdlog::info("AXI4 Performance Counters Statistics:");
  fmt::println(">AXI4 Read Counters:");
	AXI4CounterBase::dumpStatisticsTitle();
  for (auto &ctr : rdCounters) {
    ctr.dumpStatistics();
  }
  fmt::println(">AXI4 Write Counters:");
	AXI4CounterBase::dumpStatisticsTitle();
  for (auto &ctr : wrCounters) {
    ctr.dumpStatistics();
  }
}
