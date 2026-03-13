#include "mem.hpp"
#include <cstdint>

static std::shared_ptr<direct_mapped_mem> _pmem_ptr;

mem_region_group_t &get_mem_regions_of_npc() {
  static mem_region_group_t mem_regions;
  if (mem_regions.empty()) {
    if (_pmem_ptr) {
      mem_regions.push_back(*_pmem_ptr);
    } else {
      spdlog::warn("try to get mem regions of npc before init, returning empty "
                   "region list");
    }
  }
  return mem_regions;
}

// compatible interface for npc core
extern "C" void pmem_upd(int addr, int data, int mask) {

	// currently no need? 
	// directly bind to the mem instance in verilator

  // uint32_t udata = data;
  // uint8_t umask = mask & 0xf;
  //
  // auto &pmem = *_pmem_ptr;
  // // uint32_t psram_addr = addr - pmem.base();
  // // pmem.write_word(addr, udata, umask);
  // uint32_t newdata;
  // pmem.read_word(addr, newdata);
  //
  // spdlog::info(
  //     "pmem write addr={:08x} data={:08x} (strb {:02x}) newdata={:08x}",
  //     (uint32_t)addr, (uint32_t)data, (uint32_t)umask, newdata);

  // return psram_write(psram_addr, umask, udata, nullptr);
}

#if !SIM_SOC
void init_mem_of_npc(void *img, const sim_config &cfg) {
  // spdlog::info("copy img to psram for sdb read");
  // g_sim_mem.psram.copy_from(img, img_size);
  spdlog::info("copy img to pmem for cpu core sim read");

  _pmem_ptr = std::make_shared<direct_mapped_mem>(
      0x80000000u, 0xa0000000u, "pmem", 128 * 1024 * 1024,
      get_dut()
          ->NPCTestSoC->vlSymsp->TOP__NPCTestSoC__npcDevices__mem__mem__mem_ext
          .Memory.data());
  _pmem_ptr->copy_from(img, cfg.img_size);

  // auto pmemDataPtr = memcpy(pmemDataPtr, img, cfg.img_size);

  //   // memset the ASAN shadow memory to zero
  //   // instead at trm_init to optimise the init time
  // #define ASAN_SHADOW_MEMORY_START 0x7000000
  // #define ASAN_SHADOW_MEMORY_SIZE 0x1000000
  //
  //   auto psram_data = pmemDataPtr;
  //   memset((uint8_t *)psram_data + ASAN_SHADOW_MEMORY_START, 0,
  //          ASAN_SHADOW_MEMORY_SIZE);
  //   spdlog::info("ASAN shadow memory in psram zeroed");
}
#endif
