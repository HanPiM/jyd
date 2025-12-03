#include <VTop.h>
#include <VTop__Dpi.h>
#include <array>
#include <cstdint>
#include <cstdio>
#include <nvboard.h>
#include <string_view>

#include "elf_tool.hpp"
#include "sdb.hpp"
#include "tracers.hpp"

#include <getopt.h>
#include <unistd.h>

#ifndef TOP_NAME
#endif
TOP_NAME dut;
void nvboard_bind_all_pins(TOP_NAME *top);

typedef uint32_t word_t;
typedef uint32_t addr_t;

#define MADDR_BASE 0x80000000u
#define INITIAL_PC MADDR_BASE

// #define TRACE_PMEM_CALL
// #define TRACE_SHOW_ALL_INST

std::shared_ptr<sdb::debuger> dbg;
sdb::difftest_trace_handler_ptr diff_handler;

static void step_cycle() {
  // printf("-----step-----\n");
  dut.clock = 0;
  dut.eval();

  dut.clock = 1;
  dut.eval();

  // printf("-------------\n");
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

#define ANSI_FG_RED "\33[1;31m"
#define ANSI_FG_GREEN "\33[1;32m"
#define ANSI_NONE "\33[0m"

  if (a0 == 0) {
    printf(ANSI_FG_GREEN "HIT GOOD TRAP" ANSI_NONE);
    is_good_trap = true;
  } else {
    printf(ANSI_FG_RED "HIT BAD TRAP" ANSI_NONE);
  }
  printf(" at pc = 0x%08x\n", current_pc);
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

void skip_difftest_ref() {
  if (diff_handler)
    diff_handler->skip_ref();
}

void fetch_inst(int pc, int *out_inst) {
  // printf("fetch pc=%08x\n", pc);
  if (pc == 0) {
    *out_inst = 0;
    return;
  }
  *out_inst = mem[guest_to_host(pc) / 4];
}

#define MMIO_SERIAL_PORT 0x10000000u
#define MMIO_RTC_ADDR 0x10000048u

void pmem_read(int addr, int *out_data) {
  if (!is_running) {
    printf("warn: pmem_read when not running\n");
    *out_data = 0;
    return;
  }

  if (addr == MMIO_RTC_ADDR || addr == MMIO_RTC_ADDR + 4) {
    skip_difftest_ref();
    static uint64_t time_in_us;
    if (addr == MMIO_RTC_ADDR) {
      struct timespec ts;
      clock_gettime(CLOCK_MONOTONIC_COARSE, &ts);
      time_in_us = ts.tv_sec * 1000000 + ts.tv_nsec / 1000;
      *out_data = (uint32_t)(time_in_us & 0xffffffffu);
    } else {
      *out_data = time_in_us >> 32;
    }
    return;
  }
  uint32_t host_aligned = guest_to_host(addr) & (~0x3);

  //  if (host_aligned >= sizeof(mem)) {
  //    *out_data = 0;
  //    return;
  //  }

  *out_data = mem[host_aligned / 4];
#ifdef TRACE_PMEM_CALL
  printf("pmem read addr=%08x get %08X\n", addr, *out_data);
#endif
}
void pmem_write(int addr, int data, int mask) {

  if (addr == MMIO_SERIAL_PORT) {
    // printf("pmem_write to serial port: %c\n",wdata&0xff);
    skip_difftest_ref();
    putchar(data & 0xff);
    fflush(stdout);
    return;
  }

#ifdef TRACE_PMEM_CALL
  printf("pmem write addr=%08x data=%08x mask=%02x\n", addr, data, mask);
#endif

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

void dump_regs() {
  for (int i = 0; i < 32; i++) {
    printf("%s: %08x\n", reg_names[i].data(), gpr_snap[i]);
  }
}
void step_inst() {
  while (!pc_changed) {
    step_cycle();
  }
  pc_changed = false;
  //	dump_regs();
}

// IMG

static const char *img_file;
static size_t img_size;
static bool batch_mode = false;

static long load_img() {
#define Log(fmt, ...) printf(fmt "\n", ##__VA_ARGS__)
#define Assert(expr, ...)                                                      \
  do {                                                                         \
    if (!(expr)) {                                                             \
      fprintf(stderr, __VA_ARGS__);                                            \
    }                                                                          \
  } while (0)

  if (img_file == NULL) {
    Log("No image is given. Use the default build-in image.");
    return 4096; // built-in image size
  }

  FILE *fp = fopen(img_file, "rb");
  Assert(fp, "Can not open '%s'", img_file);

  fseek(fp, 0, SEEK_END);
  img_size = ftell(fp);

  Log("The image is %s, size = %ld", img_file, img_size);

  fseek(fp, 0, SEEK_SET);
  int ret = fread(mem, img_size, 1, fp);
  assert(ret == 1);

  fclose(fp);

  return img_size;
}

// ARG

static void parse_args(int argc, char **argv) {
  const struct option table[] = {{"batch", no_argument, NULL, 'b'},
                                 {0, 0, NULL, 0}};
  int o;
  while ((o = getopt_long(argc, argv, "-b", table, NULL)) != -1) {
    switch (o) {
    case 'b':
      batch_mode = true;
      break;
    case 1:
      img_file = optarg;
      break;
    default:
      printf("Bad option %c\n", o);
      exit(1);
    }
  }
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

int main(int argc, char **argv) {
  nvboard_bind_all_pins(&dut);
  // nvboard_init();

  parse_args(argc, argv);
  using std::string;
  using namespace std::views;
  using namespace std::ranges;

  load_img();

  if (img_file) {
    auto elf_file = try_find_elf_file_of(img_file);

    if (!elf_file.empty()) {
      printf("Found ELF file: %s\n", elf_file.c_str());
      //	dbg->add_trace(sdb::make_ftrace_handler(elf_file));
    }
  }

  dbg = std::make_shared<sdb::debuger>(
      INITIAL_PC, INITIAL_PC, img_size, sdbwrap::cpu_exec, sdbwrap::loadmem,
      sdbwrap::shot_regsnap,
      std::vector<std::string_view>(reg_names.begin(), reg_names.end()),
      sdbwrap::inst_fetcher);

  dbg->enable_inst_trace = true;

  size_t inst_show_limit;
#ifdef TRACE_SHOW_ALL_INST
  inst_show_limit = -1;
#else
  inst_show_limit = 16;
#endif

  dbg->add_trace(sdb::make_disasm_trace_handler(sdb::default_inst_disasm,
                                                inst_show_limit));
  dbg->add_trace(sdb::make_etrace_handler());
  dbg->add_trace(sdb::make_iringbuf_trace_handler());

  diff_handler = sdb::make_difftest_trace_handler(
      "../nemu/build/riscv32-nemu-interpreter-so", 0);
  dbg->add_trace(diff_handler);
  reset(10);

  if (batch_mode) {
    dbg->exec_command("c");
    return dbg->state().is_badexit();
  }

  std::string cmd;
  while (true) {
    std::cout << "(sdb) ";
    std::getline(std::cin, cmd);
    dbg->exec_command(cmd);
    if (dbg->state().state == sdb::run_state::quit) {
      break;
    }
  }
  return is_good_trap ? 0 : 1;
}
