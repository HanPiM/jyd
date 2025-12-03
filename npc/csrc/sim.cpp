#include <VTop.h>
#include <VTop__Dpi.h>
#include <array>
#include <cstdint>
#include <cstdio>
#include <nvboard.h>
#include <string_view>

#include "sdb.hpp"
#include "tracers.hpp"

#ifndef TOP_NAME
#endif
TOP_NAME dut;
void nvboard_bind_all_pins(TOP_NAME *top);


typedef uint32_t word_t;
typedef uint32_t addr_t;

#define MADDR_BASE 0x80000000u
#define INITIAL_PC MADDR_BASE

std::shared_ptr<sdb::debuger> dbg;
sdb::difftest_trace_handler_ptr diff_handler;

static void step_cycle() {
  //	printf("-----step-----\n");
  dut.clock = 0;
  dut.eval();

  dut.clock = 1;
  dut.eval();

  //	printf("-------------\n");
}
static void reset(int n) {
  dut.reset = 1;
  while (n-- > 0) {
    step_cycle();
  }
  dut.reset = 0;
}

static bool is_running = true;
static bool is_good_trap = false;

word_t current_pc = INITIAL_PC;
void raise_ebreak(int a0) {
  is_running = false;

	dbg->state().halt(a0);

#define ANSI_FG_RED     "\33[1;31m"
#define ANSI_FG_GREEN   "\33[1;32m"
#define ANSI_NONE       "\33[0m"

	if(a0==0){
		printf(ANSI_FG_GREEN "HIT GOOD TRAP" ANSI_NONE);
		is_good_trap=true;
	}
	else{
		printf(ANSI_FG_RED "HIT BAD TRAP" ANSI_NONE);
	}
	printf(" at pc = 0x%08x\n",current_pc);
}


word_t mem[600 * 1024 * 1024 / 4] = {
    0x00000297, // auipc t0,0
    0x00028823, // sb  zero,16(t0)
    0x0102c503, // lbu a0,16(t0)
    0x00100073, // ebreak
    0xdeadbeef, // some data
    0x12345678,
};
uint8_t *mem_atguest(size_t addr) {
  assert(addr >= MADDR_BASE);
  return ((uint8_t *)mem) + addr - MADDR_BASE;
}
word_t guest_to_host(word_t addr) {
  // printf("raw addr %08X\n",addr);
  // assert(addr>=MADDR_BASE);
  word_t res = addr - MADDR_BASE;
  return res;
}

word_t gpr_snap[32];
void gpr_upd(int regno, int data) {
  if (regno == 0)
    return;
  gpr_snap[regno] = data;
}
std::array<std::string_view, 32> reg_names = {
    "$0", "ra", "sp", "gp", "tp",  "t0",  "t1", "t2", "s0", "s1", "a0",
    "a1", "a2", "a3", "a4", "a5",  "a6",  "a7", "s2", "s3", "s4", "s5",
    "s6", "s7", "s8", "s9", "s10", "s11", "t3", "t4", "t5", "t6"};

bool pc_changed = false;
void pc_upd(int pc, int npc) {
  //	printf("pc upd pc=%08x npc=%08x\n",pc,npc);
  pc_changed = true;
  current_pc = npc;
}

void fetch_inst(int pc, int *out_inst) {
  printf("fetch pc=%08x\n", pc);
  *out_inst = mem[guest_to_host(pc) / 4];
}
void pmem_read(int addr, int *out_data) {
  uint32_t host_aligned = guest_to_host(addr) & (~0x3);
  *out_data = mem[host_aligned / 4];
  printf("pmem read addr=%08x get %08X\n", addr, *out_data);
}
void pmem_write(int addr, int data, int mask) {
  printf("pmem write addr=%08x data=%08x mask=%02x\n", addr, data, mask);
  uint32_t host_aligned = guest_to_host(addr) & (~0x3);

  uint8_t *p = (uint8_t *)(&mem[host_aligned >> 2]);
  uint32_t umask = mask, udata = data;

  while (umask) {
    if (umask & 1) {
      *p = udata & 0xff;
    }
    p++;
    umask >>= 1;
    udata >>= 8;
  }
}

void step_inst() {
  while (!pc_changed) {
    step_cycle();
  }
  pc_changed = false;
}

// SDB

namespace sdbwrap {
sdb::paddr_t cpu_exec(size_t n) {
  while (n-- > 0) {
    step_inst();
  }
  return current_pc;
}
void shot_regsnap(sdb::reg_snapshot_t &regsnap) {
  for (size_t i = 0; i < 32; i++) {
    regsnap[i] = gpr_snap[i];
  }
}
sdb::vlen_inst_code inst_fetcher(sdb::paddr_t pc) {
  word_t inst;
  fetch_inst(pc, (int *)&inst);
	uint8_t *p = (uint8_t *)&inst;
	return sdb::vlen_inst_code(p, p + 4);
}
uint8_t *loadmem(sdb::paddr_t addr, size_t nbyte) { return mem_atguest(addr); }
} // namespace sdbwrap


int main() {
  nvboard_bind_all_pins(&dut);
  // nvboard_init();

  size_t img_size = 100;

  dbg = std::make_shared<sdb::debuger>(
      INITIAL_PC, INITIAL_PC, img_size, sdbwrap::cpu_exec, sdbwrap::loadmem,
      sdbwrap::shot_regsnap,
      std::vector<std::string_view>(reg_names.begin(), reg_names.end()),
      sdbwrap::inst_fetcher);

  dbg->enable_inst_trace = true;

  dbg->add_trace(sdb::make_disasm_trace_handler(sdb::default_inst_disasm, 16));
  dbg->add_trace(sdb::make_etrace_handler());
  dbg->add_trace(sdb::make_iringbuf_trace_handler());

  diff_handler = sdb::make_difftest_trace_handler(
      "../nemu/build/riscv32-nemu-interpreter-so", 0);
  dbg->add_trace(diff_handler);
  reset(10);

  std::string cmd;
  while (true) {
    std::cout << "(sdb) ";
    std::getline(std::cin, cmd);
    dbg->exec_command(cmd);
    if (dbg->state().state == sdb::run_state::quit) {
      break;
    }
  }
  return 0;
}
