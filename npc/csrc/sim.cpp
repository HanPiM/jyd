#include "sim.hpp"
#include "dbg.hpp"

#include <spdlog/pattern_formatter.h>
#include <spdlog/sinks/basic_file_sink.h>
#include <spdlog/sinks/dup_filter_sink.h>
#include <spdlog/sinks/stdout_color_sinks.h>
#include <spdlog/spdlog.h>

#include <cassert>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <string_view>

#include "spdlog/common.h"
#include "spdlog/logger.h"
#include "verilated_fst_c.h"

#ifdef ENABLE_NVBOARD
#include <nvboard.h>
#endif

#include <getopt.h>
#include <unistd.h>

TOP_NAME dut;
sim_setting sim_settings;

TOP_NAME *get_dut() { return &dut; }

void nvboard_bind_all_pins(TOP_NAME *top);

typedef uint32_t word_t;
typedef uint32_t addr_t;

#define MADDR_BASE 0x20000000u
#define INITIAL_PC 0x30000000u

// #define TRACE_PMEM_CALL
// #define TRACE_SHOW_ALL_INST

std::shared_ptr<VerilatedFstC> tfp;

static uint64_t sim_time = 0;
static uint64_t cycle_count = 0;

static std::shared_ptr<spdlog::logger> _dpi_logger;

class sim_time_formatter : public spdlog::custom_flag_formatter {
public:
  void format(const spdlog::details::log_msg &, const std::tm &,
              spdlog::memory_buf_t &dest) override {
    std::string s = std::to_string(sim_time) + "ps";
    dest.append(s.data(), s.data() + s.size());
  }

  std::unique_ptr<custom_flag_formatter> clone() const override {
    return spdlog::details::make_unique<sim_time_formatter>();
  }
};

static void _sim_eval() {
  dut.eval();
  sim_time++;
#if ENABLE_WAVE
  if (tfp) {
    tfp->dump(sim_time);
  }
#endif
}

void sim_step_cycle() {
  if (sim_settings.trace_clock_cycle) {
    printf("[Clock Cycle Begin]\n");
  }

  dut.clock = 0;
  _sim_eval();
  dut.clock = 1;
  _sim_eval();

  cycle_count++;

#ifdef ENABLE_NVBOARD
  nvboard_update();
#endif

  if (sim_settings.trace_clock_cycle) {
    printf("[Clock Cycle End]\n");
  }
  if (sim_settings.cycle_finish_cb) {
    sim_settings.cycle_finish_cb();
  }
}
static void reset(int n) {
  dut.reset = 1;
  while (n-- > 0) {
    sim_step_cycle();
  }
  dut.reset = 0;
}

static bool is_running = true;
static bool is_good_trap = false;

word_t current_pc = INITIAL_PC;
word_t sim_current_pc() { return current_pc; }

void raise_ebreak(int a0) {
  is_running = false;

  dbg_set_halt(a0);

  constexpr std::string_view fg_red = "\33[1;31m", fg_green = "\33[1;32m",
                             fg_yellow = "\33[1;33m", ansi_none = "\33[0m";

  is_good_trap = (a0 == 0);

  // if (a0 == 0) {
  // spdlog::info("{}HIT GOOD TRAP{}", fg_green, ansi_none);
  //   is_good_trap = true;
  // } else {
  // spdlog::info("{}HIT BAD TRAP{} a0 = {}", fg_red, ansi_none, a0);
  // }
  // printf(" @pc = 0x%08x cyc %lu\n", current_pc, cycle_count);
  //
  spdlog::info("{}HIT {} TRAP{} a0 = {} @pc = 0x{:08x} cyc {}",
               is_good_trap ? fg_green : fg_red, is_good_trap ? "GOOD" : "BAD",
               ansi_none, a0, current_pc, cycle_count);
}
bool sim_halted() { return !is_running; }
bool sim_hit_good_trap() { return is_good_trap; }

word_t img[60 * 1024 * 1024 / 4] = {
    0x00000297, // auipc t0,0
    0x00028823, // sb  zero,16(t0)
    0x0102c503, // lbu a0,16(t0)
    0x00100073, // ebreak
    0xdeadbeef, // some data
    0x12345678,
};

constexpr uint32_t MROM_BASE = 0x20000000u;
constexpr uint32_t MROM_END = 0x20010000u;
word_t mrom_data[(MROM_END - MROM_BASE) / 4];
extern "C" void mrom_read(int32_t addr, int32_t *data) {
  if (addr < MROM_BASE) {
    _dpi_logger->error("addr={:08x} ERROR BELOW MROM_BASE", sim_time, addr);
  }
  assert(addr >= MROM_BASE);
  addr -= MROM_BASE;
  assert(addr < sizeof(img));
  addr &= ~0x3;
  uintptr_t ptr = (uintptr_t)img + addr;
  *data = *(int32_t *)ptr;
  // printf("[DPI] mrom_read addr=%08x data=%08x alignedd=%08X\n", addr +
  // MROM_BASE, *data,aligned_data);
}

constexpr uint32_t FLASH_BASE = 0x30000000u;
constexpr uint32_t FLASH_END = 0x40000000u;
uint32_t flash_data[sizeof(img) / 4];
static void _init_flash();
extern "C" void flash_read(int32_t addr, int32_t *data) {
  // in spi
  //   .addr({8'b0, in_paddr[23:2], 2'b0}),
  // so the high 8 bits are ignored
  // 0x3XXXXXXX -> 0x0XXXXXXX
  // no need to minus FLASH_BASE
  assert(addr < sizeof(flash_data));
  addr &= ~0x3;
  uintptr_t ptr = (uintptr_t)flash_data + addr;
  *data = *(int32_t *)ptr;
  // printf("[DPI] flash_read addr=%08x data=%08x\n", addr + FLASH_BASE, *data);
}

constexpr uint32_t PSRAM_BASE = 0x80000000u;
constexpr uint32_t PSRAM_END = 0xA0000000u;
uint32_t psram_data[8 * 1024 * 1024 / 4];
extern "C" void psram_read(int32_t addr, int32_t *data) {
  // in psram high 8bit addr are 0
  // no need to minus PSRAM_BASE
  assert(addr < sizeof(psram_data));
  addr &= ~0x3;
  uintptr_t ptr = (uintptr_t)psram_data + addr;
  *data = *(int32_t *)ptr;
  // printf("[DPI] psram_read addr=%08x data=%08x\n", addr + PSRAM_BASE, *data);
}
extern "C" void psram_write(int32_t addr, char strb8, int32_t data, int32_t *) {
  assert(addr < sizeof(psram_data));
  uint8_t shift = (addr & 0x3) * 8;
  uint32_t aligned_addr = addr & (~0x3);
  auto ptr = &psram_data[aligned_addr / 4];

  uint32_t strb32 = 0;
  if (strb8 & 0x1)
    strb32 |= 0x000000ff;
  if (strb8 & 0x2)
    strb32 |= 0x0000ff00;
  if (strb8 & 0x4)
    strb32 |= 0x00ff0000;
  if (strb8 & 0x8)
    strb32 |= 0xff000000;
  uint32_t shMask = strb32 << shift;
  uint32_t shData = data << shift;

  *ptr &= ~shMask;
  *ptr |= (shData & shMask);

  // printf("[DPI] psram_write addr=%08x data=%08x (strb %X)\n", addr +
  // PSRAM_BASE, data, (uint32_t)strb8);
}

constexpr uint32_t SDRAM_BASE = 0xa0000000u;
constexpr uint32_t SDRAM_END = 0xb0000000u;

uint16_t sdram_data[4][8192][512];

extern "C" void sdram_read(char bank, short row, short col, short *data) {
  assert(bank >= 0 && bank < 4);
  assert(row >= 0 && row < 8192);
  assert(col >= 0 && col < 512);
  *data = sdram_data[bank][row][col];
  _dpi_logger->trace("sdram_read bank={:02x} row={:04x} col={:04x} data={:04x}",
                     bank, row, col, (uint16_t)*data);
}
extern "C" void sdram_write(char bank, short row, short col, short data,
                            char mask) {
  assert(bank >= 0 && bank < 4);
  assert(row >= 0 && row < 8192);
  assert(col >= 0 && col < 512);
  // mask [0] = 0: write low byte
  // mask [1] = 0: write high byte
  if ((mask & 0x1) == 0) {
    sdram_data[bank][row][col] &= 0xff00;
    sdram_data[bank][row][col] |= (data & 0x00ff);
    // _dpi_logger->trace("sdram_write low byte {:02x}", (uint8_t)(data &
    // 0x00ff));
  }
  if ((mask & 0x2) == 0) {
    sdram_data[bank][row][col] &= 0x00ff;
    sdram_data[bank][row][col] |= (data & 0xff00);
    // _dpi_logger->trace("sdram_write high byte {:02x}", (uint8_t)((data &
    // 0xff00) >> 8));
  }

  char human_friendly_mask[3] = {'-', '-', '\0'};
  if ((mask & 0x1) == 0)
    human_friendly_mask[1] = 'L';
  if ((mask & 0x2) == 0)
    human_friendly_mask[0] = 'H';

  _dpi_logger->trace("sdram_write bank={:02x} row={:04x} col={:04x} "
                     "data={:04x} mask={} newdata={:04x}",
                     bank, row, col, (uint16_t)data, human_friendly_mask,
                     sdram_data[bank][row][col]);
}

constexpr uint32_t SRAM_BASE = 0x0f000000u;
constexpr uint32_t SRAM_END = 0x10000000u;

uint8_t *sim_guest_to_host(uint32_t addr) {
  uint32_t *ptr = nullptr;
  if (addr >= MROM_BASE && addr < MROM_END) {
    ptr = img + (addr - MROM_BASE);
  } else if (addr >= FLASH_BASE && addr < FLASH_END) {
    ptr = flash_data + (addr - FLASH_BASE);
  } else {
    printf("[W] mem_atguest don't support addr=%08x\n", addr);
    assert(0);
  }
  // printf("[DPI] mem_atguest addr=%08x get %08x\n",addr,*ptr);
  return (uint8_t *)ptr;
}
word_t guest_to_host(word_t addr) {
  // printf("raw addr %08X\n",addr);
  // assert(addr>=MADDR_BASE);
  word_t res = addr - MADDR_BASE;
  return res;
}

word_t gpr_snap[32];
word_t *sim_current_gpr() { return gpr_snap; }

void gpr_upd(int regno, int data) {
  if (regno == 0)
    return;
  gpr_snap[regno] = data;
}

bool pc_changed = false;
void pc_upd(int pc, int npc) {
  //	printf("pc upd pc=%08x npc=%08x\n",pc,npc);
  pc_changed = true;
  current_pc = npc;
}

void skip_difftest_ref() {
  if (sim_settings.trace_difftest_skip) {
    printf("[DPI] skip_difftest_ref called\n");
  }
  dbg_skip_difftest_ref();
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

  if (sim_settings.trace_pmem_readcall) {
    printf("[DPI] pmem_read addr=%08x get ", addr);
  }

  uint32_t host_aligned = guest_to_host(addr) & (~0x3);
  *out_data = img[host_aligned / 4];

  if (sim_settings.trace_pmem_readcall) {
    printf("%08x\n", *out_data);
  }
}
void pmem_write(int addr, int data, int mask) {
  if (addr == MMIO_SERIAL_PORT) {
    if (sim_settings.trace_mmio_write) {
      printf("[DPI] MMIO write to serial port: %c\n", data & 0xff);
    }
    skip_difftest_ref();
    putchar(data & 0xff);
    fflush(stdout);
    return;
  }

  if (sim_settings.trace_pmem_writecall) {
    printf("[DPI] pmem write addr=%08x data=%08x mask=%02x\n", addr, data,
           mask);
  }

  uint32_t host_aligned = guest_to_host(addr) & (~0x3);

  uint8_t *p = (uint8_t *)(&img[host_aligned >> 2]);
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

bool sim_read_vmem(word_t addr, word_t *data) {
  if (addr >= MROM_BASE && addr < MROM_END) {
    mrom_read(addr, (int *)data);
  } else if (addr >= FLASH_BASE && addr < FLASH_END) {
    flash_read(addr - FLASH_BASE, (int *)data);
  } else if (addr >= SRAM_BASE && addr < SRAM_END) {
    // TODO: shouldn't read directly
    // should gen warn and return nothing
    // for debug
    *data = img[(addr - SRAM_BASE) / 4];
  } else if (addr >= PSRAM_BASE && addr < PSRAM_END) {
    psram_read(addr - PSRAM_BASE, (int *)data);
  } else {
    // TODO: gen error
    return false;
  }
  return true;
}

void sim_step_inst() {
  size_t cnt = 0;
  // SPI flash may need many cycles to respond
  constexpr size_t MAYBE_DEADLOOP_THRESHOLD = 8192;
  while (!pc_changed) {
    sim_step_cycle();
    if (sim_halted()) {
      current_pc += 4;
      return;
    }
    cnt++;
    if (cnt >= MAYBE_DEADLOOP_THRESHOLD) {
      dbg_dump_recent_info();
      // printf(ANSI_FG_YELLOW "[WARN] " ANSI_NONE);
      // printf("simulation has stepped %zu cycles without pc change, maybe lock
      // "
      //        "happened\n",
      //        cnt);
      spdlog::warn("simulation has stepped {} cycles without pc change, maybe "
                   "lock happened",
                   cnt);
      printf("wanting to continue? (y/[n]) ");
      char c = getchar();
      if (c == 'y' || c == 'Y') {
        cnt = 0;
        while (getchar() != '\n') {
        }
        continue;
      } else {
        // printf("sim exit\n");
        spdlog::info("sim exit due to possible deadloop");
        exit(1);
      }
    }
  }
  pc_changed = false;
}

// IMG

static const char *img_file;
static size_t img_size;
static bool batch_mode = false;

static long load_img() {
#define Assert(expr, ...)                                                      \
  do {                                                                         \
    if (!(expr)) {                                                             \
      fprintf(stderr, __VA_ARGS__);                                            \
    }                                                                          \
  } while (0)

  if (img_file == NULL) {
    spdlog::warn("No image is given. Use the default build-in image.");
    return img_size = 4096; // built-in image size
  }

  FILE *fp = fopen(img_file, "rb");
  Assert(fp, "Can not open '%s'", img_file);

  fseek(fp, 0, SEEK_END);
  img_size = ftell(fp);

  spdlog::info("The image is {}, size = {}", img_file, img_size);

  fseek(fp, 0, SEEK_SET);
  int ret = fread(img, img_size, 1, fp);
  assert(ret == 1);

  fclose(fp);

  return img_size;
}

static void _init_flash() {
  memcpy(flash_data, img, img_size);
  // for debug
  // TODO: remove this
  memset(psram_data, 0xcc, sizeof(psram_data));
  sdram_data[0][0][0] = 0x1234;
  sdram_data[0][0][1] = 0x5678;
  sdram_data[0][0][2] = 0x9abc;
  sdram_data[0][0][3] = 0xdef0;
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

const char* _get_env_or_default(const char* env_name, const char* default_value) {
		const char* env_value = std::getenv(env_name);
		return env_value ? env_value : default_value;
}

void _init_dpi_logger() {
  auto formatter = std::make_unique<spdlog::pattern_formatter>();
  formatter->add_flag<sim_time_formatter>('&');
  // (sim_time) [DPI] [log_level] log_msg
  formatter->set_pattern("(%&) [%n] [%^%l%$] %v");

  auto out_file = "dpiout.log";
  auto con_lvl_str = _get_env_or_default("DPI_CONSOLE_LVL", "info");
  auto file_lvl_str = _get_env_or_default("DPI_FILE_LVL", "info");

  auto console_lvl = spdlog::level::from_str(con_lvl_str);
  auto file_lvl = spdlog::level::from_str(file_lvl_str);
  spdlog::info("DPI log lvl = {}, out file = {} lvl = {}", con_lvl_str,
               out_file, file_lvl_str);

  static auto console_sink =
      std::make_shared<spdlog::sinks::stdout_color_sink_mt>();
  static auto file_sink =
      std::make_shared<spdlog::sinks::basic_file_sink_mt>(out_file, true);

  console_sink->set_level(console_lvl);
  auto dup_console = std::make_shared<spdlog::sinks::dup_filter_sink_mt>(
      std::chrono::seconds(3));
  dup_console->add_sink(console_sink);
  dup_console->set_level(console_lvl);

  file_sink->set_level(file_lvl);
  auto dpi_sink_list = spdlog::sinks_init_list{dup_console, file_sink};
  _dpi_logger = std::make_shared<spdlog::logger>("DPI", dpi_sink_list);
  _dpi_logger->set_level(spdlog::level::trace);
  _dpi_logger->set_formatter(std::move(formatter));

  spdlog::register_logger(_dpi_logger);
}

bool sim_init(int argc, char **argv, sim_setting setting) {
  Verilated::commandArgs(argc, argv);
  sim_settings = setting;
#ifdef ENABLE_NVBOARD
  nvboard_bind_all_pins(&dut);
  nvboard_init();
#endif

  parse_args(argc, argv);
  using std::string;
  using namespace std::ranges;

  load_img();

  _init_flash();

  dbg_init(INITIAL_PC, img_size, img_file, setting);
  _init_dpi_logger();

#if ENABLE_WAVE
  if (setting.en_waveform) {
    Verilated::traceEverOn(true);
    tfp = std::shared_ptr<VerilatedFstC>(new VerilatedFstC,
                                         [](VerilatedFstC *p) { p->close(); });
    dut.trace(tfp.get(), 99);
    tfp->open(setting.wave_fst_file.c_str());
  }
#endif

  reset(10);

  if (batch_mode && !setting.no_batch) {
    dbg_exec("c");
    return dbg_is_hitbadtrap() ? 1 : 0;
  }

  return 0;
}

void sim_exec_sdbcmd(std::string_view cmd, bool &quit) { dbg_exec(cmd, &quit); }
