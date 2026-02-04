#include "sim.hpp"
#include "sdbWrap.hpp"

#include <cmath>
#include <spdlog/common.h>
#include <spdlog/logger.h>
#include <spdlog/mdc.h>
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

#include "spdlog/fmt/bundled/base.h"
#include "verilated_fst_c.h"

#if ENABLE_NVBOARD
#include <nvboard.h>
#endif

#include <getopt.h>
#include <unistd.h>
#include <vector>

#include "PerfCounter.hpp"

TOP_NAME dut;
sim_config sim_cfg;
sim_setting &sim_settings = sim_cfg.setting;

sim_cpu_state cpu;

TOP_NAME *get_dut() { return &dut; }

void nvboard_bind_all_pins(TOP_NAME *top);

typedef uint32_t word_t;
typedef uint32_t addr_t;

#define MADDR_BASE 0x20000000u

// #define TRACE_PMEM_CALL
// #define TRACE_SHOW_ALL_INST

std::shared_ptr<VerilatedFstC> tfp;

static uint64_t sim_time = 0;
static uint64_t cycle_count = 0;
static uint64_t inst_count = 0;

sim_time_t sim_get_time() { return sim_time; }
sim_cycle_t sim_get_cycle() { return cycle_count; }

static std::shared_ptr<spdlog::logger> _dpi_logger;

sim_config *sim_get_config() { return &sim_cfg; }
sim_cpu_state *sim_get_cpu_state() { return &cpu; }

uint64_t sim_get_inst_count() { return inst_count; }

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
  if (tfp) {
    tfp->dump(sim_time);
  }
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
	// fmt::println("cycle {}", cycle_count);

#if ENABLE_NVBOARD
  if (sim_settings.nvboard) {
    nvboard_update();
  }
#endif

  if (sim_settings.trace_clock_cycle) {
    printf("[Clock Cycle End]\n");
  }
  if (sim_settings.cycle_finish_cb) {
    sim_settings.cycle_finish_cb();
  }

  if (dut.reset == 0) {
    updatePerfCounters();
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

void raise_ebreak() {
  is_running = false;

	int a0 = cpu.gpr[10];

  // sbd_set_halt(a0);
  assert(sim_cfg.raise_halt_cb);
  sim_cfg.raise_halt_cb(a0);

  constexpr std::string_view fg_red = "\33[1;31m", fg_green = "\33[1;32m",
                             fg_yellow = "\33[1;33m", ansi_none = "\33[0m";

  is_good_trap = (a0 == 0);

  spdlog::info("{}HIT {} TRAP{} a0 = {} @pc = 0x{:08x} cyc {}",
               is_good_trap ? fg_green : fg_red, is_good_trap ? "GOOD" : "BAD",
               ansi_none, a0, cpu.pc, cycle_count);
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

#define _EXPAND(x) x
#define DPI_TRACE(fmt, ...)                                                    \
  do {                                                                         \
    auto _loger = spdlog::get(__func__);                                       \
    if (_loger)                                                                \
      _loger->trace(fmt, ##__VA_ARGS__);                                       \
  } while (0)

typedef bool (*mem_region_addr_mapper_tohost_t)(uint32_t addr,
                                                uint8_t *mapped_addr);
typedef bool (*mem_region_addr_mapper_toguest_t)(uint8_t *host_ptr,
                                                 uint32_t &addr);

typedef bool (*mem_region_read_guest_t)(uint32_t addr, uint32_t *data);
typedef bool (*mem_region_write_guest_t)(uint32_t addr, uint32_t data);

struct mem_region {
  uint32_t base;
  uint32_t end;

  const char *name;

  union {
    struct {
      mem_region_addr_mapper_tohost_t tohost;
      mem_region_read_guest_t read_guest;
      mem_region_write_guest_t write_guest;
      // mem_region_addr_mapper_toguest_t toguest;
    } mapper;
    struct {
      void *ptr;
      size_t size;
    } arr;
  };
  bool simple_map; // true: mapped_addr = addr - base + base_at_host
  bool enwrite;

  bool contains(uint32_t addr) const { return addr >= base && addr < end; }
  uint8_t *at_host(uint32_t addr) const {
    if (simple_map) {
      assert(addr - base < arr.size);
      return (uint8_t *)arr.ptr + (addr - base);
    } else {
      assert(mapper.tohost);
      uint8_t *mapped_addr = nullptr;
      bool ok = mapper.tohost(addr, mapped_addr);
      assert(ok);
      return mapped_addr;
    }
  }
  bool read_guest(uint32_t addr, uint32_t &data) const {
    if (simple_map) {
      // assert(addr - base < arr.size);
      if (addr - base >= arr.size) {
        spdlog::error("addr {:08x} at region {} is out of bound", addr, name);
        return false;
      }
      uint32_t *ptr = (uint32_t *)((uint8_t *)arr.ptr + (addr - base));
      data = *ptr;
      return true;
    } else {
      assert(mapper.read_guest);
      return mapper.read_guest(addr, &data);
    }
  }
  bool write_guest(uint32_t addr, uint32_t data) const {
    if (!enwrite) {
      spdlog::error("addr {:08x} at region {} is not writable", addr, name);
      return false;
    }
    if (simple_map) {
      assert(addr - base < arr.size);
      uint32_t *ptr = (uint32_t *)((uint8_t *)arr.ptr + (addr - base));
      *ptr = data;
      return true;
    } else {
      assert(mapper.write_guest);
      return mapper.write_guest(addr, data);
    }
  }
};

constexpr uint32_t MROM_BASE = 0x20000000u;
constexpr uint32_t MROM_END = 0x20010000u;
word_t mrom_data[(MROM_END - MROM_BASE) / 4];

constexpr uint32_t FLASH_BASE = 0x30000000u;
constexpr uint32_t FLASH_END = 0x40000000u;
uint32_t flash_data[sizeof(img) / 4];

constexpr uint32_t PSRAM_BASE = 0x80000000u;
constexpr uint32_t PSRAM_END = 0xA0000000u;
#if IS_SOC
uint32_t psram_data[8 * 1024 * 1024 / 4];
#else
// for npc
// psram act as pmem is 128MB
uint32_t psram_data[128 * 1024 * 1024 / 4];
#endif

extern "C" void uart_send(char ch) {
	putchar(ch);
	fflush(stdout);
}

extern "C" void mrom_read(int32_t addr, int32_t *data) {
  spdlog::mdc::put("testkey", "testvalue");
  if (addr < MROM_BASE) {
    _dpi_logger->error("addr={:08x} ERROR BELOW MROM_BASE", sim_time, addr);
  }
  assert(addr >= MROM_BASE);
  addr -= MROM_BASE;
  assert(addr < sizeof(img));
  addr &= ~0x3;
  uintptr_t ptr = (uintptr_t)img + addr;
  *data = *(int32_t *)ptr;

  DPI_TRACE("R addr={:08x} data={:08x}", addr + MROM_BASE, *data);
}

static void _copy_img();
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
  DPI_TRACE("R addr={:08x} data={:08x}", addr + FLASH_BASE, *data);
}

extern "C" void psram_read(int32_t addr, int32_t *data) {
  // in psram high 8bit addr are 0
  // no need to minus PSRAM_BASE
	if(addr >= sizeof(psram_data)){
		_dpi_logger->error("psram_read addr={:08x} out of bound", addr + PSRAM_BASE);
		return;
	}
  assert(addr < sizeof(psram_data));
  addr &= ~0x3;
  uintptr_t ptr = (uintptr_t)psram_data + addr;
  *data = *(int32_t *)ptr;
  DPI_TRACE("R addr={:08x} data={:08x}", addr + PSRAM_BASE, *data);
}
// compatible interface for npc core
extern "C" void pmem_read(int addr, int *data) {
	return psram_read(addr-PSRAM_BASE, data);
}
extern "C" void psram_write(int32_t addr, char strb8, int32_t data, int32_t *) {
	if(addr >= sizeof(psram_data)){
		_dpi_logger->error("psram_write addr={:08x} out of bound", addr + PSRAM_BASE);
	}
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

  DPI_TRACE("W addr={:08x} data={:08x} (strb {:02x}) newdata={:08x}",
            addr + PSRAM_BASE, (uint32_t)data, (uint32_t)strb8, *ptr);
}
// compatible interface for npc core
extern "C" void pmem_write(int addr, int data, int mask) {
	uint8_t unaligned_part = addr & 0x3;
	uint32_t udata = ((uint32_t)data) >> (unaligned_part * 8);
	uint8_t umask = (mask >> unaligned_part) & 0xf;
	return psram_write(addr-PSRAM_BASE, umask, udata, nullptr);
}

constexpr uint32_t SDRAM_BASE = 0xa0000000u;
constexpr uint32_t SDRAM_END = 0xb0000000u;

uint16_t sdram_data[4][8192][512][4];

extern "C" void sdram_read(char block, char bank, short row, short col,
                           short *data) {
  assert(bank >= 0 && bank < 4);
  assert(row >= 0 && row < 8192);
  assert(col >= 0 && col < 512);
  assert(block >= 0 && block < 4);
  *data = sdram_data[bank][row][col][block];
  DPI_TRACE("R bank={:02x} row={:04x} col={:04x} block={} data={:04x}", bank,
            row, col, (uint32_t)block, (uint16_t)*data);
}
extern "C" void sdram_write(char block, char bank, short row, short col,
                            short data, char mask) {
  assert(bank >= 0 && bank < 4);
  assert(row >= 0 && row < 8192);
  assert(col >= 0 && col < 512);
  // assert(block == 0 || block == 1);
  // mask [0] = 0: write low byte
  // mask [1] = 0: write high byte
  if ((mask & 0x1) == 0) {
    sdram_data[bank][row][col][block] &= 0xff00;
    sdram_data[bank][row][col][block] |= (data & 0x00ff);
    // _dpi_logger->trace("sdram_write low byte {:02x}", (uint8_t)(data &
    // 0x00ff));
  }
  if ((mask & 0x2) == 0) {
    sdram_data[bank][row][col][block] &= 0x00ff;
    sdram_data[bank][row][col][block] |= (data & 0xff00);
    // _dpi_logger->trace("sdram_write high byte {:02x}", (uint8_t)((data &
    // 0xff00) >> 8));
  }

  char human_friendly_mask[3] = {'-', '-', '\0'};
  if ((mask & 0x1) == 0)
    human_friendly_mask[1] = 'L';
  if ((mask & 0x2) == 0)
    human_friendly_mask[0] = 'H';

  DPI_TRACE("W bank={:02x} row={:04x} col={:04x} block={} "
            "data={:04x} mask={} newdata={:04x}",
            bank, row, col, (uint32_t)block, (uint16_t)data,
            human_friendly_mask, sdram_data[bank][row][col][block]);
}

struct sdram_u32_data_ptr {
  uint16_t *lowpart;
  uint16_t *highpart;
};
sdram_u32_data_ptr get_sdram_data_at(word_t addr) {
  word_t in_sdram_addr = addr - SDRAM_BASE;
  char raw_bank = (in_sdram_addr >> 10) & 0x7;
  uint16_t row = (in_sdram_addr >> 13) & 0x1fff;
  uint16_t col = (in_sdram_addr >> 1) & 0x1ff;
  uint8_t bank = raw_bank % 4;
  uint8_t block = (raw_bank & 0x4) ? 2 : 0;
  assert(bank < 4);
  assert(row < 8192);
  assert(col < 512);
  assert(block < 4);
  return {.lowpart = &sdram_data[bank][row][col][block],
          .highpart = &sdram_data[bank][row][col][block + 1]};
}

bool read_sdram(word_t addr, word_t *data) {
  sdram_u32_data_ptr ptrs = get_sdram_data_at(addr);
  *data = ((word_t)(*ptrs.highpart) << 16) | (word_t)(*ptrs.lowpart);
  return true;
}
bool write_sdram(word_t addr, word_t data) {
  sdram_u32_data_ptr ptrs = get_sdram_data_at(addr);
  *ptrs.lowpart = (uint16_t)(data & 0xffff);
  *ptrs.highpart = (uint16_t)((data >> 16) & 0xffff);
  return true;
}
constexpr uint32_t SRAM_BASE = 0x0f000000u;
constexpr uint32_t SRAM_END = 0x10000000u;

#define SIMPLE_REGION(name, base, end, array, enwrite)                         \
  {                                                                            \
    base, end, #name, {.arr = {.ptr = (void *)array, .size = sizeof(array)}},  \
        true, enwrite                                                          \
  }

mem_region mem_regions[] = {
    SIMPLE_REGION(mrom, MROM_BASE, MROM_END, mrom_data, false),
    SIMPLE_REGION(flash, FLASH_BASE, FLASH_END, flash_data, false),
    SIMPLE_REGION(psram, PSRAM_BASE, PSRAM_END, psram_data, true),
    {SDRAM_BASE,
     SDRAM_END,
     "sdram",
     {.mapper =
          {
              .tohost = nullptr,
              .read_guest = read_sdram,
              .write_guest = write_sdram,
          }},
     false,
     true},
};

uint8_t *sim_guest_to_host(uint32_t addr) {
  for (auto &r : mem_regions) {
    if (r.contains(addr)) {
      return r.at_host(addr);
    }
  }
  spdlog::error("sim_guest_to_host addr={:08x} no mapping region", addr);
  return nullptr;
}

extern "C" void gpr_upd(char regno, int data) {
  if (regno == 0)
    return;
  cpu.gpr[regno] = data;
}

bool pc_changed = false;
extern "C" void pc_upd(int pc, int npc) {
  pc_changed = true;
  cpu.pc = npc;
}

extern "C" void skip_difftest_ref() {
  if (sim_settings.trace_difftest_skip) {
    printf("[DPI] skip_difftest_ref called\n");
  }
  sdb_skip_difftest_ref();
}

bool sim_read_vmem(word_t addr, word_t *data) {
  for (auto &r : mem_regions) {
    if (r.contains(addr)) {
      return r.read_guest(addr, *data);
    }
  }
  if (SRAM_BASE <= addr && addr < SRAM_END) {
    // spdlog::warn("sim_read_vmem addr={:08x} in SRAM region, direct read not
    // allowed", addr);
    // TODO: handle sram read
    *data = 0;
  } else
    spdlog::warn("sim_read_vmem addr={:08x} no mapping region", addr);
  return false;
}
bool sim_write_vmem(word_t addr, word_t data) {
  for (auto &r : mem_regions) {
    if (r.contains(addr)) {
      return r.write_guest(addr, data);
    }
  }
  spdlog::warn("sim_write_vmem addr={:08x} no mapping region", addr);
  return false;
}

void sim_step_inst() {
  size_t cnt = 0;
  // SPI flash may need many cycles to respond
  constexpr size_t MAYBE_DEADLOOP_THRESHOLD = 8192*8;
  while (!pc_changed) {
    sim_step_cycle();
    if (sim_halted()) {
      cpu.pc += 4;
      inst_count++;
      return;
    }
    cnt++;
    if (cnt >= MAYBE_DEADLOOP_THRESHOLD) {
      if (!sim_settings.gdb_mode) {
        sdb_dump_recent_info();
      }
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
        spdlog::info("sim exit due to possible deadloop");
        exit(1);
      }
    }
  }
  pc_changed = false;
  inst_count++;
}

// IMG

static void load_img() {
#define Assert(expr, ...)                                                      \
  do {                                                                         \
    if (!(expr)) {                                                             \
      fprintf(stderr, __VA_ARGS__);                                            \
    }                                                                          \
  } while (0)

  if (sim_cfg.img_file_path == NULL) {
    spdlog::warn("No image is given. Use the default build-in image.");
    sim_cfg.img_size = 24;
    return;
  }

  FILE *fp = fopen(sim_cfg.img_file_path, "rb");
  Assert(fp, "Can not open '%s'", sim_cfg.img_file_path);

  fseek(fp, 0, SEEK_END);
  sim_cfg.img_size = ftell(fp);

  spdlog::info("load image {}, size = {}", sim_cfg.img_file_path,
               sim_cfg.img_size);

  fseek(fp, 0, SEEK_SET);
  int ret = fread(img, sim_cfg.img_size, 1, fp);
  assert(ret == 1);

  fclose(fp);
}

static void _copy_img() {
  if (is_soc()) {
    spdlog::info("copy img to flash for soc sim");
    memcpy(flash_data, img, sim_cfg.img_size);
  } else {
    spdlog::info("copy img to psram for cpu core sim");
    memcpy(psram_data, img, sim_cfg.img_size);
  }
}
static void _fill_rams_uninit() {
  if (sim_settings.zero_uninit_ram) {
    if (is_soc()) {
      memset(psram_data, 0, sizeof(psram_data));
    }
    memset(sdram_data, 0, sizeof(sdram_data));
  } else {
    if (is_soc()) {
      memset(psram_data, 0xcc, sizeof(psram_data));
      spdlog::debug("psram_data filled with 0xcc");
    }
    memset(sdram_data, 0xdd, sizeof(sdram_data));
    spdlog::debug("sdram_data filled with 0xdd");
  }
  spdlog::info("RAMs uninitialized area filled with {}",
               sim_settings.zero_uninit_ram ? "zeros" : "non-zero patterns");
}

// ARG

static void parse_args(int argc, char **argv) {
  const struct option table[] = {{"batch", no_argument, NULL, 'b'},
                                 {0, 0, NULL, 0}};
  int o;
  while ((o = getopt_long(argc, argv, "-b", table, NULL)) != -1) {
    switch (o) {
    case 'b':
      sim_cfg.hope_batch_mode = true;
      break;
    case 1:
      sim_cfg.img_file_path = optarg;
      break;
    default:
      printf("Bad option %c\n", o);
      exit(1);
    }
  }
}

const char *_get_env_or_default(const char *env_name,
                                const char *default_value) {
  const char *env_value = std::getenv(env_name);
  return env_value ? env_value : default_value;
}

static auto _gen_logger_formatter_with_simtime() {
  auto formatter = std::make_unique<spdlog::pattern_formatter>();
  formatter->add_flag<sim_time_formatter>('&');
  // (sim_time) [logger_name] [log_level] log_msg
  formatter->set_pattern("(%&) [%n] [%^%l%$] %v");
  return formatter;
}

void set_logger_pattern_with_simtime(std::shared_ptr<spdlog::logger> logger) {
  auto formatter = _gen_logger_formatter_with_simtime();
  logger->set_formatter(std::move(formatter));
}
void _init_dpi_logger() {
  auto formatter = _gen_logger_formatter_with_simtime();

  auto out_file = "dpiout.log";
  auto con_lvl_str = _get_env_or_default("DPI_CONSOLE_LVL", "info");
  auto file_lvl_str = _get_env_or_default("DPI_FILE_LVL", "info");

  auto console_lvl = spdlog::level::from_str(con_lvl_str);
  auto file_lvl = spdlog::level::from_str(file_lvl_str);
  spdlog::info("DPI logger out console lvl {}, out file '{}' lvl {}",
               con_lvl_str, out_file, file_lvl_str);

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

#define _REG_DPI_FUNC_LOGGER(func)                                             \
  do {                                                                         \
    auto func_logger = std::make_shared<spdlog::logger>(#func, dpi_sink_list); \
    auto lvl = sim_settings.TRACE_DPI_FLAG(func) ? spdlog::level::trace        \
                                                 : spdlog::level::info;        \
    spdlog::debug("DPI func '{}' logger lvl {}", #func,                        \
                  spdlog::level::to_string_view(lvl));                         \
    func_logger->set_level(lvl);                                               \
    func_logger->set_formatter(formatter->clone());                            \
    spdlog::register_logger(func_logger);                                      \
  } while (0)

  _REG_DPI_FUNC_LOGGER(mrom_read);
  _REG_DPI_FUNC_LOGGER(flash_read);
  _REG_DPI_FUNC_LOGGER(psram_read);
  _REG_DPI_FUNC_LOGGER(psram_write);
  _REG_DPI_FUNC_LOGGER(sdram_read);
  _REG_DPI_FUNC_LOGGER(sdram_write);

  _dpi_logger = std::make_shared<spdlog::logger>("DPI", dpi_sink_list);
  _dpi_logger->set_level(spdlog::level::trace);
  _dpi_logger->set_formatter(std::move(formatter));
  spdlog::register_logger(_dpi_logger);
}

bool sim_init(int argc, char **argv, sim_setting setting) {
  Verilated::commandArgs(argc, argv);
  sim_settings = setting;
#if ENABLE_NVBOARD
  if (setting.nvboard) {
    spdlog::info("initializing nvboard");
    nvboard_bind_all_pins(&dut);
    nvboard_init();
  }
#endif

  parse_args(argc, argv);

  using std::string;
  using namespace std::ranges;

  load_img();

  _copy_img();
  _fill_rams_uninit();
  _init_dpi_logger(); // should before dbg_init(which may preload data with func
                      // call dpis)
  if (setting.en_wave) {
    Verilated::traceEverOn(true);
    tfp = std::shared_ptr<VerilatedFstC>(new VerilatedFstC,
                                         [](VerilatedFstC *p) { p->close(); });
    dut.trace(tfp.get(), 99);
    tfp->open(setting.wave_fst_file.c_str());
    spdlog::info("wave enabled, output file: {}", setting.wave_fst_file);
  }

  constexpr int reset_cycles = 30;
  reset(reset_cycles);
  spdlog::info("sim reset done ({} cycles)", reset_cycles);

  cpu.pc = sim_cfg.init_pc;
  spdlog::info("set initial pc to {:08x}", cpu.pc);

  return true;
}
