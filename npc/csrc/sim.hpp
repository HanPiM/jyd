#pragma once

#include <VTop.h>
#include <string_view>

VTop* get_dut();

typedef void(*cycle_end_callback_t)();

struct sim_setting{
	bool showdisasm=true;
	bool always_show_disasm=false;

	bool ftrace=false;
	bool iringbuf=true;
	bool etrace=true;
	bool difftest=true;

	bool nvboard=false;

	bool trace_pmem_readcall=false;
	bool trace_pmem_writecall=false;
	bool trace_inst_fetchcall=false;
	bool trace_mmio_write=false;

	bool trace_clock_cycle=false;

	cycle_end_callback_t cycle_finish_cb=nullptr;
};

bool sim_init(int argc, char** argv,sim_setting teg=sim_setting{});

bool sim_halted();
bool sim_hit_good_trap();

void sim_exec_sdbcmd(std::string_view cmd,bool& quit);
