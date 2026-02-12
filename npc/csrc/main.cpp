#include <fstream>
#include <iostream>
#include <string_view>
#include <verilated.h>
#include <verilated_vpi.h>

#include <spdlog/spdlog.h>
#include <spdlog/sinks/stdout_color_sinks.h>
#include <spdlog/sinks/basic_file_sink.h>

#include "sdbWrap.hpp"
#include "sim.hpp"

#include "PerfCounter.hpp"
#include "spdlog/common.h"

int gdb_mainloop();

int main(int argc, char **argv) {
	auto console_sink = std::make_shared<spdlog::sinks::stdout_color_sink_mt>();
	console_sink->set_level(spdlog::level::info);
	auto file_sink = std::make_shared<spdlog::sinks::basic_file_sink_mt>("sim.log", true);
	file_sink->set_level(spdlog::level::debug);
	auto sinks = spdlog::sinks_init_list{console_sink, file_sink};
	auto logger = std::make_shared<spdlog::logger>("sim", sinks);
	logger->set_level(spdlog::level::debug);

  spdlog::set_default_logger(logger);
  // spdlog::set_level(spdlog::level::debug); // will modify all registered loggers
  spdlog::set_pattern("[%H:%M:%S.%e][%^%-5l%$][%n] %v");

  if (is_soc()) {
    spdlog::info("Simulating SoC design");
    sim_get_config()->init_pc = 0x30000000;
  } else {
    spdlog::info("Simulating CPU core design");
    sim_get_config()->init_pc = 0x80000000;
  }
	spdlog::info("Git commit hash: {}", _STR(GIT_COMMIT_HASH));
  spdlog::info("Sim init pc set to 0x{:08x}", sim_get_config()->init_pc);

  auto &setting = sim_get_config()->setting;
  load_sim_setting_from_env(setting);

  if (!sim_init(argc, argv, setting)) {
    get_dut()->final();
    spdlog::error("sim_init failed");
    return 1;
  }

  if (true||is_soc()) {
    initPerfCounters();
    spdlog::info("perf counters initialized");
  }

  if (setting.gdb_mode) {
    gdb_mainloop();
  } else {
    sdb_mainloop();
  }

  spdlog::info("sim ended");

  if (true||is_soc()) {
    dumpPerfCountersStatistics(std::cout);
		dumpPerfReportOnDir(".");
  }

  get_dut()->final();
  if (!setting.gdb_mode) {
    return sim_hit_good_trap() ? 0 : 1;
  }
  return 0;
}
