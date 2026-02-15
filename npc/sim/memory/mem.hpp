#pragma once

#include <cstdint>
#include <cstddef>

#include "../sim.hpp"

uint8_t* mem_guest_to_host(uint32_t addr);
bool read_guest_mem(uint32_t addr, uint32_t *data);
bool write_guest_mem(uint32_t addr, uint32_t data);

void mem_init(void* img, const sim_config& cfg);
