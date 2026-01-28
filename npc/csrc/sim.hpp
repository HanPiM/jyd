#pragma once

#include "vsrc.hpp"
#include <cstdint>
#include <stdint.h>
#include <string_view>

TOP_NAME *get_dut();

typedef void (*cycle_end_callback_t)();

struct sim_setting {
  bool en_inst_trace = true;

  bool showdisasm = true;
  bool always_showdisasm = false;

	bool no_batch = false;

  bool ftrace = false;
  bool iringbuf = true;
  bool etrace = true;
  bool difftest = true;

	bool nvboard = false;

	bool zero_uninit_ram = false;

  bool trace_difftest_skip = false;

  bool trace_pmem_readcall = false;
  bool trace_pmem_writecall = false;
  bool trace_inst_fetchcall = false;
  bool trace_mmio_write = false;

  bool trace_clock_cycle = false;

#define TRACE_DPI_FLAG(name) trace_dpi_##name

#define _GEN_DPI_FLAG(name) bool TRACE_DPI_FLAG(name) = false;
	_GEN_DPI_FLAG(mrom_read);
	_GEN_DPI_FLAG(sdram_read);
	_GEN_DPI_FLAG(sdram_write);
	_GEN_DPI_FLAG(flash_read);

	_GEN_DPI_FLAG(psram_read);
	_GEN_DPI_FLAG(psram_write);

  cycle_end_callback_t cycle_finish_cb = nullptr;

  bool en_wave = false;

  std::string wave_fst_file = "build/wave.fst";
};

// unchange item if not set in env
void load_sim_setting_from_env(sim_setting &setting);

bool sim_init(int argc, char **argv, sim_setting teg = sim_setting{});

void sim_step_cycle();
void sim_step_inst();

uint32_t sim_current_pc();
uint32_t* sim_current_gpr();

uint8_t* sim_guest_to_host(uint32_t addr);
bool sim_read_vmem(uint32_t addr, uint32_t *data);

bool sim_halted();
bool sim_hit_good_trap();

void sim_exec_sdbcmd(std::string_view cmd, bool &quit);
