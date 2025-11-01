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

#include <cpu/cpu.h>
#include <cpu/decode.h>
#include <cpu/difftest.h>
#include <locale.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include "../monitor/sdb/sdb.h"
#include "common.h"
#include "debug.h"
#include "utils.h"

/* The assembly code of instructions executed is only output to the screen
 * when the number of instructions executed is less than this value.
 * This is useful when you use the `si' command.
 * You can modify this value as you want.
 */
#define MAX_INST_TO_PRINT 10

#define IRINGBUF_SIZE 32

// for variant ilen
#define MAX_INSTBYTE 8

void disassemble(char *str, int size, uint64_t pc, uint8_t *code, int nbyte);

CPU_state cpu = {};
uint64_t g_nr_guest_inst = 0;
static uint64_t g_timer = 0; // unit: us
static bool g_print_step = false;

typedef struct{
	vaddr_t pc;
	int ilen;
	uint8_t code[MAX_INSTBYTE];
}_pc_inst_t;

void dis_asm(char* outbuf,int bufsiz,const _pc_inst_t* inst){
	disassemble(outbuf,bufsiz,inst->pc,(uint8_t*)inst->code,inst->ilen);
}	

void expand_tabs(char *out, const char *in, int tabsize) {
    int col = 0;
    while (*in) {
        if (*in == '\t') {
            int spaces = tabsize - (col % tabsize);
            for (int i = 0; i < spaces; i++) {
                *out++ = ' ';
                col++;
            }
        } else {
            *out++ = *in;
            col++;
        }
        in++;
    }
    *out = '\0';
}

struct{
	_pc_inst_t buf[IRINGBUF_SIZE];
	size_t idx_end;
}g_iringbuf;

// ringbuf mod plus 1
#define _rb_mp1(x) (((x)+1)%IRINGBUF_SIZE)

#define ANSI_FG_GRAY "\033[90m" // light black


// push pc should nerver be 0
// 0 pc consider as none inst will be ignore
void _ringbuf_push(vaddr_t pc,const uint8_t* inst,int ilen){
	g_iringbuf.buf[g_iringbuf.idx_end].pc=pc;
	memcpy(g_iringbuf.buf[g_iringbuf.idx_end].code,inst,ilen);
	g_iringbuf.buf[g_iringbuf.idx_end].ilen=ilen;
	g_iringbuf.idx_end=_rb_mp1(g_iringbuf.idx_end);
}
void _ringbuf_dump(){
	char rawdasm[128];
	char dmpbuf[256];
	for(size_t i=_rb_mp1(g_iringbuf.idx_end);
			i!=g_iringbuf.idx_end;
			i=_rb_mp1(i)){
		_pc_inst_t* pinst=&g_iringbuf.buf[i];
		if(!pinst->pc)continue;

		dis_asm(rawdasm,sizeof(rawdasm),pinst);
		expand_tabs(dmpbuf,rawdasm,6);
		printf("%08X: %s%-25s" ANSI_FG_GRAY "(",
				pinst->pc,
				_rb_mp1(i)==g_iringbuf.idx_end?ANSI_FG_RED:ANSI_NONE,
				dmpbuf);
		for(int j=0;j<pinst->ilen;j++){
			if(j)putchar(' ');
			printf("%02x",pinst->code[j]);
		}
		puts(")"ANSI_NONE);
	}
}


void device_update();

static void trace_and_difftest(Decode *_this, vaddr_t dnpc) {
#ifdef CONFIG_ITRACE_COND
  if (ITRACE_COND) { log_write("%s\n", _this->logbuf); }
#endif
  if (g_print_step) { IFDEF(CONFIG_ITRACE, puts(_this->logbuf)); }
  IFDEF(CONFIG_DIFFTEST, difftest_step(_this->pc, dnpc));

#ifdef CONFIG_WATCHPOINT 
  check_wp();
#endif
}

static void exec_once(Decode *s, vaddr_t pc) {
  s->pc = pc;
  s->snpc = pc;
  isa_exec_once(s);
  cpu.pc = s->dnpc;
#ifdef CONFIG_ITRACE
  char *p = s->logbuf;
  p += snprintf(p, sizeof(s->logbuf), FMT_WORD ":", s->pc);
  int ilen = s->snpc - s->pc;
  int i;
  uint8_t *inst = (uint8_t *)&s->isa.inst;
#ifdef CONFIG_ISA_x86
  for (i = 0; i < ilen; i ++) {
#else
  for (i = ilen - 1; i >= 0; i --) {
#endif
    p += snprintf(p, 4, " %02x", inst[i]);
  }
  int ilen_max = MUXDEF(CONFIG_ISA_x86, 8, 4);
  int space_len = ilen_max - ilen;
  if (space_len < 0) space_len = 0;
  space_len = space_len * 3 + 1;
  memset(p, ' ', space_len);
  p += space_len;

  _ringbuf_push(MUXDEF(CONFIG_ISA_x86, s->snpc, s->pc),
		  (uint8_t *)&s->isa.inst, ilen);
  disassemble(p, s->logbuf + sizeof(s->logbuf) - p,
      MUXDEF(CONFIG_ISA_x86, s->snpc, s->pc), (uint8_t *)&s->isa.inst, ilen);
#endif
}

static void execute(uint64_t n) {
  Decode s;
  for (;n > 0; n --) {
    exec_once(&s, cpu.pc);
    g_nr_guest_inst ++;
    trace_and_difftest(&s, cpu.pc);
    if (nemu_state.state != NEMU_RUNNING) break;
    IFDEF(CONFIG_DEVICE, device_update());
  }
}

static void statistic() {
  IFNDEF(CONFIG_TARGET_AM, setlocale(LC_NUMERIC, ""));
#define NUMBERIC_FMT MUXDEF(CONFIG_TARGET_AM, "%", "%'") PRIu64
  Log("host time spent = " NUMBERIC_FMT " us", g_timer);
  Log("total guest instructions = " NUMBERIC_FMT, g_nr_guest_inst);
  if (g_timer > 0) Log("simulation frequency = " NUMBERIC_FMT " inst/s", g_nr_guest_inst * 1000000 / g_timer);
  else Log("Finish running in less than 1 us and can not calculate the simulation frequency");
}

void assert_fail_msg() {
#define putsyellow(s)  puts(ANSI_FG_YELLOW s ANSI_NONE);
  putsyellow("inst buffer");
  _ringbuf_dump();
  putsyellow("register info");
  isa_reg_display();
  statistic();
}

/* Simulate how the CPU works. */
void cpu_exec(uint64_t n) {
  g_print_step = (n < MAX_INST_TO_PRINT);
  switch (nemu_state.state) {
    case NEMU_END: case NEMU_ABORT: case NEMU_QUIT:
      printf("Program execution has ended. To restart the program, exit NEMU and run again.\n");
      return;
    default: nemu_state.state = NEMU_RUNNING;
  }

  uint64_t timer_start = get_time();

  execute(n);

  uint64_t timer_end = get_time();
  g_timer += timer_end - timer_start;

  switch (nemu_state.state) {
    case NEMU_RUNNING: nemu_state.state = NEMU_STOP; break;

    case NEMU_END: case NEMU_ABORT:
      Log("nemu: %s at pc = " FMT_WORD,
          (nemu_state.state == NEMU_ABORT ? ANSI_FMT("ABORT", ANSI_FG_RED) :
           (nemu_state.halt_ret == 0 ? ANSI_FMT("HIT GOOD TRAP", ANSI_FG_GREEN) :
            ANSI_FMT("HIT BAD TRAP", ANSI_FG_RED))),
          nemu_state.halt_pc);
      // fall through
    case NEMU_QUIT: statistic();
  }
}
