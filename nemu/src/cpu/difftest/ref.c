/***************************************************************************************
* Copyright (c) 2014-2024 Zihao Yu, Nanjing University
*
* NEMU is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

#include <isa.h>
#include <cpu/cpu.h>
#include <difftest-def.h>
#include <memory/paddr.h>

// here nemu act as ref

__EXPORT void difftest_memcpy(paddr_t addr, void *buf, size_t n, bool direction) {
	if (direction == DIFFTEST_TO_REF) {
		memcpy(guest_to_host(addr), buf, n);
	}
	else {
		memcpy(buf, guest_to_host(addr), n);
	}
}

__EXPORT void difftest_regcpy(void *dut, bool direction) {
	if (direction == DIFFTEST_TO_REF) {
		memcpy(&cpu, dut, DIFFTEST_REG_SIZE);
	}
	else {
		memcpy(dut, &cpu, DIFFTEST_REG_SIZE);
	}
}

__EXPORT void difftest_fp_regcpy(riscv_fp_state *state, bool direction) {
	_Static_assert(sizeof(state->fpr[0]) == sizeof(cpu.fpr[0]),
	               "NEMU and difftest FPR sizes must match");
	if (direction == DIFFTEST_TO_REF) {
		memcpy(cpu.fpr, state->fpr, sizeof(cpu.fpr));
		cpu.fcsr = state->fcsr & 0xff;
	} else {
		memcpy(state->fpr, cpu.fpr, sizeof(cpu.fpr));
		state->fcsr = cpu.fcsr;
	}
}

__EXPORT void difftest_exec(uint64_t n) {
	cpu_exec(n);
}

__EXPORT void difftest_raise_intr(word_t NO) {
  assert(0);
}

__EXPORT void difftest_init(int port) {
  void init_mem();
  init_mem();
  /* Perform ISA dependent initialization. */
  init_isa();
}
