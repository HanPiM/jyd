#include <iostream>
#include <string_view>
#include <verilated.h>
#include <verilated_vpi.h>

#include <spdlog/spdlog.h>
#include <spdlog/sinks/stdout_color_sinks.h>

#include "sim.hpp"
#include "dbg.hpp"

int gdb_mainloop();

int main(int argc, char **argv) {
	spdlog::set_default_logger(spdlog::stdout_color_mt("sim"));
  spdlog::set_level(spdlog::level::trace); // will modify all registered loggers
	spdlog::set_pattern("[%H:%M:%S.%e][%^%-5l%$][%n] %v");

	auto& setting = sim_get_config()->setting;
	load_sim_setting_from_env(setting);

  if(!sim_init(argc, argv, setting)){
		get_dut()->final();
		spdlog::error("sim_init failed");
		return 1;
	}

	if(setting.gdb_mode){
		gdb_mainloop();
	}else{
		sdb_mainloop();
	}

	spdlog::info("sim ended");

	sim_dump_statistics();
	
	get_dut()->final();
	if(!setting.gdb_mode){
		return sim_hit_good_trap() ? 0 : 1;
	}
	return 0;
}
