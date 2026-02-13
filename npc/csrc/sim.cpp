#include "sim.hpp"
#include "sdbWrap.hpp"

#include <algorithm>
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
#include <sys/types.h>
#include <variant>

#include "spdlog/fmt/bundled/base.h"
#include "verilated_fst_c.h"
#include "vsrc.hpp"

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
#define DPI_LOG(lvl, fmt, ...)                                                 \
  do {                                                                         \
    auto _loger = spdlog::get(__func__);                                       \
    if (_loger)                                                                \
      _loger->lvl(fmt, ##__VA_ARGS__);                                         \
  } while (0)
#define DPI_TRACE(fmt, ...) DPI_LOG(trace, fmt, ##__VA_ARGS__)
#define DPI_ERROR(fmt, ...) DPI_LOG(error, fmt, ##__VA_ARGS__)
#define DPI_CRITICAL(fmt, ...) DPI_LOG(critical, fmt, ##__VA_ARGS__)

#define DPI_ASSERT(cond, fmt, ...)                                             \
  do {                                                                         \
    if (!(cond)) {                                                             \
      DPI_CRITICAL(fmt, ##__VA_ARGS__);                                        \
      assert(cond);                                                            \
    }                                                                          \
  } while (0)

struct mem_region_traits {
  const uint32_t _Base;
  const uint32_t _End;
  std::string_view name;

  mem_region_traits(uint32_t base, uint32_t end, std::string_view name)
      : _Base(base), _End(end), name(name) {
    assert(end > base && "end should be greater than base");
  }

  constexpr bool contains(uint32_t addr) const {
    return addr >= _Base && addr < _End;
  }
  constexpr uint32_t base() const { return _Base; }

  void assert_in_range(uint32_t addr) const {
    bool in_range = contains(addr);
    if (!in_range) {
      auto logger = spdlog::get(name.data());
      if (logger) {
        logger->error("addr {:08x} out of bound for region {} [{:08x}, {:08x})",
                      addr, name, _Base, _End);
      }
    }
    assert(in_range);
  }
};

struct direct_mapped_mem : public mem_region_traits {
  const uint32_t _ActualSizeInBytes;

  using _MemContainerType = std::vector<uint32_t>;
  std::shared_ptr<_MemContainerType> mem_container;
  uint32_t *data;

  direct_mapped_mem(uint32_t base, uint32_t end, std::string_view name,
                    uint32_t actual_size = 0)
      : mem_region_traits(base, end, name),
        _ActualSizeInBytes(actual_size ? actual_size : (end - base)) {
    assert(_ActualSizeInBytes <= (end - base) &&
           "actual size should not exceed the address range");
    assert(_ActualSizeInBytes % 4 == 0 && "size should be multiple of 4");
    mem_container = std::make_shared<_MemContainerType>(_ActualSizeInBytes / 4);
    data = mem_container->data();
  }

  void assert_in_actual_data_range(uint32_t addr) const {
    size_t offset = addr - _Base;
    if (offset >= _ActualSizeInBytes) {
      auto logger = spdlog::get(this->name.data());
      if (logger) {
        logger->error("addr {:08x} out of actual data bound for region {} "
                      "[0, {:08x})",
                      addr, this->name, _ActualSizeInBytes);
      }
    }
    assert(offset < _ActualSizeInBytes);
  }

  void copy_from(void *src, size_t siz) { memcpy(data, src, siz); }
  void memset_data(uint8_t val) { memset(data, val, _ActualSizeInBytes); }

  uint8_t *get_data_ptr_at(uint32_t addr) {
    assert_in_range(addr);
    assert_in_actual_data_range(addr);
    return (uint8_t *)&data[(addr - _Base) / 4] + (addr & 0x3);
  }

  bool read_word(uint32_t addr, uint32_t &value) {
    assert_in_range(addr);
    assert_in_actual_data_range(addr);
    value = data[(addr - _Base) / 4]; // word aligned
    return true;
    // TODO: ret false instead of assert
  }
  bool write_word(uint32_t addr, uint32_t value) {
    assert_in_range(addr);
    assert_in_actual_data_range(addr);
    data[(addr - _Base) / 4] = value;
    return true;
    // TODO: ret false instead of assert
  }

  void write_word(uint32_t addr, uint32_t value, uint8_t strb8) {
    assert_in_range(addr);
    assert_in_actual_data_range(addr);
    uint8_t shift = (addr & 0x3) * 8;
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
    uint32_t shData = value << shift;

    uint32_t &ref = data[(addr - _Base) / 4];
    ref &= ~shMask;
    ref |= (shData & shMask);
  }
};

constexpr uint32_t SDRAM_BASE = 0xa0000000u;
constexpr uint32_t SDRAM_END = 0xb0000000u;

bool read_sdram(word_t addr, word_t *data);
bool write_sdram(word_t addr, word_t data);

struct sdram_mem : public mem_region_traits {
  sdram_mem(std::string_view name)
      : mem_region_traits(SDRAM_BASE, SDRAM_END, name) {}

  uint8_t *get_data_ptr_at(uint32_t addr) {
    assert_in_range(addr);
    spdlog::error("sdram region does not support direct data pointer access");
    return nullptr;
  }
  bool read_word(uint32_t addr, uint32_t &value) {
    assert_in_range(addr);
    return read_sdram(addr, &value);
  }
  bool write_word(uint32_t addr, uint32_t data) {
    assert_in_range(addr);
    return write_sdram(addr, data);
  }
};

struct {
  direct_mapped_mem mrom = {0x20000000u, 0x20010000u, "mrom"};
  direct_mapped_mem flash = {0x30000000u, 0x40000000u, "flash", sizeof(img)};
  direct_mapped_mem psram = {0x80000000u, 0xa0000000u, "psram",
                             128 * 1024 * 1024};
  direct_mapped_mem sram = {0x0f000000u, 0x10000000u, "sram", 8 * 1024};

  sdram_mem sdram = {"sdram"};
} g_sim_mem;

using mem_region_t = std::variant<direct_mapped_mem, sdram_mem>;
std::vector<mem_region_t> mem_regions = {g_sim_mem.mrom, g_sim_mem.flash,
                                         g_sim_mem.psram, g_sim_mem.sram,
                                         g_sim_mem.sdram};

extern "C" void uart_send(char ch) {
  putchar(ch);
  fflush(stdout);
}

extern "C" void mrom_read(int32_t addr, int32_t *data) {
  g_sim_mem.mrom.read_word(addr, (uint32_t &)(*data));
  DPI_TRACE("R addr={:08x} data={:08x}", addr, *data);
}

static void _copy_img();
extern "C" void flash_read(int32_t addr, int32_t *data) {
  // in spi
  //   .addr({8'b0, in_paddr[23:2], 2'b0}),
  // so the high 8 bits are ignored
  // 0x3XXXXXXX -> 0x00XXXXXX

  DPI_ASSERT((addr & 0xff000000) == 0,
             "flash_read addr={:08x} has non-zero high 8 bits", addr);

  // add back
  addr += g_sim_mem.flash.base();

  g_sim_mem.flash.read_word(addr, (uint32_t &)(*data));
  DPI_TRACE("R addr={:08x} data={:08x}", addr, *data);
}

extern "C" void psram_read(int32_t addr, int32_t *data) {
  // in psram high 8bit addr are 0
  // no need to minus PSRAM_BASE

  DPI_ASSERT((addr & 0xff000000) == 0,
             "psram_read addr={:08x} has non-zero high 8 bits", addr);

  // add back
  addr += g_sim_mem.psram.base();

  g_sim_mem.psram.read_word(addr, (uint32_t &)(*data));

  // addr &= ~0x3;
  DPI_TRACE("R addr={:08x} data={:08x}", addr, *data);
}
// compatible interface for npc core
extern "C" void pmem_read(int addr, int *data) {
  return psram_read(addr - g_sim_mem.psram.base(), data);
}
extern "C" void psram_write(int32_t addr, char strb8, int32_t data, int32_t *) {
  DPI_ASSERT((addr & 0xff000000) == 0,
             "psram_write addr={:08x} has non-zero high 8 bits", addr);
  // add back
  addr += g_sim_mem.psram.base();
  g_sim_mem.psram.write_word(addr, data, strb8);
  uint32_t newdata;
  g_sim_mem.psram.read_word(addr, newdata);

  DPI_TRACE("W addr={:08x} data={:08x} (strb {:02x}) newdata={:08x}", addr,
            (uint32_t)data, (uint32_t)strb8, newdata);
}
// compatible interface for npc core
extern "C" void pmem_write(int addr, int data, int mask) {
  uint8_t unaligned_part = addr & 0x3;
  uint32_t udata = ((uint32_t)data) >> (unaligned_part * 8);
  uint8_t umask = (mask >> unaligned_part) & 0xf;
  return psram_write(addr - g_sim_mem.psram.base(), umask, udata, nullptr);
}

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

uint8_t *sim_guest_to_host(uint32_t addr) {
  for (auto &r : mem_regions) {
    auto res = std::visit(
        [&](auto &region) -> uint8_t * {
          if (region.contains(addr)) {
            return region.get_data_ptr_at(addr);
          }
          return nullptr;
        },
        r);
    if (res) {
      return res;
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
  // printf("[DPI] pc_upd called, pc=0x%08x npc=0x%08x\n", pc, npc);
}

extern "C" void sram_upd(int addr, int data, char mask){
	g_sim_mem.sram.write_word(addr, data, mask);
}

extern "C" void skip_difftest_ref() {
  if (sim_settings.trace_difftest_skip) {
    printf("(%ld)[DPI] skip_difftest_ref called\n", sim_time);
  }
  sdb_skip_difftest_ref();
}

bool sim_read_vmem(word_t addr, word_t *data) {
  bool ok = std::ranges::any_of(mem_regions, [&](auto &v) {
    return std::visit(
        [&](auto &r) { return r.contains(addr) && r.read_word(addr, *data); },
        v);
  });
  if (!ok)
    spdlog::warn("sim_read_vmem addr={:08x} no mapping region or read failed",
                 addr);
  return ok;
}
bool sim_write_vmem(word_t addr, word_t data) {
  bool ok = std::ranges::any_of(mem_regions, [&](auto &v) {
    return std::visit(
        [&](auto &r) { return r.contains(addr) && r.write_word(addr, data); },
        v);
  });
  if (!ok)
    spdlog::warn("sim_write_vmem addr={:08x} no mapping region or write failed",
                 addr);
  return ok;
}

void sim_step_inst() {
  size_t cnt = 0;
  // SPI flash may need many cycles to respond
  constexpr size_t MAYBE_DEADLOOP_THRESHOLD = 8192 * 2;
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
    // memcpy(flash_data, img, sim_cfg.img_size);
    g_sim_mem.flash.copy_from(img, sim_cfg.img_size);
  } else {
    spdlog::info("copy img to psram for cpu core sim");
    // memcpy(psram_data, img, sim_cfg.img_size);
    g_sim_mem.psram.copy_from(img, sim_cfg.img_size);
  }
}
static void _fill_rams_uninit() {
  if (sim_settings.zero_uninit_ram) {
    if (is_soc()) {
      g_sim_mem.psram.memset_data(0);
    }
    memset(sdram_data, 0, sizeof(sdram_data));
  } else {
    if (is_soc()) {
      g_sim_mem.psram.memset_data(0xcc);
      spdlog::debug("psram_data filled with 0xcc");
    }
    memset(sdram_data, 0xdd, sizeof(sdram_data));
    spdlog::debug("sdram_data filled with 0xdd");
  }
  spdlog::info("RAMs uninitialized area filled with {}",
               sim_settings.zero_uninit_ram ? "zeros" : "non-zero patterns");

  if (!is_soc()) {
    // memset the ASAN shadow memory to zero
    // instead at trm_init to optimise the init time
#define ASAN_SHADOW_MEMORY_START 0x7000000
#define ASAN_SHADOW_MEMORY_SIZE 0x1000000

    auto psram_data = g_sim_mem.psram.data;
    memset((uint8_t *)psram_data + ASAN_SHADOW_MEMORY_START, 0,
           ASAN_SHADOW_MEMORY_SIZE);
    spdlog::info("ASAN shadow memory in psram zeroed");
  }
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
void _init_mem_logger() {
  auto formatter = _gen_logger_formatter_with_simtime();
  auto lvl = spdlog::level::info;

#define _REG_MEM_REGION_LOGGER(name)                                           \
  do {                                                                         \
    spdlog::debug("Registering logger for mem region '{}'", #name);            \
    auto logger =                                                              \
        std::make_shared<spdlog::logger>(#name, spdlog::sinks_init_list{});    \
    logger->set_level(lvl);                                                    \
    logger->set_formatter(formatter->clone());                                 \
    spdlog::register_logger(logger);                                           \
  } while (0)
  _REG_MEM_REGION_LOGGER(mrom);
  _REG_MEM_REGION_LOGGER(flash);
  _REG_MEM_REGION_LOGGER(psram);
  _REG_MEM_REGION_LOGGER(sram);
  _REG_MEM_REGION_LOGGER(sdram);
}
void _init_dpi_logger() {
  auto formatter = _gen_logger_formatter_with_simtime();

  auto out_file_name = "dpi";
  auto con_lvl_str = _get_env_or_default("DPI_CONSOLE_LVL", "info");
  auto file_lvl_str = _get_env_or_default("DPI_FILE_LVL", "info");

  auto console_lvl = spdlog::level::from_str(con_lvl_str);
  auto file_lvl = spdlog::level::from_str(file_lvl_str);
  spdlog::info("DPI logger out console lvl {}, out file '{}.log' lvl {}",
               con_lvl_str, out_file_name, file_lvl_str);

  static auto console_sink =
      std::make_shared<spdlog::sinks::stdout_color_sink_mt>();
  static auto file_sink = newFileLoggerSink(out_file_name);

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

  _init_mem_logger();

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
