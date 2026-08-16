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

#define MATCH_XACCEL_CRCU8  0x0000000b
#define MATCH_XACCEL_CRCU16 0x0000100b
#define MATCH_XACCEL_CRCU32 0x0000200b
#define MASK_XACCEL_CRC     0xfe00707f
#define MASK_XACCEL_CRCU8   MASK_XACCEL_CRC
#define MASK_XACCEL_CRCU16  MASK_XACCEL_CRC
#define MASK_XACCEL_CRCU32  MASK_XACCEL_CRC

#define MATCH_XACCEL_XDUP8LO 0x0200100b
#define MASK_XACCEL_XDUP8LO  0xfff0707f

#define MATCH_XACCEL_XMAC16 0x0000300b
#define MATCH_XACCEL_XMACACC_FIRST 0x0800300b
#define MATCH_XACCEL_XMACACC_ADD 0x0a00300b
#define MATCH_XACCEL_XMACACC_BIT_FIRST 0x0c00300b
#define MATCH_XACCEL_XMACACC_BIT_ADD 0x0e00300b
#define MATCH_XACCEL_XMACACC_LAST 0x1000300b
#define MATCH_XACCEL_XMACACC_BIT_LAST 0x1200300b
#define MATCH_XACCEL_XDOT16 0x0000400b
#define MATCH_XACCEL_XDOTN_CONFIG 0x0600400b
#define MATCH_XACCEL_XDOTN 0x0800400b
#define MATCH_XACCEL_XDOTN_BIT 0x0a00400b
#define MATCH_XACCEL_XBMUL  0x0000500b
#define MATCH_XACCEL_XMBM   0x0200500b
#define MATCH_XACCEL_XLISTREV_INIT 0x0000600b
#define MATCH_XACCEL_XLISTFIND_IDX 0x0200600b
#define MATCH_XACCEL_XLISTREV_LOOP 0x0400600b
#define MATCH_XACCEL_XLISTFIND_DATA 0x0600600b
#define MATCH_XACCEL_XMSUM  0x0400700b
#define MASK_XACCEL_XACCEL  0xfe00707f
#define MASK_XACCEL_XMAC16 MASK_XACCEL_XACCEL
#define MASK_XACCEL_XMACACC_FIRST MASK_XACCEL_XACCEL
#define MASK_XACCEL_XMACACC_ADD MASK_XACCEL_XACCEL
#define MASK_XACCEL_XMACACC_BIT_FIRST MASK_XACCEL_XACCEL
#define MASK_XACCEL_XMACACC_BIT_ADD MASK_XACCEL_XACCEL
#define MASK_XACCEL_XMACACC_LAST MASK_XACCEL_XACCEL
#define MASK_XACCEL_XMACACC_BIT_LAST MASK_XACCEL_XACCEL
#define MASK_XACCEL_XDOT16 MASK_XACCEL_XACCEL
#define MASK_XACCEL_XDOTN_CONFIG MASK_XACCEL_XACCEL
#define MASK_XACCEL_XDOTN MASK_XACCEL_XACCEL
#define MASK_XACCEL_XDOTN_BIT MASK_XACCEL_XACCEL
#define MASK_XACCEL_XBMUL  MASK_XACCEL_XACCEL
#define MASK_XACCEL_XMBM   MASK_XACCEL_XACCEL
#define MASK_XACCEL_XLISTREV_INIT MASK_XACCEL_XACCEL
#define MASK_XACCEL_XLISTFIND_IDX MASK_XACCEL_XACCEL
#define MASK_XACCEL_XLISTREV_LOOP MASK_XACCEL_XACCEL
#define MASK_XACCEL_XLISTFIND_DATA MASK_XACCEL_XACCEL
#define MASK_XACCEL_XMSUM  MASK_XACCEL_XACCEL

static vaddr_t list_reverse_previous;
static word_t matrix_accumulator;

#define MATCH_XACCEL_XDFACNT_INIT 0x0000005b
#define MATCH_XACCEL_XDFACNT_INC  0x0000105b
#define MATCH_XACCEL_XDFACNT_READ 0x0000205b
#define MATCH_XACCEL_XDFACNT_COMMIT 0x0000305b
#define MATCH_XACCEL_XDFA2_STEP 0x0000405b
#define MATCH_XACCEL_XDFA4_STEP 0x0000505b
#define MATCH_XACCEL_XDFA4H_FINAL_READ 0x0200205b
#define MATCH_XACCEL_XDFA4H_STEP 0x0200505b
#define MATCH_XACCEL_XDFA4H_STEP_PTR 0x0400505b
#define MASK_XACCEL_XDFACNT       0xfe00707f
#define MASK_XACCEL_XDFACNT_INIT  MASK_XACCEL_XDFACNT
#define MASK_XACCEL_XDFACNT_INC   MASK_XACCEL_XDFACNT
#define MASK_XACCEL_XDFACNT_READ  MASK_XACCEL_XDFACNT
#define MASK_XACCEL_XDFACNT_COMMIT MASK_XACCEL_XDFACNT
#define MASK_XACCEL_XDFA2_STEP MASK_XACCEL_XDFACNT
#define MASK_XACCEL_XDFA4_STEP MASK_XACCEL_XDFACNT
#define MASK_XACCEL_XDFA4H_FINAL_READ MASK_XACCEL_XDFACNT
#define MASK_XACCEL_XDFA4H_STEP MASK_XACCEL_XDFACNT
#define MASK_XACCEL_XDFA4H_STEP_PTR MASK_XACCEL_XDFACNT

enum { XA_MAC16, XA_DOT16, XA_DOTN, XA_BMUL, XA_LISTREV, XA_LISTFIND, XA_MSUM };

static uint32_t numeric_dfa_transition_counts[8];
static uint32_t numeric_dfa_final_counts[8];
static uint32_t numeric_dfa_pending_mask;
static uint32_t numeric_dfa_internal_state;
static uint32_t numeric_dfa_internal_stopped = 1;

static word_t numeric_dfa_word_step(word_t state, word_t symbols,
                                        unsigned width, bool format2) {
  word_t consumed = 0, mask = 0, stop = 0;
  for (unsigned i = 0; i < width && !stop; i++) {
    uint8_t c = symbols >> (8 * i);
    if (c == 0) {
      stop = 1;
      break;
    }
    consumed++;
    if (c == ',') {
      stop = 1;
      break;
    }
    bool digit = (uint8_t)(c - '0') <= 9;
    switch (state) {
    case 0:
      if (digit) state = 4;
      else if (c == '+' || c == '-') state = 2;
      else if (c == '.') state = 5;
      else { state = 1; mask |= 1u << 1; }
      mask |= 1u << 0;
      break;
    case 2:
      if (digit) state = 4;
      else if (c == '.') state = 5;
      else state = 1;
      mask |= 1u << 2;
      break;
    case 4:
      if (c == '.') { state = 5; mask |= 1u << 4; }
      else if (!digit) { state = 1; mask |= 1u << 4; }
      break;
    case 5:
      if (c == 'E' || c == 'e') { state = 3; mask |= 1u << 5; }
      else if (!digit) { state = 1; mask |= 1u << 5; }
      break;
    case 3:
      state = (c == '+' || c == '-') ? 6 : 1;
      mask |= 1u << 3;
      break;
    case 6:
      state = digit ? 7 : 1;
      mask |= 1u << 6;
      break;
    case 7:
      if (!digit) { state = 1; mask |= 1u << 1; }
      break;
    default:
      stop = 1;
      break;
    }
    if (state == 1)
      stop = 1;
  }
  if (format2)
    return state | (consumed << 3) | (stop << 5) | (mask << 6);
  return state | (consumed << 3) | (stop << 6) | (mask << 7);
}

static word_t crc_update(word_t data, word_t crc, unsigned bytes) {
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

static inline int32_t sx16(word_t value) { return (int16_t)(uint16_t)value; }

static word_t matrix_clipped_sum(vaddr_t data, word_t config,
                                  uint64_t *elements) {
  uint32_t n = config >> 16;
  int32_t clip = (int16_t)(config & 0xffffu);
  int32_t tmp = 0, prev = 0, cur = 0;
  int16_t ret = 0;
  *elements = (uint64_t)n * n;
  for (uint64_t i = 0; i < *elements; i++) {
    cur = (int32_t)vaddr_read(data + i * 4, 4);
    tmp += cur;
    if (tmp > clip) {
      ret = (int16_t)(ret + 10);
      tmp = 0;
    } else {
      ret = (int16_t)(ret + (cur > prev));
    }
    prev = cur;
  }
  return (word_t)(int32_t)ret;
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

  if (IS_INST(XACCEL_CRCU8)) {
    R(rd) = crc_update(R(rs1), R(rs2), 1);
    matched = true;
  }
  if (IS_INST(XACCEL_CRCU16)) {
    R(rd) = crc_update(R(rs1), R(rs2), 2);
    matched = true;
  }
  if (IS_INST(XACCEL_CRCU32)) {
    R(rd) = crc_update(R(rs1), R(rs2), 4);
    matched = true;
  }
  if (IS_INST(XACCEL_XDUP8LO)) {
    word_t byte = (R(rs1) >> 8) & 0xffu;
    R(rd) = (R(rs1) & ~0xffu) | byte;
    matched = true;
  }
  if (IS_INST(XACCEL_XMAC16)) {
    R(rd) = R(rd) + (word_t)(sx16(R(rs1)) * sx16(R(rs2)));
    riscv_profile_record_xaccel(XA_MAC16, 1, 3);
    matched = true;
  }
  if (IS_INST(XACCEL_XMACACC_FIRST)) {
    matrix_accumulator = (word_t)(sx16(R(rs1)) * sx16(R(rs2)));
    riscv_profile_record_xaccel(XA_MAC16, 1, 1);
    matched = true;
  }
  if (IS_INST(XACCEL_XMACACC_ADD)) {
    matrix_accumulator += (word_t)(sx16(R(rs1)) * sx16(R(rs2)));
    riscv_profile_record_xaccel(XA_MAC16, 1, 1);
    matched = true;
  }
  if (IS_INST(XACCEL_XMACACC_BIT_FIRST) || IS_INST(XACCEL_XMACACC_BIT_ADD) ||
      IS_INST(XACCEL_XMACACC_BIT_LAST)) {
    word_t value = (R(rs1) & 0xffffu) * (R(rs2) & 0xffffu);
    word_t term = ((value >> 2) & 0xfu) * ((value >> 5) & 0x7fu);
    matrix_accumulator = IS_INST(XACCEL_XMACACC_BIT_FIRST) ? term : matrix_accumulator + term;
    riscv_profile_record_xaccel(XA_MAC16, 1, 1);
    if (IS_INST(XACCEL_XMACACC_BIT_LAST))
      R(rd) = matrix_accumulator;
    matched = true;
  }
  if (IS_INST(XACCEL_XMACACC_LAST)) {
    matrix_accumulator += (word_t)(sx16(R(rs1)) * sx16(R(rs2)));
    R(rd) = matrix_accumulator;
    riscv_profile_record_xaccel(XA_MAC16, 1, 1);
    matched = true;
  }
  if (IS_INST(XACCEL_XDOT16)) {
    int32_t lo = sx16(R(rs1)) * sx16(R(rs2));
    int32_t hi = (int16_t)(R(rs1) >> 16) * (int16_t)(R(rs2) >> 16);
    R(rd) = (word_t)(lo + hi);
    riscv_profile_record_xaccel(XA_DOT16, 2, 4);
    matched = true;
  }
  static unsigned xdotn_length;
  if (IS_INST(XACCEL_XDOTN_CONFIG)) {
    xdotn_length = R(rs1) & 0xffffu;
    matched = true;
  }
  if (IS_INST(XACCEL_XDOTN) || IS_INST(XACCEL_XDOTN_BIT)) {
    vaddr_t address_a = R(rs1);
    vaddr_t address_b = R(rs2);
    word_t sum = 0;
    unsigned length = xdotn_length;
    bool bit_extract = IS_INST(XACCEL_XDOTN_BIT);
    for (unsigned k = 0; k < length; k++) {
      word_t a = vaddr_read(address_a + 2 * k, 2) & 0xffffu;
      word_t b = vaddr_read(address_b + 2 * length * k, 2) & 0xffffu;
      word_t product = a * b;
      word_t term = bit_extract
                        ? ((product >> 2) & 0xfu) * ((product >> 5) & 0x7fu)
                        : (word_t)(sx16(a) * sx16(b));
      sum += term;
    }
    R(rd) = sum;
    riscv_profile_record_xaccel(XA_DOTN, length, 3 * length);
    matched = true;
  }
  if (IS_INST(XACCEL_XBMUL)) {
    word_t value = R(rs1);
    R(rd) = ((value >> 2) & 0xfu) * ((value >> 5) & 0x7fu);
    riscv_profile_record_xaccel(XA_BMUL, 1, 1);
    matched = true;
  }
  if (IS_INST(XACCEL_XMBM)) {
    word_t value = (R(rs1) & 0xffffu) * (R(rs2) & 0xffffu);
    R(rd) = ((value >> 2) & 0xfu) * ((value >> 5) & 0x7fu);
    riscv_profile_record_xaccel(XA_BMUL, 1, 1);
    matched = true;
  }
  if (IS_INST(XACCEL_XLISTREV_INIT)) {
    vaddr_t current = R(rs1);
    R(rd) = vaddr_read(current, 4);
    vaddr_write(current, 4, 0);
    list_reverse_previous = current;
    riscv_profile_record_xaccel(XA_LISTREV, 1, 4);
    matched = true;
  }
  if (IS_INST(XACCEL_XLISTFIND_IDX) || IS_INST(XACCEL_XLISTFIND_DATA)) {
    vaddr_t current = R(rs1);
    word_t target = R(rs2) & 0xffffu;
    uint64_t nodes = 0;
    bool find_data = (inst & MASK_XACCEL_XLISTFIND_DATA) == MATCH_XACCEL_XLISTFIND_DATA;
    while (current != 0) {
      vaddr_t next = vaddr_read(current, 4);
      vaddr_t info = vaddr_read(current + 4, 4);
      word_t fields = vaddr_read(info, 4);
      word_t value = find_data ? (fields & 0xffu) : (fields >> 16);
      nodes++;
      if (value == target)
        break;
      current = next;
    }
    R(rd) = current;
    riscv_profile_record_xaccel(XA_LISTFIND, nodes, 5 * nodes);
    matched = true;
  }
  if (IS_INST(XACCEL_XLISTREV_LOOP)) {
    vaddr_t current = R(rs1);
    uint64_t nodes = 0;
    while (current != 0) {
      vaddr_t next = vaddr_read(current, 4);
      vaddr_write(current, 4, list_reverse_previous);
      list_reverse_previous = current;
      current = next;
      nodes++;
    }
    R(rd) = list_reverse_previous;
    riscv_profile_record_xaccel(XA_LISTREV, nodes, 2 * nodes);
    matched = true;
  }
  if (IS_INST(XACCEL_XMSUM)) {
    uint64_t elements;
    R(rd) = matrix_clipped_sum(R(rs1), R(rs2), &elements);
    riscv_profile_record_xaccel(XA_MSUM, elements, 8 + 2 * elements);
    matched = true;
  }
  if (IS_INST(XACCEL_XDFACNT_INIT)) {
    memset(numeric_dfa_transition_counts, 0, sizeof(numeric_dfa_transition_counts));
    memset(numeric_dfa_final_counts, 0, sizeof(numeric_dfa_final_counts));
    numeric_dfa_pending_mask = 0;
    numeric_dfa_internal_state = 0;
    numeric_dfa_internal_stopped = 1;
    riscv_profile_record_xdfacnt(0);
    matched = true;
  }
  if (IS_INST(XACCEL_XDFACNT_INC)) {
    word_t state = R(rs1);
    if (state < 8)
      numeric_dfa_transition_counts[state]++;
    riscv_profile_record_xdfacnt(1);
    matched = true;
  }
  if (IS_INST(XACCEL_XDFACNT_READ)) {
    word_t state = R(rs1);
    R(rd) = state < 8 ? numeric_dfa_transition_counts[state] : 0;
    riscv_profile_record_xdfacnt(2);
    matched = true;
  }
  if (IS_INST(XACCEL_XDFACNT_COMMIT)) {
    word_t mask = R(rs1);
    for (unsigned state = 0; state < 8; state++)
      if (mask & (1u << state))
        numeric_dfa_transition_counts[state]++;
    riscv_profile_record_xdfacnt(3);
    matched = true;
  }
  if (IS_INST(XACCEL_XDFA2_STEP)) {
    vaddr_t address = R(rs2);
    unsigned offset = address & 3u;
    word_t symbols = vaddr_read(address & ~3u, 4) >> (8 * offset);
    R(rd) = numeric_dfa_word_step(R(rs1), symbols, offset == 3 ? 1 : 2, true);
    riscv_profile_record_xdfacnt(4);
    matched = true;
  }
  if (IS_INST(XACCEL_XDFA4_STEP)) {
    vaddr_t address = R(rs2);
    unsigned offset = address & 3u;
    word_t symbols = vaddr_read(address & ~3u, 4) >> (8 * offset);
    R(rd) = numeric_dfa_word_step(R(rs1), symbols, 4 - offset, false);
    riscv_profile_record_xdfacnt(5);
    matched = true;
  }
  if (IS_INST(XACCEL_XDFA4H_FINAL_READ)) {
    word_t state = R(rs1);
    R(rd) = state < 8 ? numeric_dfa_final_counts[state] : 0;
    riscv_profile_record_xdfacnt(2);
    matched = true;
  }
  if (IS_INST(XACCEL_XDFA4H_STEP)) {
    vaddr_t address = R(rs2);
    unsigned offset = address & 3u;
    word_t symbols = vaddr_read(address & ~3u, 4) >> (8 * offset);
    word_t result = numeric_dfa_word_step(R(rs1), symbols, 4 - offset, false);
    word_t mask = result >> 7;
    numeric_dfa_pending_mask |= mask;
    if (result & (1u << 6)) {
      for (unsigned state = 0; state < 8; state++)
        if (numeric_dfa_pending_mask & (1u << state))
          numeric_dfa_transition_counts[state]++;
      numeric_dfa_final_counts[result & 7u]++;
      numeric_dfa_pending_mask = 0;
    }
    R(rd) = result;
    riscv_profile_record_xdfacnt(5);
    matched = true;
  }
  if (IS_INST(XACCEL_XDFA4H_STEP_PTR)) {
    vaddr_t address = R(rs2);
    unsigned offset = address & 3u;
    word_t symbols = vaddr_read(address & ~3u, 4) >> (8 * offset);
    word_t start_state = numeric_dfa_internal_stopped ? 0 : numeric_dfa_internal_state;
    word_t result = numeric_dfa_word_step(start_state, symbols, 4 - offset, false);
    word_t mask = result >> 7;
    numeric_dfa_pending_mask |= mask;
    if (result & (1u << 6)) {
      unsigned consumed = (result >> 3) & 7u;
      // A NUL-first-byte stop with no pending transitions is the terminal
      // empty-token step; the software loop never executes it, so it must
      // not record a final state.
      if (consumed != 0 || numeric_dfa_pending_mask != 0) {
        for (unsigned state = 0; state < 8; state++)
          if (numeric_dfa_pending_mask & (1u << state))
            numeric_dfa_transition_counts[state]++;
        numeric_dfa_final_counts[result & 7u]++;
      }
      numeric_dfa_pending_mask = 0;
    }
    numeric_dfa_internal_state = result & 7u;
    numeric_dfa_internal_stopped = (result >> 6) & 1u;
    R(rd) = address + ((result >> 3) & 7u);
    riscv_profile_record_xdfacnt(5);
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
