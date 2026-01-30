#include <fstream>
#include <iostream>
#include <string_view>
#include <verilated.h>
#include <verilated_vpi.h>

#include <spdlog/sinks/stdout_color_sinks.h>
#include <spdlog/spdlog.h>

#include "sdbWrap.hpp"
#include "sim.hpp"

#include "PerfCounter.hpp"

int gdb_mainloop();

bool is_soc() {
  static bool isSoC = false;
  static bool inited = false;
  if (!inited) {
    auto arch = getenv("ARCH");
    if (arch && std::string(arch).find("soc") != std::string::npos) {
      isSoC = true;
    }
    inited = true;
  }
  return isSoC;
}

void test_table() {
  EXUPerfCounter exu_counter;
  for (int i = 0; i < 1000; i++) {
    exu_counter.instCountOfTyp[random() % EXUPerfCounter::TYPE_NUM]++;
    exu_counter.totalCycleOfTyp[random() % EXUPerfCounter::TYPE_NUM] +=
        random() % 5 + 1;

    exu_counter.instCountOfFmt[random() % EXUPerfCounter::FMT_NUM]++;
    exu_counter.totalCycleOfFmt[random() % EXUPerfCounter::FMT_NUM] +=
        random() % 5 + 1;
  }
  exu_counter.dumpStatistics();
}

int main(int argc, char **argv) {
  spdlog::set_default_logger(spdlog::stdout_color_mt("sim"));
  spdlog::set_level(spdlog::level::info); // will modify all registered loggers
  spdlog::set_pattern("[%H:%M:%S.%e][%^%-5l%$][%n] %v");

  if (is_soc()) {
    spdlog::info("Simulating SoC design");
    sim_get_config()->init_pc = 0x30000000;
  } else {
    spdlog::info("Simulating CPU core design");
    sim_get_config()->init_pc = 0x80000000;
  }
  spdlog::info("Sim init pc set to 0x{:08x}", sim_get_config()->init_pc);

  auto &setting = sim_get_config()->setting;
  load_sim_setting_from_env(setting);

  if (!sim_init(argc, argv, setting)) {
    get_dut()->final();
    spdlog::error("sim_init failed");
    return 1;
  }

  if (is_soc()) {
    initPerfCounters();
    spdlog::info("perf counters initialized");
  }

  if (setting.gdb_mode) {
    gdb_mainloop();
  } else {
    sdb_mainloop();
  }

  spdlog::info("sim ended");

  if (is_soc()) {
    dumpPerfCountersStatistics();
    std::string perfCtrCSV = dumpPerfCounterAsCSV();
    std::cout << "PerfCounter CSV Data:\n";
    std::cout << perfCtrCSV << std::endl;

    std::fstream csvFile;
    csvFile.open("perf_counters.csv", std::ios::out);
    if (csvFile.is_open()) {
      csvFile << perfCtrCSV;
      csvFile.close();
      spdlog::info("Perf counters CSV data written to 'perf_counters.csv'");
    } else {
      spdlog::error("Failed to write perf counters CSV data to file");
    }
  }

  get_dut()->final();
  if (!setting.gdb_mode) {
    return sim_hit_good_trap() ? 0 : 1;
  }
  return 0;
}
