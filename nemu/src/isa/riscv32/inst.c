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
#include "debug.h"
#include "local-include/reg.h"
#include "macro.h"
#include <assert.h>
#include <cpu/cpu.h>
#include <cpu/decode.h>
#include <cpu/ifetch.h>
#include <stdint.h>
#include <stdio.h>

#include <limits.h>

#include <btrace_pack.h>
#include <itrace_pack.h>

#include "memory/paddr.h"
#include "memory/vaddr.h"
#include <encoding.out.h>
#include <profile.h>

// We are in riscv32
#define signed_min INT_MIN
#define WORD_MAXBITLEN 32

#define R(i) gpr(i)

#define MATCH_COREMARK_CRCU8  0x0000000b
#define MATCH_COREMARK_CRCU16 0x0000100b
#define MATCH_COREMARK_CRCU32 0x0000200b
#define MASK_COREMARK_CRC     0xfe00707f
#define MASK_COREMARK_CRCU8   MASK_COREMARK_CRC
#define MASK_COREMARK_CRCU16  MASK_COREMARK_CRC
#define MASK_COREMARK_CRCU32  MASK_COREMARK_CRC

static word_t coremark_crc(word_t data, word_t crc, unsigned bytes) {
  crc &= 0xffffu;
  for (unsigned byte = 0; byte < bytes; byte++) {
    word_t value = data & 0xffu;
    data >>= 8;
    for (unsigned bit = 0; bit < 8; bit++) {
      word_t x16 = (value ^ crc) & 1u;
      value >>= 1;
      if (x16)
        crc ^= 0x4002u;
      crc = (crc >> 1) | (x16 << 15);
    }
  }
  return crc & 0xffffu;
}

static word_t _handle_csr_rw(word_t csr, word_t src1, bool is_write);
static word_t _csr_read(word_t csr) { return _handle_csr_rw(csr, 0, 0); }
static void _csr_write(word_t csr, word_t src1) {
  _handle_csr_rw(csr, src1, 1);
}

itrace_pack_t g_itrace_pack;
itrace_pack_t g_mtrace_pack;
btrace_pack_t g_btrace_pack;

// generate in out.cc
int execute_instruction(word_t instruction, word_t *pc, word_t *regs,
                        uint64_t *fregs, word_t *fcsr);

uint64_t g_nbranches;

static word_t g_csr_MCAUSE, g_csr_MEPC, g_csr_MTVAL;
static word_t g_csr_MVENDORID = 0x79737978;
static word_t g_csr_MARCHID = 25100261;
static word_t g_csr_MSTATUS = 0x1800;
static word_t g_csr_MSCRATCH = 0x1800;

bool riscv_fp_enabled(void) {
  return BITS(g_csr_MSTATUS, 14, 13) != 0;
}

void riscv_fp_mark_dirty(void) {
  g_csr_MSTATUS = (g_csr_MSTATUS & ~(3u << 13)) | (3u << 13);
}

word_t riscv_raise_illegal_instruction(word_t instruction, word_t pc) {
  g_csr_MEPC = pc;
  g_csr_MCAUSE = CAUSE_ILLEGAL_INSTRUCTION;
  g_csr_MTVAL = instruction;
  // TODO: update the complete mstatus trap-entry stack when privilege modes
  // are modeled.
  return isa_raise_intr(CAUSE_ILLEGAL_INSTRUCTION, pc);
}

static int decode_exec(Decode *s) {
  s->dnpc = s->snpc;

  word_t csr_addr = BITS(s->isa.inst, 31, 20); // no sext
	word_t csr_uimm = BITS(s->isa.inst, 19, 15); // no sext

  word_t inst = s->isa.inst;
  word_t profile_rs1 = 0, profile_rs2 = 0;
  if (riscv_profile_enabled()) {
    profile_rs1 = R((inst >> 15) & 31);
    profile_rs2 = R((inst >> 20) & 31);
  }

  word_t rd = (inst & INSN_FIELD_RD) >> 7;
  word_t rs1 = (inst & INSN_FIELD_RS1) >> 15;
  word_t rs2 = (inst & INSN_FIELD_RS2) >> 20;

#define IS_INST(name) (((inst & MASK_##name) == MATCH_##name) && (!matched))
#define _NOCHK_IS_INST(name) ((inst & MASK_##name) == MATCH_##name)

  bool local_csr_inst =
      _NOCHK_IS_INST(CSRRW) || _NOCHK_IS_INST(CSRRS) ||
      _NOCHK_IS_INST(CSRRC) || _NOCHK_IS_INST(CSRRWI) ||
      _NOCHK_IS_INST(CSRRSI) || _NOCHK_IS_INST(CSRRCI);
  bool local_system_inst =
      local_csr_inst || _NOCHK_IS_INST(EBREAK) || _NOCHK_IS_INST(ECALL) ||
      _NOCHK_IS_INST(MRET) || inst == 0x100f;

  word_t tmp = s->pc;
  bool matched =
      !local_system_inst &&
      (execute_instruction(inst, &tmp, cpu.gpr, &cpu.fpr[0].v[0],
                           &cpu.fcsr) == 0);
  if (matched)
    s->dnpc = tmp;

  if (inst == 0x100f) { // fence.i
    matched = true;
  }

#define MATCH_BRANCH 0b1100011
#define MASK_BRANCH 0b1111111

  if (_NOCHK_IS_INST(BRANCH) || _NOCHK_IS_INST(JALR) || _NOCHK_IS_INST(JAL)) {
    g_nbranches++;
    if (g_btrace_pack) {
      btrace_record_t record = {.pc = s->pc, .code = inst, .nxt_pc = s->dnpc};
      btrace_pack_add(g_btrace_pack, &record);
    }
  }

  bool fp_csr = csr_addr == CSR_FFLAGS || csr_addr == CSR_FRM ||
                csr_addr == CSR_FCSR;
  if (!matched && local_csr_inst && fp_csr && !riscv_fp_enabled()) {
    s->dnpc = riscv_raise_illegal_instruction(inst, s->pc);
    matched = true;
  }

  if (IS_INST(CSRRW)) {
    word_t src1 = R(rs1);
    if (rd != 0) {
      R(rd) = _csr_read(csr_addr);
    }
    _csr_write(csr_addr, src1);
    matched = true;
  }
  if (IS_INST(CSRRS)) {
    word_t old = _csr_read(csr_addr);
    word_t src1 = R(rs1);
    R(rd) = old;
    _csr_write(csr_addr, old | src1);
    matched = true;
  }
	if (IS_INST(CSRRC)) {
		word_t old = _csr_read(csr_addr);
		word_t src1 = R(rs1);
		R(rd) = old;
		_csr_write(csr_addr, old & ~src1);
		matched = true;
	}

	if(IS_INST(CSRRWI)) {
		word_t src1 = csr_uimm;
		if (rd != 0) {
			R(rd) = _csr_read(csr_addr);
		}
		_csr_write(csr_addr, src1);
		matched = true;
	}
	if(IS_INST(CSRRSI)) {
		word_t old = _csr_read(csr_addr);
		word_t src1 = csr_uimm;
		R(rd) = old;
		_csr_write(csr_addr, old | src1);
		matched = true;
	}
	if(IS_INST(CSRRCI)) {
		word_t old = _csr_read(csr_addr);
		word_t src1 = csr_uimm;
		R(rd) = old;
		_csr_write(csr_addr, old & ~src1);
		matched = true;
	}

  if (IS_INST(EBREAK)) {
    NEMUTRAP(s->pc, R(10)); // R(10) is $a0
    matched = true;
  }
  if (IS_INST(ECALL)) {
    _csr_write(CSR_MEPC, s->pc);
    _csr_write(CSR_MCAUSE, CAUSE_MACHINE_ECALL);
    s->dnpc = isa_raise_intr(0x11451419, s->pc);
    matched = true;
  }

  // xRET sets the pc to the value stored in the xepc register.
  if (IS_INST(MRET)) {
    s->dnpc = _csr_read(CSR_MEPC);
    matched = true;
  }

  if (IS_INST(COREMARK_CRCU8)) {
    R(rd) = coremark_crc(R(rs1), R(rs2), 1);
    matched = true;
  }
  if (IS_INST(COREMARK_CRCU16)) {
    R(rd) = coremark_crc(R(rs1), R(rs2), 2);
    matched = true;
  }
  if (IS_INST(COREMARK_CRCU32)) {
    R(rd) = coremark_crc(R(rs1), R(rs2), 4);
    matched = true;
  }

  if (!matched) {
    INV(s->pc);
  }

  R(0) = 0; // reset $zero to 0

  if (riscv_profile_enabled())
    riscv_profile_record(s, inst, profile_rs1, profile_rs2);

  return 0;
}

extern word_t g_csr_MTVEC;

word_t _handle_csr_rw(word_t csr, word_t src1, bool is_write) {
  // printf("csr " #csr_name " %s : old=%08X
  // new=%08X\n",is_write?"write":"read",
  // (uint32_t)old,(uint32_t)(is_write?src1:old));
#define _CASE(csr_name)                                                        \
  case CSR_##csr_name: {                                                       \
    old = g_csr_##csr_name;                                                    \
    if (is_write)                                                              \
      g_csr_##csr_name = src1;                                                 \
    return old;                                                                \
  }
  word_t old;
  if (csr == CSR_FFLAGS) {
    old = cpu.fcsr & 0x1f;
    if (is_write) {
      cpu.fcsr = (cpu.fcsr & ~0x1f) | (src1 & 0x1f);
      riscv_fp_mark_dirty();
    }
    return old;
  }
  if (csr == CSR_FRM) {
    old = (cpu.fcsr >> 5) & 0x7;
    if (is_write) {
      cpu.fcsr = (cpu.fcsr & ~0xe0) | ((src1 & 0x7) << 5);
      riscv_fp_mark_dirty();
    }
    return old;
  }
  if (csr == CSR_FCSR) {
    old = cpu.fcsr;
    if (is_write) {
      cpu.fcsr = src1 & 0xff;
      riscv_fp_mark_dirty();
    }
    return old;
  }
  switch (csr) {
    _CASE(MCAUSE);
    _CASE(MEPC);
    _CASE(MTVAL);
    _CASE(MSTATUS);
    _CASE(MTVEC);
    _CASE(MVENDORID);
    _CASE(MARCHID);
		_CASE(MSCRATCH);
  default:
    panic("unsupported csr read/write: 0x%03X", csr);
  }
}

int isa_exec_once(Decode *s) {
  s->isa.inst = inst_fetch(&s->snpc, 4);
  if (isSoC && g_itrace_pack) {
    itrace_pack_add(g_itrace_pack, s->pc);
  }
  // printf("%08x\n", s->pc);
  return decode_exec(s);
}
