#pragma once
#include "sim.hpp"
void dbg_init(uint32_t init_pc, size_t img_size, const char* img_file_path, sim_setting setting);
bool dbg_is_hitbadtrap();
void dbg_set_halt(int a0);
void dbg_dump_recent_info();

void dbg_skip_difftest_ref();

void dbg_exec(std::string_view cmd, bool *quit=nullptr);
