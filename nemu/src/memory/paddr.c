/***************************************************************************************
 * Copyright (c) 2014-2024 Zihao Yu, Nanjing University
 *
 * NEMU is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan
 *PSL v2. You may obtain a copy of Mulan PSL v2 at:
 *          http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY
 *KIND, EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
 *NON-INFRINGEMENT, MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 *
 * See the Mulan PSL v2 for more details.
 ***************************************************************************************/

#include "common.h"
#include <device/mmio.h>
#include <isa.h>
#include <memory/host.h>
#include <memory/paddr.h>
#include <stdint.h>
#include <stdio.h>

#if defined(CONFIG_PMEM_MALLOC)
static uint8_t *pmem = NULL;
#else // CONFIG_PMEM_GARRAY
static uint8_t pmem[CONFIG_MSIZE] PG_ALIGN = {};
#endif

#define SRAM_BASE 0x0f000000u
#define MROM_BASE 0x20000000u

static uint8_t mrom[0x1000] PG_ALIGN; // 4KB
static uint8_t sram[0x2000] PG_ALIGN; // 8KB

static bool in_mrom(paddr_t addr) { return addr - MROM_BASE < sizeof(mrom); }
static bool in_sram(paddr_t addr) { return addr - SRAM_BASE < sizeof(sram); }

uint8_t *guest_to_host(paddr_t paddr) {
  if (likely(in_pmem(paddr))) {
    return pmem + paddr - CONFIG_MBASE;
  } else if (in_mrom(paddr)) {
    return mrom + paddr - MROM_BASE;
  } else if (in_sram(paddr)) {
    return sram + paddr - SRAM_BASE;
  }
  return NULL;
}
paddr_t host_to_guest(uint8_t *haddr) {
  if (likely(haddr >= pmem && haddr < pmem + CONFIG_MSIZE)) {
    return haddr - pmem + CONFIG_MBASE;
  } else if (haddr >= mrom && haddr < mrom + sizeof(mrom)) {
		return haddr - mrom + MROM_BASE;
	} else if (haddr >= sram && haddr < sram + sizeof(sram)) {
		return haddr - sram + SRAM_BASE;
	}
	return 0;
}

static bool builtin_read(paddr_t addr, int len, word_t *data) {
  uint8_t *haddr = guest_to_host(addr);
  if (haddr == NULL) {
    return false;
  }
  *data = host_read(haddr, len);
  return true;
}

static bool builtin_write(paddr_t addr, int len, word_t data) {
	if(in_mrom(addr)) {
		panic("can not write ROM address = " FMT_PADDR " at pc = " FMT_WORD, addr, cpu.pc);
		return false;
	}
	uint8_t *haddr = guest_to_host(addr);
	if (haddr == NULL) {
		return false;
	}
	host_write(haddr, len, data);
	return true;
}

static void out_of_bound(paddr_t addr) {
  panic("address = " FMT_PADDR " is out of bound of pmem [" FMT_PADDR
        ", " FMT_PADDR "] at pc = " FMT_WORD,
        addr, PMEM_LEFT, PMEM_RIGHT, cpu.pc);
}

void init_mem() {
#if defined(CONFIG_PMEM_MALLOC)
  pmem = malloc(CONFIG_MSIZE);
  assert(pmem);
#endif
  IFDEF(CONFIG_MEM_RANDOM, memset(pmem, rand(), CONFIG_MSIZE));
  Log("physical memory area [" FMT_PADDR ", " FMT_PADDR "]", PMEM_LEFT,
      PMEM_RIGHT);
}

#define is_addr_inmtrace(p)                                                    \
  (((p) >= CONFIG_MTRACE_BEG) && ((p) < CONFIG_MTRACE_END))
#define mtrace(p, expr)                                                        \
  IFDEF(CONFIG_MTRACE, if (is_addr_inmtrace(addr)) { expr; });

word_t paddr_read(paddr_t addr, int len) {
  mtrace(addr, printf("mem r %08X %db\n", addr, len));
  word_t data;
  if (builtin_read(addr, len, &data)) {
    return data;
  }

  IFDEF(CONFIG_DEVICE, return mmio_read(addr, len));
  out_of_bound(addr);
  return 0;
}

void paddr_write(paddr_t addr, int len, word_t data) {
  mtrace(addr, printf("mem w %08X %db %08X\n", addr, len, data));
	if (builtin_write(addr, len, data)) {
		return;
	}
  IFDEF(CONFIG_DEVICE, mmio_write(addr, len, data); return);
  out_of_bound(addr);
}
