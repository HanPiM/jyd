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
#include <readline/readline.h>
#include <readline/history.h>
#include <stdio.h>
#include <memory/paddr.h>
#include "sdb.h"
#include "utils.h"

#include <sdbc.h>

static int is_batch_mode = false;

void init_regex();
void init_wp_pool();

static sdb_debuger dbg;

/* We use the `readline' library to provide more flexibility to read from stdin. */
static char* rl_gets() {
  static char *line_read = NULL;

  if (line_read) {
    free(line_read);
    line_read = NULL;
  }

  line_read = readline("(nemu) ");

  if (line_read && *line_read) {
    add_history(line_read);
  }

  return line_read;
}

int is_exit_status_bad(); 
/*
static int cmd_q(char *args) {
  if(is_exit_status_bad())return -1;
  else set_nemu_state(NEMU_QUIT,0,0);
  return -1;
}
*/

void sdb_set_batch_mode() {
  is_batch_mode = true;
}

void sdb_mainloop() {
  if (is_batch_mode) {
		sdb_exec(dbg, "c");
    return;
  }

  for (char *str; (str = rl_gets()) != NULL; ) {
		sdb_exec(dbg, str);


#ifdef CONFIG_DEVICE
    extern void sdl_clear_event_queue();
    sdl_clear_event_queue();
#endif

  }
}

sdb_paddr_t cpu_exec_wrapper() {
	cpu_exec(1);
	return cpu.pc;
}
uint8_t* wrap_mem_loader(sdb_paddr_t addr, size_t nbyte){
	return (uint8_t*)guest_to_host(addr);
}
void wrap_shotreg(uint32_t* reg_snapshot){
	memcpy(reg_snapshot, cpu.gpr, sizeof(cpu.gpr));
}
sdb_vlen_inst_code wrap_fetch_inst(sdb_paddr_t pc){
	sdb_vlen_inst_code inst_code;
	inst_code.data=(uint8_t*)guest_to_host(pc);
	inst_code.len=4;
	return inst_code;
}

void init_sdb() {
  /* Compile the regular expressions. */
  init_regex();

  /* Initialize the watchpoint pool. */
  init_wp_pool();

	extern const char* regs[32];

	dbg=sdb_create_debuger(
			CONFIG_MBASE,
			cpu_exec_wrapper,
			wrap_mem_loader,
			wrap_shotreg,
		 	regs, 32, NULL,
			wrap_fetch_inst);
	uint32_t flags=0;
#ifdef CONFIG_FTRACE
	flags|=SDB_ENTRACE_FTRACE;
#endif
#ifdef CONFIG_ITRACE
	flags|=SDB_ENTRACE_INST;
#endif
	sdb_enable_entrace(dbg, flags);
}
