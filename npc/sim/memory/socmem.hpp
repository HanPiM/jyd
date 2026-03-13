#pragma once

#include "../sim.hpp"
#include "mregion.hpp"

#define DECLARE_SOC_MEM(name) \
	mem_region_group_t& get_mem_regions_of_##name(); \
	void init_mem_of_##name(void* img, const sim_config& cfg);

DECLARE_SOC_MEM(ysyxsoc);
DECLARE_SOC_MEM(npc);

