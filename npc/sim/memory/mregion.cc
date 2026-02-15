#include "mregion.hpp"
#include "../sim.hpp"
#include <cstdint>
#include <spdlog/spdlog.h>

void init_mem_logger() {
  auto lvl = spdlog::level::info;

#define _REG_MEM_REGION_LOGGER(name)                                           \
  do {                                                                         \
    spdlog::debug("Registering logger for mem region '{}'", #name);            \
    auto logger =                                                              \
        std::make_shared<spdlog::logger>(#name, spdlog::sinks_init_list{});    \
    logger->set_level(lvl);                                                    \
    set_logger_pattern_with_simtime(logger);                                   \
    spdlog::register_logger(logger);                                           \
  } while (0)
  _REG_MEM_REGION_LOGGER(mrom);
  _REG_MEM_REGION_LOGGER(flash);
  _REG_MEM_REGION_LOGGER(psram);
  _REG_MEM_REGION_LOGGER(sram);
  _REG_MEM_REGION_LOGGER(sdram);
}

void mem_region_traits::assert_in_range(uint32_t addr) const {
  bool in_range = contains(addr);
  if (!in_range) {
    auto logger = spdlog::get(name.data());
    if (logger) {
      logger->error("addr {:08x} out of bound for region {} [{:08x}, {:08x})",
                    addr, name, _Base, _End);
    }
  }
  assert(in_range);
}

void direct_mapped_mem::assert_in_actual_data_range(uint32_t addr) const {
  size_t offset = addr - _Base;
  if (offset >= _ActualSizeInBytes) {
    auto logger = spdlog::get(this->name.data());
    if (logger) {
      logger->error("addr {:08x} out of actual data bound for region {} "
                    "[0, {:08x})",
                    addr, this->name, _ActualSizeInBytes);
    }
  }
  assert(offset < _ActualSizeInBytes);
}

sdram_mem::u32_data_ptr sdram_mem::get_data_at(uint32_t addr) {
  uint32_t in_sdram_addr = addr - _Base;
  char raw_bank = (in_sdram_addr >> 10) & 0x7;
  uint16_t row = (in_sdram_addr >> 13) & 0x1fff;
  uint16_t col = (in_sdram_addr >> 1) & 0x1ff;
  uint8_t bank = raw_bank % 4;
  uint8_t block = (raw_bank & 0x4) ? 2 : 0;
  assert(bank < N_BANKS);
  assert(row < N_ROWS);
  assert(col < N_COLS);
  assert(block < N_BLOCKS);
  return {.lowpart = &data_at(bank, row, col, block),
          .highpart = &data_at(bank, row, col, block + 1)};
}
uint8_t *sdram_mem::get_data_ptr_at(uint32_t addr) {
  assert_in_range(addr);
  spdlog::error(
      "unimpled!!! sdram region does not support direct data pointer access");
  return nullptr;
}
