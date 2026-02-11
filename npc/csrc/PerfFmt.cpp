#include "PerfCounter.hpp"
#include "spdlog/fmt/bundled/format.h"
#include "spdlog/spdlog.h"
#include <tabulate/table.hpp>

using namespace _PerfCtrImp;
using namespace tabulate;

static void _SetTableFmt(Table &t) {
  assert(t.size() >= 2);
  t.format().font_align(FontAlign::right);
  t.row(0).format().font_align(FontAlign::center);

  for (auto &row : t) {
    row.format().hide_border_top();
  }

  t[0].format().show_border_top();
  t[1].format().show_border_top();
}
void _PrintTable(Table &t, std::ostream &os) {
  _SetTableFmt(t);
  os << t << std::endl;
}

void HandShakeCounterManager::dumpStatistics(std::ostream &os) {
  os << "handshake counts:\n";
  Table t;
  t.add_row({"Description", "Handshake Count", "Frequency"});
  for (auto &bus : bus_list) {
    double freq = sim_get_cycle() == 0
                      ? NAN
                      : ((double)bus.shake_count / (double)sim_get_cycle());
    t.add_row(RowStream{} << bus.description << bus.shake_count << freq);
  }
  _PrintTable(t, os);
}

void EXUPerfCounter::_dump(size_t *instCnts, size_t *cycCnts, size_t num,
                           const char *(*nameFunc)(int), std::ostream &os) {
  Table t;
  t.add_row({"Category", "Inst Count", "Inst %", "Cycle Count", "Cycle %",
             "Avg CPI"});

  size_t totalInsts = std::accumulate(instCnts, instCnts + num, 0ull);
  size_t totalCyc = std::accumulate(cycCnts, cycCnts + num, 0ull);

  for (size_t i = 0; i < num; i++) {
    auto name = nameFunc((int)i);
    auto instCount = instCnts[i];
    auto cycleCount = cycCnts[i];

    double instPerc = totalInsts == 0
                          ? NAN
                          : ((double)instCount / (double)totalInsts) * 100.0;
    double cyclePerc =
        totalCyc == 0 ? NAN : ((double)cycleCount / (double)totalCyc) * 100.0;

    double avgCPI =
        instCount == 0 ? NAN : (double)cycleCount / (double)instCount;

    t.add_row(RowStream{} << name << instCount << instPerc << cycleCount
                          << cyclePerc << avgCPI);
  }
  _PrintTable(t, os);
}

const char *PipeStagePerfCounter::nameOfState(int s) {
  const char *names[] = {
      "Backpressure",
      "Bubble",
      "Fire",
  };
  return names[s];
}
void PipeStagePerfCounter::dumpStatistics(std::ostream &os) {
  spdlog::error("PipeStagePerfCounter::dumpStatistics unimpled!!!");
  // os << "IFU State Counter Statistics:\n";
  // os << "total instruction fetch count: " << totalFetchCount << "\n";
  //
  // os << "state statistics:\n";
  // Table t;
  // t.add_row({"State", "Count", "Percent"});
  //
  // auto totStateCount =
  //     std::accumulate(countOfState, countOfState + STATE_NUM, 0ull);
  //
  // auto totCycles = sim_get_cycle();
  // for (size_t i = 0; i < STATE_NUM; i++) {
  //   double perc =
  //       totStateCount == 0
  //           ? NAN
  //           : ((double)countOfState[i] / (double)totStateCount) * 100.0;
  //   t.add_row(RowStream{} << nameOfState((int)i) << countOfState[i] << perc);
  // }
  // _PrintTable(t, os);
}
void PipePerfManager::dumpStatistics(std::ostream &os) {
  os << "Pipeline Performance Counter Statistics:\n";
  Table t;
  t.add_row({"Stage", "Backpressure\nCycles", "Bubble\nCycles", "Fire\nCycles",
             "Backpressure\n%", "Bubble\n%", "Fire\n%"});

  for (auto &ctr : stageCtrs) {
    size_t totCycles = ctr.totalCount();
    double backPerc =
        totCycles == 0
            ? NAN
            : ((double)ctr.countOfState[0] / (double)totCycles) * 100.0;
    double bubPerc =
        totCycles == 0
            ? NAN
            : ((double)ctr.countOfState[1] / (double)totCycles) * 100.0;
    double firePerc =
        totCycles == 0
            ? NAN
            : ((double)ctr.countOfState[2] / (double)totCycles) * 100.0;

    t.add_row(
        RowStream{} << ctr.ctrName
                    << ctr.countOfState[PipeStagePerfCounter::Backpressure]
                    << ctr.countOfState[PipeStagePerfCounter::Bubble]
                    << ctr.countOfState[PipeStagePerfCounter::Fire] << backPerc
                    << bubPerc << firePerc);
  }

  _PrintTable(t, os);
}

void AXI4CounterBase::dumpStatistics(std::ostream &os) {
  spdlog::error("AXI4CounterBase::dumpStatistics unimpled!!!");
}

static void _AddTableRowForAXI4Counter(Table &t, AXI4CounterBase &ctr) {
  double avg_latency =
      ctr.transaction_count == 0
          ? NAN
          : (double)ctr.total_latency_cycles / (double)ctr.transaction_count;
  t.add_row(RowStream{} << ctr.ctrName << ctr.transaction_count
                        << ctr.total_latency_cycles << avg_latency
                        << ctr.maxRecord.cycles << ctr.maxRecord.startTime);
}
void AXI4PerfCounterManager::dumpStatistics(std::ostream &os) {
  os << "AXI4 Performance Counters Statistics:\n";

  Table t;
  t.add_row({"Name", "Transactions", "Total\nCycles", "Avg\nLatency",
             "Max\nLatency", "Max Start\n(sim time)"});
  for (AXI4CounterBase &ctr : rdCounters) {
    _AddTableRowForAXI4Counter(t, ctr);
  }
  for (AXI4CounterBase &ctr : wrCounters) {
    _AddTableRowForAXI4Counter(t, ctr);
  }
  _PrintTable(t, os);
}
