#include <common.h>
#include <profile.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define PHASE_LEN 16000000ull
#define MAX_PHASES 128
#define PC_SLOTS (1u << 20)
#define PAIR_SLOTS (1u << 16)
#define DCACHE_LINES 512
#define DCACHE_PROBE_MAX_ENTRIES 4096
#define ENABLE_CAPACITY_PROBES 0

enum Kind {
  K_ALU,
  K_BRANCH,
  K_JAL,
  K_JALR,
  K_LOAD,
  K_STORE,
  K_M,
  K_B,
  K_CRC,
  K_SYSTEM,
  K_OTHER,
  K_NR
};
enum MOp {
  M_MUL,
  M_MULH,
  M_MULHSU,
  M_MULHU,
  M_DIV,
  M_DIVU,
  M_REM,
  M_REMU,
  M_NR
};
enum BOp {
  B_CLZ,
  B_CTZ,
  B_CPOP,
  B_SEXTB,
  B_SEXTH,
  B_CLMUL,
  B_CLMULR,
  B_CLMULH,
  B_ORCB,
  B_REV8,
  B_ANDN,
  B_ORN,
  B_XNOR,
  B_MIN,
  B_MAX,
  B_MINU,
  B_MAXU,
  B_SH1ADD,
  B_SH2ADD,
  B_SH3ADD,
  B_BSET,
  B_BCLR,
  B_BINV,
  B_BEXT,
  B_XPERM4,
  B_ROL,
  B_ROR,
  B_RORI,
  B_PACK,
  B_OTHER,
  B_NR
};
enum MemWidth { W_BYTE, W_HALF, W_WORD, W_OTHER, W_NR };
enum CrcOp { CRC_U8, CRC_U16, CRC_U32, CRC_NR };

typedef struct {
  uint32_t pc;
  uint64_t n, taken;
} PcStat;
typedef struct {
  uint64_t inst, control, taken, load, store, raw;
  uint64_t current_bypass, proposed_bypass, incremental_bypass, lost_bypass;
  uint64_t proposed_bypass_by_width[W_NR];
  uint64_t late_load_branch_eligible, late_load_branch_hit;
  uint64_t rtl_btb32_miss, rtl_btb32_skip_not_taken_miss;
} Phase;
typedef struct {
  uint32_t tag, target;
  uint8_t valid, type, counter;
} BtbEnt;
typedef struct {
  uint8_t tag, valid;
} DCacheEnt;
typedef struct {
  uint64_t load_access[W_NR];
  uint64_t load_hit[W_NR];
  uint64_t load_miss[W_NR];
  uint64_t full_store_allocate;
  uint64_t partial_store_total;
  uint64_t partial_store_hit_update;
  uint64_t partial_store_invalidate;
} DCacheStat;
typedef struct {
  uint32_t tag;
  uint8_t valid, lru;
} AssocDCacheEnt;
typedef struct {
  unsigned sets, ways;
  AssocDCacheEnt entries[DCACHE_PROBE_MAX_ENTRIES];
  DCacheStat stat;
} AssocDCache;
typedef struct {
  uint32_t producer_pc, consumer_pc;
  uint64_t count, eligible, current_hit, proposed_hit;
  uint64_t incremental_hit, lost_hit;
  uint64_t assoc_hit[3];
  uint64_t source[3];
  uint8_t width, consumer, used;
} LoadPairStat;
typedef struct {
  uint32_t pc;
  uint8_t rd, width, current_hit, rtl_hit, proposed_hit, assoc_hit[3];
  uint8_t d1_state_diff, valid;
} PreviousLoad;

static const char *kind_names[] = {
    "alu", "branch", "jal", "jalr", "load", "store", "m", "b",
    "crc", "system", "other"};
static const char *width_names[] = {"byte", "half", "word", "other"};

static const char *out_path;
static uint64_t total, kinds[K_NR], mops[M_NR], bops[B_NR], crcops[CRC_NR],
    raw_dist[9];
static uint64_t xaccel_ops[6], xaccel_units[6], xaccel_cycles[6];
static uint64_t xdfacnt_ops[6];
static uint64_t pack_rs2_zero, pack_rs1_upper_zero, pack_matches_xor;
static uint64_t miss_consumer_successor_deps;
static uint64_t m_divide_by_zero, m_signed_overflow;
static uint64_t prod_cons[K_NR][K_NR];
static uint64_t widths[W_NR], regions[5], aliases[512], jalr_rd[3], jalr_rs1[3];
static uint64_t load_raw[W_NR][K_NR];
static uint64_t load_raw_source[W_NR][3];
static uint64_t load_raw_eligible[W_NR][K_NR];
static uint64_t load_raw_current_hit[W_NR][K_NR];
static uint64_t load_raw_proposed_hit[W_NR][K_NR];
static uint64_t load_raw_incremental_hit[W_NR][K_NR];
static uint64_t load_raw_lost_hit[W_NR][K_NR];
static uint64_t load_m_raw[W_NR][M_NR][3];
static uint64_t load_m_raw_eligible[W_NR][M_NR][3];
static uint64_t load_m_raw_current_hit[W_NR][M_NR][3];
static uint64_t load_m_raw_proposed_hit[W_NR][M_NR][3];
static uint64_t load_branch_raw[W_NR][8][3];
static uint64_t load_branch_eligible[W_NR][8][3];
static uint64_t load_branch_current_hit[W_NR][8][3];
static uint64_t load_branch_proposed_hit[W_NR][8][3];
/* Isolate masked partial-store updates from the already-retained narrow-load
 * eligibility change.  Distance 1 uses the exact restricted late-load
 * consumer forms; distance 2 is the generic LSU-cache forwarding opportunity.
 */
static uint64_t masked_store_baseline_hit[2][W_NR];
static uint64_t masked_store_candidate_hit[2][W_NR];
static uint64_t masked_store_incremental_hit[2][W_NR];
static uint64_t masked_store_lost_hit[2][W_NR];
static uint64_t masked_store_baseline_miss_successor_deps;
static uint64_t masked_store_candidate_miss_successor_deps;
static Phase phases[MAX_PHASES];
static PcStat *pcs;
static LoadPairStat *load_pairs;
static uint64_t pair_table_full;
static uint64_t btb16_miss, btb32_miss, btb16_jalr_miss,
                btb32_jalr_miss, btb32_stored_jalr_not_predicted_miss,
                btb64_miss, btb128_miss;
static uint64_t rtl_btb32_miss, rtl_btb32_skip_not_taken_miss;
static uint64_t branch_total, branch_taken, branch_backward;
static uint64_t branch_backward_taken, branch_backward_not_taken;
static uint64_t branch_forward_taken, branch_forward_not_taken;
static uint64_t branch_static_backward_miss, branch_static_not_taken_miss;
static uint64_t branch_btb32_miss_backward_taken;
static BtbEnt btb16[16], btb32[32], btb16j[16], btb32j[32],
    btb32_stored_jalr_not_predicted[32], btb64[64], btb128[128],
    rtl_btb32[32], rtl_btb32_skip_not_taken[32];
static DCacheEnt current_dcache[DCACHE_LINES], rtl_dcache[DCACHE_LINES],
    proposed_dcache[DCACHE_LINES];
static DCacheStat current_dcache_stat, rtl_dcache_stat, proposed_dcache_stat;
static AssocDCache dcache_2way = {.sets = 256, .ways = 2};
static AssocDCache dcache_4way = {.sets = 128, .ways = 4};
static AssocDCache dcache_8way = {.sets = 64, .ways = 8};
#if ENABLE_CAPACITY_PROBES
static AssocDCache dcache_1024 = {.sets = 1024, .ways = 1};
static AssocDCache dcache_2048 = {.sets = 2048, .ways = 1};
static AssocDCache dcache_4096 = {.sets = 4096, .ways = 1};
#endif
static PreviousLoad previous_load, distance2_load;
static uint64_t last_write[32];
static uint8_t last_kind[32];
static uint8_t previous_miss_consumer_rd;
static uint8_t masked_baseline_miss_consumer_rd;
static uint8_t masked_candidate_miss_consumer_rd;

static int32_t branch_imm(uint32_t x) {
  uint32_t imm = ((x >> 31) << 12) | (((x >> 7) & 1) << 11) |
                 (((x >> 25) & 0x3f) << 5) | (((x >> 8) & 0xf) << 1);
  return (int32_t)(imm << 19) >> 19;
}

void riscv_profile_set_output(const char *path) {
  out_path = path;
  if (path && !pcs)
    pcs = calloc(PC_SLOTS, sizeof(*pcs));
  if (path && !load_pairs)
    load_pairs = calloc(PAIR_SLOTS, sizeof(*load_pairs));
}
bool riscv_profile_enabled(void) { return out_path != NULL; }

void riscv_profile_record_xaccel(unsigned op, uint64_t units,
                                 uint64_t modeled_cycles) {
  if (!out_path || op >= 6)
    return;
  xaccel_ops[op]++;
  xaccel_units[op] += units;
  xaccel_cycles[op] += modeled_cycles;
}

void riscv_profile_record_xdfacnt(unsigned op) {
  if (out_path && op < 6)
    xdfacnt_ops[op]++;
}

static unsigned kind_of(uint32_t x) {
  unsigned op = x & 0x7f, f3 = (x >> 12) & 7, f7 = x >> 25;
  if (op == 0x63)
    return K_BRANCH;
  if (op == 0x6f)
    return K_JAL;
  if (op == 0x67)
    return K_JALR;
  if (op == 0x03)
    return K_LOAD;
  if (op == 0x23)
    return K_STORE;
  if (op == 0x0b && f7 == 0 && f3 < CRC_NR)
    return K_CRC;
  if (op == 0x73)
    return K_SYSTEM;
  if (op == 0x33 && f7 == 1)
    return K_M;
  if (op == 0x33 && f7 == 0x04 && ((x >> 12) & 7) == 4)
    return K_B;
  bool b_register =
      op == 0x33 &&
      ((f7 == 0x05 && (f3 <= 3 || f3 >= 4)) ||
       (f7 == 0x10 && (f3 == 2 || f3 == 4 || f3 == 6)) ||
       (f7 == 0x20 && (f3 == 4 || f3 == 6 || f3 == 7)) ||
       (f7 == 0x30 && (f3 == 1 || f3 == 5)) ||
       (f7 == 0x24 && (f3 == 1 || f3 == 5)) ||
       (f7 == 0x14 && (f3 == 1 || f3 == 2)) ||
       (f7 == 0x34 && f3 == 1));
  bool b_immediate =
      op == 0x13 && (f3 == 1 || f3 == 5) &&
      (f7 == 0x05 || f7 == 0x30 || f7 == 0x24 || f7 == 0x14 ||
       f7 == 0x34);
  if (b_register || b_immediate)
    return K_B;
  if (op == 0x33 || op == 0x13 || op == 0x37 || op == 0x17)
    return K_ALU;
  return K_OTHER;
}
static unsigned mop_of(uint32_t x) { return (x >> 12) & 7; }
static unsigned bop_of(uint32_t x) {
  unsigned op = x & 0x7f, f3 = (x >> 12) & 7, f7 = x >> 25,
           rs2 = (x >> 20) & 31;
  if (op == 0x13 && f3 == 1 && f7 == 0x30) {
    if (rs2 == 0)
      return B_CLZ;
    if (rs2 == 1)
      return B_CTZ;
    if (rs2 == 2)
      return B_CPOP;
    if (rs2 == 4)
      return B_SEXTB;
    if (rs2 == 5)
      return B_SEXTH;
  }
  if (op == 0x33 && f7 == 0x05 && f3 == 1)
    return B_CLMUL;
  if (op == 0x33 && f7 == 0x05 && f3 == 2)
    return B_CLMULR;
  if (op == 0x33 && f7 == 0x05 && f3 == 3)
    return B_CLMULH;
  if (op == 0x13 && f3 == 5 && f7 == 0x14 && rs2 == 7)
    return B_ORCB;
  if (op == 0x13 && f3 == 5 && f7 == 0x34 && rs2 == 24)
    return B_REV8;
  if (op == 0x33 && f7 == 0x20 && f3 == 4)
    return B_XNOR;
  if (op == 0x33 && f7 == 0x20 && f3 == 6)
    return B_ORN;
  if (op == 0x33 && f7 == 0x20 && f3 == 7)
    return B_ANDN;
  if (op == 0x33 && f7 == 0x05 && f3 == 4)
    return B_MIN;
  if (op == 0x33 && f7 == 0x05 && f3 == 5)
    return B_MAX;
  if (op == 0x33 && f7 == 0x05 && f3 == 6)
    return B_MINU;
  if (op == 0x33 && f7 == 0x05 && f3 == 7)
    return B_MAXU;
  if (op == 0x33 && f7 == 0x10 && f3 == 2)
    return B_SH1ADD;
  if (op == 0x33 && f7 == 0x10 && f3 == 4)
    return B_SH2ADD;
  if (op == 0x33 && f7 == 0x10 && f3 == 6)
    return B_SH3ADD;
  if ((op == 0x33 || op == 0x13) && f7 == 0x14 && f3 == 1)
    return B_BSET;
  if ((op == 0x33 || op == 0x13) && f7 == 0x24 && f3 == 1)
    return B_BCLR;
  if ((op == 0x33 || op == 0x13) && f7 == 0x34 && f3 == 1)
    return B_BINV;
  if ((op == 0x33 || op == 0x13) && f7 == 0x24 && f3 == 5)
    return B_BEXT;
  if (op == 0x33 && f7 == 0x14 && f3 == 2)
    return B_XPERM4;
  if (op == 0x33 && f7 == 0x30 && f3 == 1)
    return B_ROL;
  if (op == 0x33 && f7 == 0x30 && f3 == 5)
    return B_ROR;
  if (op == 0x13 && f7 == 0x30 && f3 == 5)
    return B_RORI;
  if (op == 0x33 && f7 == 0x04 && f3 == 4)
    return B_PACK;
  return B_OTHER;
}

static bool is_late_load_consumer(uint32_t x) {
  unsigned op = x & 0x7f, f3 = (x >> 12) & 7, f7 = x >> 25;
  uint32_t imm12 = x >> 20;
  bool add = f3 == 0 && (op == 0x13 || (op == 0x33 && f7 == 0));
  bool andi1 = op == 0x13 && f3 == 7 && imm12 == 1;
  bool srli1 = op == 0x13 && f3 == 5 && imm12 == 1;
  return add || andi1 || srli1;
}
static int32_t sext(uint32_t x, unsigned n) {
  return (int32_t)(x << (32 - n)) >> (32 - n);
}
static unsigned memory_width(uint32_t x, bool load) {
  unsigned f3 = (x >> 12) & 7;
  if (f3 == 0 || (load && f3 == 4))
    return W_BYTE;
  if (f3 == 1 || (load && f3 == 5))
    return W_HALF;
  if (f3 == 2)
    return W_WORD;
  return W_OTHER;
}
static bool aligned_for_width(uint32_t addr, unsigned width) {
  if (width == W_BYTE)
    return true;
  if (width == W_HALF)
    return (addr & 1) == 0;
  if (width == W_WORD)
    return (addr & 3) == 0;
  return false;
}
static bool dcacheable(uint32_t addr) { return ((addr >> 20) & 3) == 1; }
static unsigned dcache_index(uint32_t addr) {
  return (addr >> 2) & (DCACHE_LINES - 1);
}
static uint8_t dcache_tag(uint32_t addr) { return (addr >> 11) & 0x7f; }
static bool dcache_hit(DCacheEnt *cache, uint32_t addr) {
  DCacheEnt *entry = &cache[dcache_index(addr)];
  return entry->valid && entry->tag == dcache_tag(addr);
}
static void dcache_allocate(DCacheEnt *cache, uint32_t addr) {
  DCacheEnt *entry = &cache[dcache_index(addr)];
  entry->valid = 1;
  entry->tag = dcache_tag(addr);
}
static bool dcache_load(DCacheEnt *cache, DCacheStat *stat, uint32_t addr,
                        unsigned width, bool eligible) {
  if (!eligible)
    return false;
  stat->load_access[width]++;
  bool hit = dcache_hit(cache, addr);
  if (hit)
    stat->load_hit[width]++;
  else {
    stat->load_miss[width]++;
    dcache_allocate(cache, addr);
  }
  return hit;
}
static void current_dcache_store(uint32_t addr, unsigned width) {
  if (!dcacheable(addr))
    return;
  if (width == W_WORD) {
    current_dcache_stat.full_store_allocate++;
    dcache_allocate(current_dcache, addr);
  } else {
    current_dcache_stat.partial_store_total++;
    current_dcache_stat.partial_store_invalidate++;
    current_dcache[dcache_index(addr)].valid = 0;
  }
}
static void rtl_dcache_store(uint32_t addr, unsigned width) {
  if (!dcacheable(addr))
    return;
  if (width == W_WORD) {
    rtl_dcache_stat.full_store_allocate++;
    dcache_allocate(rtl_dcache, addr);
  } else {
    rtl_dcache_stat.partial_store_total++;
    rtl_dcache_stat.partial_store_invalidate++;
    rtl_dcache[dcache_index(addr)].valid = 0;
  }
}
static void proposed_dcache_store(uint32_t addr, unsigned width) {
  if (!dcacheable(addr))
    return;
  if (width == W_WORD) {
    proposed_dcache_stat.full_store_allocate++;
    dcache_allocate(proposed_dcache, addr);
  } else if (dcache_hit(proposed_dcache, addr)) {
    proposed_dcache_stat.partial_store_total++;
    proposed_dcache_stat.partial_store_hit_update++;
  } else {
    proposed_dcache_stat.partial_store_total++;
    proposed_dcache_stat.partial_store_invalidate++;
    proposed_dcache[dcache_index(addr)].valid = 0;
  }
}

static unsigned assoc_set(const AssocDCache *cache, uint32_t addr) {
  return (addr >> 2) % cache->sets;
}
static uint32_t assoc_tag(const AssocDCache *cache, uint32_t addr) {
  return (addr >> 2) / cache->sets;
}
static AssocDCacheEnt *assoc_entry(AssocDCache *cache, unsigned set,
                                   unsigned way) {
  return &cache->entries[set * cache->ways + way];
}
static AssocDCacheEnt *assoc_find(AssocDCache *cache, uint32_t addr) {
  unsigned set = assoc_set(cache, addr);
  uint32_t tag = assoc_tag(cache, addr);
  for (unsigned way = 0; way < cache->ways; way++) {
    AssocDCacheEnt *entry = assoc_entry(cache, set, way);
    if (entry->valid && entry->tag == tag)
      return entry;
  }
  return NULL;
}
static AssocDCacheEnt *assoc_victim(AssocDCache *cache, uint32_t addr) {
  unsigned set = assoc_set(cache, addr);
  AssocDCacheEnt *victim = assoc_entry(cache, set, 0);
  for (unsigned way = 0; way < cache->ways; way++) {
    AssocDCacheEnt *entry = assoc_entry(cache, set, way);
    if (!entry->valid)
      return entry;
    if (entry->lru > victim->lru)
      victim = entry;
  }
  return victim;
}
static void assoc_touch(AssocDCache *cache, uint32_t addr,
                        AssocDCacheEnt *selected) {
  unsigned set = assoc_set(cache, addr);
  unsigned old_lru = selected->lru;
  for (unsigned way = 0; way < cache->ways; way++) {
    AssocDCacheEnt *entry = assoc_entry(cache, set, way);
    if (entry != selected && entry->valid && entry->lru < old_lru)
      entry->lru++;
  }
  selected->lru = 0;
}
static void assoc_allocate(AssocDCache *cache, uint32_t addr) {
  AssocDCacheEnt *entry = assoc_find(cache, addr);
  if (!entry)
    entry = assoc_victim(cache, addr);
  entry->valid = 1;
  entry->tag = assoc_tag(cache, addr);
  assoc_touch(cache, addr, entry);
}
static bool assoc_load(AssocDCache *cache, uint32_t addr, unsigned width,
                       bool eligible) {
  if (!eligible)
    return false;
  cache->stat.load_access[width]++;
  AssocDCacheEnt *entry = assoc_find(cache, addr);
  if (entry) {
    cache->stat.load_hit[width]++;
    assoc_touch(cache, addr, entry);
    return true;
  }
  cache->stat.load_miss[width]++;
  assoc_allocate(cache, addr);
  return false;
}
static void assoc_store(AssocDCache *cache, uint32_t addr, unsigned width) {
  if (!dcacheable(addr))
    return;
  AssocDCacheEnt *entry = assoc_find(cache, addr);
  if (width == W_WORD) {
    cache->stat.full_store_allocate++;
    assoc_allocate(cache, addr);
  } else {
    cache->stat.partial_store_total++;
    if (entry) {
      cache->stat.partial_store_hit_update++;
      assoc_touch(cache, addr, entry);
    } else {
      cache->stat.partial_store_invalidate++;
      /* A partial miss cannot allocate unknown bytes.  In a set-associative
       * shadow it invalidates only the replacement way, preserving other
       * ways instead of pessimistically flushing the whole set. */
      entry = assoc_victim(cache, addr);
      entry->valid = 0;
    }
  }
}

static bool late_load_source_eligible(uint32_t x, unsigned source_mask) {
  unsigned op = x & 0x7f, f3 = (x >> 12) & 7, f7 = x >> 25;
  uint32_t imm12 = x >> 20;
  bool add = f3 == 0 && (op == 0x13 || (op == 0x33 && f7 == 0));
  bool andi1 = op == 0x13 && f3 == 7 && imm12 == 1;
  bool srli1 = op == 0x13 && f3 == 5 && imm12 == 1;
  bool allow_rs1 = add || andi1 || srli1;
  bool allow_rs2 = add && op == 0x33;
  return (!(source_mask & 1) || allow_rs1) &&
         (!(source_mask & 2) || allow_rs2);
}

static void record_masked_store_update_saving(PreviousLoad *load,
                                              unsigned distance, uint32_t x,
                                              unsigned consumer, unsigned rs1,
                                              unsigned rs2, bool use1,
                                              bool use2) {
  if (!load->valid)
    return;
  unsigned source_mask = ((use1 && rs1 == load->rd) ? 1 : 0) |
                         ((use2 && rs2 == load->rd) ? 2 : 0);
  if (!source_mask)
    return;

  bool eligible;
  if (distance == 1) {
    eligible = late_load_source_eligible(x, source_mask);
  } else {
    bool address_rs1 = consumer == K_LOAD || consumer == K_STORE ||
                       consumer == K_JALR;
    eligible = !(address_rs1 && (source_mask & 1));
  }
  if (!eligible)
    return;
  if (distance == 2 && load->d1_state_diff)
    return;

  unsigned slot = distance - 1;
  unsigned width = load->width;
  bool baseline_hit = load->rtl_hit;
  bool candidate_hit = load->proposed_hit;
  masked_store_baseline_hit[slot][width] += baseline_hit;
  masked_store_candidate_hit[slot][width] += candidate_hit;
  masked_store_incremental_hit[slot][width] +=
      candidate_hit && !baseline_hit;
  masked_store_lost_hit[slot][width] += baseline_hit && !candidate_hit;
  if (distance == 1)
    load->d1_state_diff = baseline_hit != candidate_hit;
}
static void model(BtbEnt *b, unsigned n, uint32_t pc, uint32_t target,
                  unsigned type, bool taken, bool allow_jalr, uint64_t *miss) {
  unsigned i = (pc >> 2) & (n - 1);
  if (type == K_JALR && !allow_jalr) {
    (*miss)++;
    return;
  }
  bool hit = b[i].valid && b[i].tag == pc && b[i].type == type;
  bool predicted_taken = type == K_BRANCH ? hit && b[i].counter >= 2 : hit;
  if (predicted_taken != taken ||
      (taken && (!hit || b[i].target != target)))
    (*miss)++;
  if (!hit)
    b[i] = (BtbEnt){pc, target, 1, type, taken ? 2 : 1};
  else {
    if (taken) {
      if (b[i].counter < 3)
        b[i].counter++;
      b[i].target = target;
    } else if (b[i].counter > 0)
      b[i].counter--;
  }
}
static void model_stored_jalr_not_predicted(BtbEnt *b, unsigned n, uint32_t pc,
                                            uint32_t target, unsigned type,
                                            bool taken, uint64_t *miss) {
  if (type != K_JALR) {
    model(b, n, pc, target, type, taken, false, miss);
    return;
  }

  /* Match the current RTL: JALR allocates and refreshes a BTB entry, but the
   * entry is not considered unconditionally taken by the predictor. */
  (*miss)++;
  unsigned i = (pc >> 2) & (n - 1);
  b[i] = (BtbEnt){pc, target, 1, type, 2};
}
static bool model_rtl_btb32(BtbEnt *b, uint32_t pc, uint32_t target,
                            uint32_t inst, unsigned type, bool taken,
                            bool skip_conflicting_not_taken) {
  unsigned i = (pc >> 2) & 31;
  bool hit = b[i].valid && b[i].tag == pc;
  bool predicted_taken =
      hit && (b[i].type == K_JAL ||
              (b[i].type == K_BRANCH && b[i].counter >= 2));
  uint32_t predicted_pc = predicted_taken ? b[i].target : pc + 4;
  bool wrong = predicted_pc != target;
  bool incoming_branch = type == K_BRANCH;
  bool incoming_jal = type == K_JAL || inst == 0x00008067;
  bool matching_branch = hit && b[i].type == K_BRANCH && incoming_branch;

  if (skip_conflicting_not_taken && incoming_branch && !taken &&
      !matching_branch)
    return wrong;

  uint8_t next_direction = 0;
  if (incoming_branch) {
    if (!matching_branch)
      next_direction = taken ? 2 : 1;
    else if (taken && b[i].counter != 3)
      next_direction = b[i].counter + 1;
    else if (!taken && b[i].counter != 0)
      next_direction = b[i].counter - 1;
    else
      next_direction = b[i].counter;
  }

  uint32_t stored_target =
      incoming_branch ? pc + (uint32_t)branch_imm(inst) : target;
  b[i] = (BtbEnt){pc, stored_target, 1,
                  incoming_jal ? K_JAL : type, next_direction};
  return wrong;
}
static LoadPairStat *find_load_pair(uint32_t producer_pc, uint32_t consumer_pc,
                                    unsigned width, unsigned consumer) {
  uint32_t h = producer_pc * 2654435761u ^ consumer_pc * 2246822519u ^
               width * 3266489917u ^ consumer * 668265263u;
  for (unsigned probe = 0; probe < PAIR_SLOTS; probe++) {
    LoadPairStat *pair = &load_pairs[(h + probe) & (PAIR_SLOTS - 1)];
    if (!pair->used) {
      pair->used = 1;
      pair->producer_pc = producer_pc;
      pair->consumer_pc = consumer_pc;
      pair->width = width;
      pair->consumer = consumer;
      return pair;
    }
    if (pair->producer_pc == producer_pc &&
        pair->consumer_pc == consumer_pc && pair->width == width &&
        pair->consumer == consumer)
      return pair;
  }
  pair_table_full++;
  return NULL;
}
static void record_immediate_load_raw(uint32_t pc, uint32_t x,
                                      unsigned consumer,
                                      unsigned rs1, unsigned rs2, bool use1,
                                      bool use2, Phase *phase) {
  if (!previous_load.valid)
    return;
  unsigned source_mask =
      ((use1 && rs1 == previous_load.rd) ? 1 : 0) |
      ((use2 && rs2 == previous_load.rd) ? 2 : 0);
  if (!source_mask)
    return;

  unsigned width = previous_load.width;
  bool address_rs1 = consumer == K_LOAD || consumer == K_STORE ||
                     consumer == K_JALR;
  bool eligible = !(address_rs1 && (source_mask & 1));
  bool current_hit = eligible && previous_load.current_hit;
  bool proposed_hit = eligible && previous_load.proposed_hit;
  bool incremental_hit = proposed_hit && !current_hit;
  bool lost_hit = current_hit && !proposed_hit;

  if (consumer == K_M) {
    unsigned mop = mop_of(x);
    unsigned source = source_mask - 1;
    load_m_raw[width][mop][source]++;
    if (eligible)
      load_m_raw_eligible[width][mop][source]++;
    if (current_hit)
      load_m_raw_current_hit[width][mop][source]++;
    if (proposed_hit)
      load_m_raw_proposed_hit[width][mop][source]++;
  }
  if (consumer == K_BRANCH) {
    unsigned branch_type = (x >> 12) & 7;
    unsigned source = source_mask - 1;
    load_branch_raw[width][branch_type][source]++;
    if (eligible)
      load_branch_eligible[width][branch_type][source]++;
    if (eligible)
      phase->late_load_branch_eligible++;
    if (current_hit)
      load_branch_current_hit[width][branch_type][source]++;
    if (proposed_hit)
      load_branch_proposed_hit[width][branch_type][source]++;
    if (proposed_hit)
      phase->late_load_branch_hit++;
  }

  load_raw[width][consumer]++;
  load_raw_source[width][source_mask - 1]++;
  if (eligible)
    load_raw_eligible[width][consumer]++;
  if (current_hit) {
    load_raw_current_hit[width][consumer]++;
    phase->current_bypass++;
  }
  if (proposed_hit) {
    load_raw_proposed_hit[width][consumer]++;
    phase->proposed_bypass++;
    phase->proposed_bypass_by_width[width]++;
  }
  if (incremental_hit) {
    load_raw_incremental_hit[width][consumer]++;
    phase->incremental_bypass++;
  }
  if (lost_hit) {
    load_raw_lost_hit[width][consumer]++;
    phase->lost_bypass++;
  }

  LoadPairStat *pair = find_load_pair(previous_load.pc, pc, width, consumer);
  if (!pair)
    return;
  pair->count++;
  pair->source[source_mask - 1]++;
  if (eligible)
    pair->eligible++;
  if (current_hit)
    pair->current_hit++;
  if (proposed_hit)
    pair->proposed_hit++;
  for (unsigned probe = 0; probe < 3; probe++)
    if (previous_load.assoc_hit[probe])
      pair->assoc_hit[probe]++;
  if (incremental_hit)
    pair->incremental_hit++;
  if (lost_hit)
    pair->lost_hit++;
}

void riscv_profile_record(const Decode *s, word_t x, word_t rs1_before,
                          word_t rs2_before) {
  unsigned k = kind_of(x), op = x & 0x7f, rd = (x >> 7) & 31,
           rs1 = (x >> 15) & 31, rs2 = (x >> 20) & 31;
  bool use1 = !(op == 0x37 || op == 0x17 || op == 0x6f);
  bool use2 = op == 0x33 || op == 0x23 || op == 0x63 || op == 0x0b;
  uint64_t seq = ++total;
  kinds[k]++;
  Phase *p = &phases[(seq - 1) / PHASE_LEN];
  p->inst++;

  if (previous_miss_consumer_rd &&
      ((use1 && rs1 == previous_miss_consumer_rd) ||
       (use2 && rs2 == previous_miss_consumer_rd)))
    miss_consumer_successor_deps++;
  previous_miss_consumer_rd = 0;

  if (masked_baseline_miss_consumer_rd &&
      ((use1 && rs1 == masked_baseline_miss_consumer_rd) ||
       (use2 && rs2 == masked_baseline_miss_consumer_rd)))
    masked_store_baseline_miss_successor_deps++;
  if (masked_candidate_miss_consumer_rd &&
      ((use1 && rs1 == masked_candidate_miss_consumer_rd) ||
       (use2 && rs2 == masked_candidate_miss_consumer_rd)))
    masked_store_candidate_miss_successor_deps++;
  masked_baseline_miss_consumer_rd = 0;
  masked_candidate_miss_consumer_rd = 0;

  if (previous_load.valid && rd && is_late_load_consumer(x)) {
    unsigned source_mask =
        ((use1 && rs1 == previous_load.rd) ? 1 : 0) |
        ((use2 && rs2 == previous_load.rd) ? 2 : 0);
    if (source_mask && !previous_load.proposed_hit)
      previous_miss_consumer_rd = rd;
    if (source_mask && late_load_source_eligible(x, source_mask)) {
      if (!previous_load.rtl_hit)
        masked_baseline_miss_consumer_rd = rd;
      if (!previous_load.proposed_hit)
        masked_candidate_miss_consumer_rd = rd;
    }
  }

  record_masked_store_update_saving(&previous_load, 1, x, k, rs1, rs2,
                                    use1, use2);
  record_masked_store_update_saving(&distance2_load, 2, x, k, rs1, rs2,
                                    use1, use2);
  record_immediate_load_raw(s->pc, x, k, rs1, rs2, use1, use2, p);

  /* Multiplication is a permutation modulo 2^20. Workloads currently execute
   * within a <=4 MiB IROM window, so this dense cache is collision-free. */
  PcStat *q = &pcs[((s->pc >> 2) * 2654435761u) & (PC_SLOTS - 1)];
  if (!q->n || q->pc == s->pc) {
    q->pc = s->pc;
    q->n++;
  }
  unsigned src[2] = {rs1, rs2};
  for (unsigned z = 0; z < 2; z++)
    if ((z ? use2 : use1) && src[z] && last_write[src[z]]) {
      uint64_t d = seq - last_write[src[z]];
      raw_dist[d < 8 ? d : 8]++;
      prod_cons[last_kind[src[z]]][k]++;
      p->raw++;
    }
  if (k == K_M) {
    mops[mop_of(x)]++;
    unsigned m_func3 = (x >> 12) & 7;
    bool is_div_rem = m_func3 >= 4;
    bool is_signed_div_rem = m_func3 == 4 || m_func3 == 6;
    m_divide_by_zero += is_div_rem && rs2_before == 0;
    m_signed_overflow += is_signed_div_rem &&
                         rs1_before == 0x80000000u &&
                         rs2_before == 0xffffffffu;
  }
  if (k == K_B) {
    unsigned bop = bop_of(x);
    bops[bop]++;
    if (bop == B_PACK) {
      uint32_t pack_result = (rs1_before & 0xffffu) | (rs2_before << 16);
      pack_rs2_zero += rs2_before == 0;
      pack_rs1_upper_zero += (rs1_before >> 16) == 0;
      pack_matches_xor += pack_result == (rs1_before ^ rs2_before);
    }
  }
  if (k == K_CRC)
    crcops[(x >> 12) & 7]++;
  if (k == K_BRANCH || k == K_JAL || k == K_JALR) {
    bool taken = s->dnpc != s->snpc;
    p->control++;
    if (taken) {
      p->taken++;
      q->taken++;
    }
    if (k == K_BRANCH) {
      int32_t imm = branch_imm(x);
      bool backward = imm < 0;
      branch_total++;
      branch_taken += taken;
      branch_backward += backward;
      if (backward)
        branch_backward_taken += taken, branch_backward_not_taken += !taken;
      else
        branch_forward_taken += taken, branch_forward_not_taken += !taken;
      /* A static backward-taken predictor is the useful targetless probe:
       * it predicts the common loop direction without requiring BTB state.
       * The not-taken probe is the lower-bound direction-only comparator. */
      branch_static_backward_miss += (backward != taken);
      branch_static_not_taken_miss += taken;
    }
    uint64_t btb32_before = btb32_miss;
    model(btb16, 16, s->pc, s->dnpc, k, taken, false, &btb16_miss);
    model(btb32, 32, s->pc, s->dnpc, k, taken, false, &btb32_miss);
    model(btb64, 64, s->pc, s->dnpc, k, taken, false, &btb64_miss);
    model(btb128, 128, s->pc, s->dnpc, k, taken, false, &btb128_miss);
    model(btb16j, 16, s->pc, s->dnpc, k, taken, true, &btb16_jalr_miss);
    model(btb32j, 32, s->pc, s->dnpc, k, taken, true, &btb32_jalr_miss);
    model_stored_jalr_not_predicted(
        btb32_stored_jalr_not_predicted, 32, s->pc, s->dnpc, k, taken,
        &btb32_stored_jalr_not_predicted_miss);
    bool rtl_current_wrong =
        model_rtl_btb32(rtl_btb32, s->pc, s->dnpc, x, k, taken, false);
    bool rtl_candidate_wrong = model_rtl_btb32(
        rtl_btb32_skip_not_taken, s->pc, s->dnpc, x, k, taken, true);
    rtl_btb32_miss += rtl_current_wrong;
    rtl_btb32_skip_not_taken_miss += rtl_candidate_wrong;
    p->rtl_btb32_miss += rtl_current_wrong;
    p->rtl_btb32_skip_not_taken_miss += rtl_candidate_wrong;
    if (k == K_BRANCH && taken && btb32_miss != btb32_before && branch_imm(x) < 0)
      branch_btb32_miss_backward_taken++;
  }
  if (k == K_JALR) {
    jalr_rd[rd == 0 ? 0 : rd == 1 ? 1 : 2]++;
    jalr_rs1[rs1 == 1 ? 0 : rs1 == 5 ? 1 : 2]++;
  }

  distance2_load = previous_load;
  previous_load.valid = 0;
  if (k == K_LOAD || k == K_STORE) {
    int32_t imm = k == K_LOAD
                      ? sext(x >> 20, 12)
                      : sext(((x >> 25) << 5) | ((x >> 7) & 31), 12);
    uint32_t addr = rs1_before + imm;
    unsigned width = memory_width(x, k == K_LOAD);
    widths[width]++;
    unsigned region = addr < 0x10000000   ? 0
                      : addr < 0x80000000 ? 1
                      : addr < 0x90000000 ? 2
                      : addr < 0xc0000000 ? 3
                                          : 4;
    regions[region]++;
    aliases[addr & 511]++;
    if (k == K_LOAD) {
      p->load++;
      bool current_eligible = dcacheable(addr) && width == W_WORD &&
                              aligned_for_width(addr, width);
      bool proposed_eligible = dcacheable(addr) &&
                               aligned_for_width(addr, width);
      bool current_hit =
          dcache_load(current_dcache, &current_dcache_stat, addr, width,
                      current_eligible);
      bool rtl_hit =
          dcache_load(rtl_dcache, &rtl_dcache_stat, addr, width,
                      proposed_eligible);
      bool proposed_hit =
          dcache_load(proposed_dcache, &proposed_dcache_stat, addr, width,
                      proposed_eligible);
      bool assoc_hit[3] = {
          assoc_load(&dcache_2way, addr, width, proposed_eligible),
          assoc_load(&dcache_4way, addr, width, proposed_eligible),
          assoc_load(&dcache_8way, addr, width, proposed_eligible)};
#if ENABLE_CAPACITY_PROBES
      assoc_load(&dcache_1024, addr, width, proposed_eligible);
      assoc_load(&dcache_2048, addr, width, proposed_eligible);
      assoc_load(&dcache_4096, addr, width, proposed_eligible);
#endif
      if (rd) {
        previous_load = (PreviousLoad){s->pc, rd, width, current_hit, rtl_hit,
                                       proposed_hit, {assoc_hit[0], assoc_hit[1],
                                                       assoc_hit[2]}, 0, 1};
      }
    } else {
      p->store++;
    current_dcache_store(addr, width);
    rtl_dcache_store(addr, width);
    proposed_dcache_store(addr, width);
    assoc_store(&dcache_2way, addr, width);
    assoc_store(&dcache_4way, addr, width);
    assoc_store(&dcache_8way, addr, width);
#if ENABLE_CAPACITY_PROBES
    assoc_store(&dcache_1024, addr, width);
    assoc_store(&dcache_2048, addr, width);
    assoc_store(&dcache_4096, addr, width);
#endif
      (void)rs2_before;
    }
  }
  bool writes = k != K_BRANCH && k != K_STORE && op != 0x0f;
  if (writes && rd) {
    last_write[rd] = seq;
    last_kind[rd] = k;
  }
}

static void arr(FILE *f, const uint64_t *a, unsigned n) {
  for (unsigned i = 0; i < n; i++)
    fprintf(f, "%s%llu", i ? "," : "", (unsigned long long)a[i]);
}
static void matrix(FILE *f, uint64_t a[W_NR][K_NR]) {
  fputc('[', f);
  for (unsigned i = 0; i < W_NR; i++) {
    if (i)
      fputc(',', f);
    fputc('[', f);
    arr(f, a[i], K_NR);
    fputc(']', f);
  }
  fputc(']', f);
}
static void load_m_cube(FILE *f, uint64_t a[W_NR][M_NR][3]) {
  fputc('[', f);
  for (unsigned width = 0; width < W_NR; width++) {
    if (width)
      fputc(',', f);
    fputc('[', f);
    for (unsigned mop = 0; mop < M_NR; mop++) {
      if (mop)
        fputc(',', f);
      fputc('[', f);
      arr(f, a[width][mop], 3);
      fputc(']', f);
    }
    fputc(']', f);
  }
  fputc(']', f);
}
static void load_branch_cube(FILE *f, uint64_t a[W_NR][8][3]) {
  fputc('[', f);
  for (unsigned width = 0; width < W_NR; width++) {
    if (width)
      fputc(',', f);
    fputc('[', f);
    for (unsigned branch = 0; branch < 8; branch++) {
      if (branch)
        fputc(',', f);
      fputc('[', f);
      arr(f, a[width][branch], 3);
      fputc(']', f);
    }
    fputc(']', f);
  }
  fputc(']', f);
}
static uint64_t matrix_sum(uint64_t a[W_NR][K_NR]) {
  uint64_t sum = 0;
  for (unsigned i = 0; i < W_NR; i++)
    for (unsigned j = 0; j < K_NR; j++)
      sum += a[i][j];
  return sum;
}
static int pc_cmp(const void *a, const void *b) {
  const PcStat *x = a, *y = b;
  return x->n < y->n ? 1 : x->n > y->n ? -1 : 0;
}
static int pair_cmp(const void *a, const void *b) {
  const LoadPairStat *x = a, *y = b;
  return x->count < y->count ? 1 : x->count > y->count ? -1 : 0;
}
static void dcache_stat_json(FILE *f, const DCacheStat *stat) {
  fprintf(f, "{\"load_access_by_width\":[");
  arr(f, stat->load_access, W_NR);
  fprintf(f, "],\"load_hit_by_width\":[");
  arr(f, stat->load_hit, W_NR);
  fprintf(f, "],\"load_miss_by_width\":[");
  arr(f, stat->load_miss, W_NR);
  fprintf(f,
          "],\"full_store_allocate\":%llu,"
          "\"partial_store_total\":%llu,"
          "\"partial_store_hit_update\":%llu,"
          "\"partial_store_invalidate\":%llu}",
          (unsigned long long)stat->full_store_allocate,
          (unsigned long long)stat->partial_store_total,
          (unsigned long long)stat->partial_store_hit_update,
          (unsigned long long)stat->partial_store_invalidate);
}
static uint64_t dcache_miss_sum(const DCacheStat *stat) {
  uint64_t total_miss = 0;
  for (unsigned width = 0; width < W_NR; width++)
    total_miss += stat->load_miss[width];
  return total_miss;
}

void riscv_profile_finish(void) {
  if (!out_path)
    return;
  FILE *f = fopen(out_path, "w");
  if (!f) {
    perror(out_path);
    return;
  }
  uint64_t control = kinds[K_BRANCH] + kinds[K_JAL] + kinds[K_JALR];
  uint64_t current_bypass = matrix_sum(load_raw_current_hit);
  uint64_t proposed_bypass = matrix_sum(load_raw_proposed_hit);
  uint64_t incremental_bypass = matrix_sum(load_raw_incremental_hit);
  uint64_t lost_bypass = matrix_sum(load_raw_lost_hit);
  long long net_bypass = (long long)proposed_bypass - (long long)current_bypass;
  double net_ms_280mhz = (double)net_bypass / 280000.0;
  uint64_t masked_baseline_cycles = 0, masked_candidate_cycles = 0,
           masked_incremental_cycles = 0, masked_lost_cycles = 0;
  for (unsigned distance = 0; distance < 2; distance++)
    for (unsigned width = 0; width < W_NR; width++) {
      masked_baseline_cycles += masked_store_baseline_hit[distance][width];
      masked_candidate_cycles += masked_store_candidate_hit[distance][width];
      masked_incremental_cycles +=
          masked_store_incremental_hit[distance][width];
      masked_lost_cycles += masked_store_lost_hit[distance][width];
    }
  long long masked_forward_net_cycles =
      (long long)masked_candidate_cycles - (long long)masked_baseline_cycles;
  long long masked_successor_net_cycles =
      (long long)masked_store_baseline_miss_successor_deps -
      (long long)masked_store_candidate_miss_successor_deps;
  long long masked_net_cycles =
      masked_forward_net_cycles + masked_successor_net_cycles;

  fprintf(f,
          "{\n  \"schema\":3,\n  \"total_instructions\":%llu,\n  "
          "\"control_instructions\":%llu,\n  \"categories\":{",
          (unsigned long long)total, (unsigned long long)control);
  for (int i = 0; i < K_NR; i++)
    fprintf(f, "%s\"%s\":%llu", i ? "," : "", kind_names[i],
            (unsigned long long)kinds[i]);
  fprintf(f,
          "},\n  \"m_ops_order\":[\"mul\",\"mulh\",\"mulhsu\",\"mulhu\","
          "\"div\",\"divu\",\"rem\",\"remu\"],\n  \"m_ops\":[");
  arr(f, mops, M_NR);
  fprintf(f,
          "],\n  \"m_special_operands\":{\"divide_by_zero\":%llu,"
          "\"signed_overflow\":%llu},"
          "\n  \"b_ops_order\":[\"clz\",\"ctz\",\"cpop\",\"sext.b\","
          "\"sext.h\",\"clmul\",\"clmulr\",\"clmulh\",\"orc.b\","
          "\"rev8\",\"andn\",\"orn\",\"xnor\",\"min\",\"max\","
          "\"minu\",\"maxu\",\"sh1add\",\"sh2add\",\"sh3add\","
          "\"bset\",\"bclr\",\"binv\",\"bext\",\"xperm4\",\"rol\","
          "\"ror\",\"rori\",\"pack\",\"other\"],\n  \"b_ops\":[",
          (unsigned long long)m_divide_by_zero,
          (unsigned long long)m_signed_overflow);
  arr(f, bops, B_NR);
  fprintf(f,
          "],\n  \"crc_ops_order\":[\"crcu8\",\"crcu16\",\"crcu32\"],"
          "\n  \"crc_ops\":[");
  arr(f, crcops, CRC_NR);
  fprintf(f,
          "],\n  \"xaccel_ops_order\":[\"xmac16\",\"xdot16\",\"xbmul\","
          "\"xlistrev\",\"unused\",\"xmsum\"],\n  \"xaccel_ops\":[");
  arr(f, xaccel_ops, 6);
  fprintf(f, "],\n  \"xaccel_units\":[");
  arr(f, xaccel_units, 6);
  fprintf(f, "],\n  \"xaccel_modeled_cycles_conservative\":[");
  arr(f, xaccel_cycles, 6);
  fprintf(f, "],\n  \"xdfacnt_ops_order\":[\"init\",\"inc\",\"read\",\"commit\",\"step2\",\"step4\"],"
             "\n  \"xdfacnt_ops\":[");
  arr(f, xdfacnt_ops, 6);
  fprintf(f,
          "],\n  \"pack_diagnostics\":{"
          "\"rs2_zero\":%llu,\"rs1_upper_zero\":%llu,"
          "\"matches_previous_xor_result\":%llu},"
          "\n  \"miss_consumer_immediate_successor_dependencies\":%llu,"
          "\n  \"miss_successor_penalty_ms_at_280mhz\":%.6f,"
          "\n  \"raw_distance_1_to_7_then_8plus\":[",
          (unsigned long long)pack_rs2_zero,
          (unsigned long long)pack_rs1_upper_zero,
          (unsigned long long)pack_matches_xor,
          (unsigned long long)miss_consumer_successor_deps,
          (double)miss_consumer_successor_deps / 280000.0);
  arr(f, raw_dist + 1, 8);
  fprintf(f, "],\n  \"producer_consumer_category_matrix\":[");
  for (int i = 0; i < K_NR; i++) {
    if (i)
      fputc(',', f);
    fputc('[', f);
    arr(f, prod_cons[i], K_NR);
    fputc(']', f);
  }
  fprintf(f, "],\n  \"memory_width_byte_half_word_other\":[");
  arr(f, widths, W_NR);
  fprintf(f,
          "],\n  \"memory_regions_lt10000000_lt80000000_lt90000000_ltc0000000_other\":[");
  arr(f, regions, 5);
  fprintf(f, "],\n  \"jyd_low9_address_aliases\":[");
  arr(f, aliases, 512);
  fprintf(f, "],\n  \"jalr_rd_x0_x1_other\":[");
  arr(f, jalr_rd, 3);
  fprintf(f, "],\n  \"jalr_rs1_x1_x5_other\":[");
  arr(f, jalr_rs1, 3);
  fprintf(f,
          "],\n  \"predictor_estimate\":{\"architectural_order\":true,"
          "\"note\":\"not a cycle-accurate model\","
          "\"btb16_no_jalr_misses\":%llu,"
          "\"btb32_no_jalr_misses\":%llu,"
          "\"btb32_stored_jalr_not_predicted_misses\":%llu,"
          "\"btb16_with_jalr_misses\":%llu,"
          "\"btb32_with_jalr_misses\":%llu,"
          "\"rtl_btb32_misses\":%llu,"
          "\"rtl_btb32_skip_conflicting_not_taken_misses\":%llu,"
          "\"rtl_btb32_skip_conflicting_not_taken_saved_misses\":%lld,"
          "\"rtl_btb32_skip_conflicting_not_taken_saved_cycles\":%lld,"
          "\"btb64_no_jalr_misses\":%llu,"
          "\"btb128_no_jalr_misses\":%llu},\n"
          "  \"branch_direction_probe\":{"
          "\"conditional_total\":%llu,\"taken\":%llu,"
          "\"backward\":%llu,\"backward_taken\":%llu,"
          "\"backward_not_taken\":%llu,\"forward_taken\":%llu,"
          "\"forward_not_taken\":%llu,"
          "\"static_backward_taken_misses\":%llu,"
          "\"static_not_taken_misses\":%llu,"
          "\"btb32_miss_backward_taken\":%llu},\n",
          (unsigned long long)btb16_miss,
          (unsigned long long)btb32_miss,
          (unsigned long long)btb32_stored_jalr_not_predicted_miss,
          (unsigned long long)btb16_jalr_miss,
          (unsigned long long)btb32_jalr_miss,
          (unsigned long long)rtl_btb32_miss,
          (unsigned long long)rtl_btb32_skip_not_taken_miss,
          (long long)rtl_btb32_miss -
              (long long)rtl_btb32_skip_not_taken_miss,
          3 * ((long long)rtl_btb32_miss -
               (long long)rtl_btb32_skip_not_taken_miss),
          (unsigned long long)btb64_miss,
          (unsigned long long)btb128_miss,
          (unsigned long long)branch_total,
          (unsigned long long)branch_taken,
          (unsigned long long)branch_backward,
          (unsigned long long)branch_backward_taken,
          (unsigned long long)branch_backward_not_taken,
          (unsigned long long)branch_forward_taken,
          (unsigned long long)branch_forward_not_taken,
          (unsigned long long)branch_static_backward_miss,
          (unsigned long long)branch_static_not_taken_miss,
          (unsigned long long)branch_btb32_miss_backward_taken);

  fprintf(f,
          "  \"dcache_models\":{\"architectural_order\":true,"
          "\"line_count\":512,\"line_bytes\":4,"
          "\"index_bits\":\"addr[10:2]\",\"tag_bits\":\"addr[17:11]\","
          "\"cacheable_rule\":\"addr[21:20] == 1\","
          "\"width_order\":[\"byte\",\"half\",\"word\",\"other\"],"
          "\"current\":");
  dcache_stat_json(f, &current_dcache_stat);
  fprintf(f, ",\"proposed\":");
  dcache_stat_json(f, &proposed_dcache_stat);
  uint64_t proposed_misses = dcache_miss_sum(&proposed_dcache_stat);
  fprintf(f,
          ",\"associativity_probes\":{"
          "\"model\":\"all aligned cacheable loads, full-word allocate, "
          "partial-hit update, partial-miss invalidate one way\","
          "\"direct_1way_512_misses\":%llu,"
          "\"2way_256\":{\"stats\":",
          (unsigned long long)proposed_misses);
  dcache_stat_json(f, &dcache_2way.stat);
  fprintf(f, ",\"miss_reduction_vs_direct\":%lld},\"4way_128\":{\"stats\":",
          (long long)proposed_misses -
              (long long)dcache_miss_sum(&dcache_2way.stat));
  dcache_stat_json(f, &dcache_4way.stat);
  fprintf(f, ",\"miss_reduction_vs_direct\":%lld},\"8way_64\":{\"stats\":",
          (long long)proposed_misses -
              (long long)dcache_miss_sum(&dcache_4way.stat));
  dcache_stat_json(f, &dcache_8way.stat);
  fprintf(f, ",\"miss_reduction_vs_direct\":%lld}",
          (long long)proposed_misses -
              (long long)dcache_miss_sum(&dcache_8way.stat));
#if ENABLE_CAPACITY_PROBES
  fprintf(f, ",\"capacity_2x_1024_lines\":{\"stats\":");
  dcache_stat_json(f, &dcache_1024.stat);
  fprintf(f, ",\"miss_reduction_vs_direct\":%lld},\"capacity_4x_2048_lines\":{\"stats\":",
          (long long)proposed_misses -
              (long long)dcache_miss_sum(&dcache_1024.stat));
  dcache_stat_json(f, &dcache_2048.stat);
  fprintf(f, ",\"miss_reduction_vs_direct\":%lld},\"capacity_8x_4096_lines\":{\"stats\":",
          (long long)proposed_misses -
              (long long)dcache_miss_sum(&dcache_2048.stat));
  dcache_stat_json(f, &dcache_4096.stat);
  fprintf(f, ",\"miss_reduction_vs_direct\":%lld}}},\n",
          (long long)proposed_misses -
              (long long)dcache_miss_sum(&dcache_4096.stat));
#else
  fprintf(f, "}},\n");
#endif
  /* Close dcache_models before the independent load/RAW sections below. */

  fprintf(f,
          "  \"partial_store_hit_update_isolated\":{"
          "\"baseline_model\":\"narrow loads plus partial-store invalidate\","
          "\"candidate_model\":\"narrow loads plus masked update on hit\","
          "\"rtl_narrow_invalidate_cache\":");
  dcache_stat_json(f, &rtl_dcache_stat);
  fprintf(f, ",\"candidate_masked_update_cache\":");
  dcache_stat_json(f, &proposed_dcache_stat);
  fprintf(f,
          ",\"distance1_exact_late_consumer_baseline_by_width\":[");
  arr(f, masked_store_baseline_hit[0], W_NR);
  fprintf(f, "],\"distance1_exact_late_consumer_candidate_by_width\":[");
  arr(f, masked_store_candidate_hit[0], W_NR);
  fprintf(f, "],\"distance1_incremental_by_width\":[");
  arr(f, masked_store_incremental_hit[0], W_NR);
  fprintf(f, "],\"distance1_lost_by_width\":[");
  arr(f, masked_store_lost_hit[0], W_NR);
  fprintf(f, "],\"distance2_no_d1_stall_baseline_by_width\":[");
  arr(f, masked_store_baseline_hit[1], W_NR);
  fprintf(f, "],\"distance2_no_d1_stall_candidate_by_width\":[");
  arr(f, masked_store_candidate_hit[1], W_NR);
  fprintf(f, "],\"distance2_no_d1_stall_incremental_by_width\":[");
  arr(f, masked_store_incremental_hit[1], W_NR);
  fprintf(f, "],\"distance2_no_d1_stall_lost_by_width\":[");
  arr(f, masked_store_lost_hit[1], W_NR);
  fprintf(f,
          "],\"one_cycle_saving_estimate\":{"
          "\"baseline_cycles\":%llu,\"candidate_cycles\":%llu,"
          "\"incremental_hits\":%llu,\"lost_hits\":%llu,"
          "\"forward_net_cycles\":%lld,"
          "\"baseline_miss_consumer_successor_deps\":%llu,"
          "\"candidate_miss_consumer_successor_deps\":%llu,"
          "\"successor_net_cycles\":%lld,\"net_cycles\":%lld,"
          "\"net_ms_at_280mhz\":%.6f},"
          "\"assumptions\":\"one cycle per newly enabled hit-dependent "
          "consumer; distance 1 mirrors restricted late ADD/ANDI-1/SRLI-1; "
          "distance 2 mirrors generic LSU cache forwarding and excludes "
          "loads already changed at distance 1; miss-completed consumer "
          "successor dependencies add one cycle; intervening multi-cycle "
          "instructions can still make the estimate an upper bound\"},\n",
          (unsigned long long)masked_baseline_cycles,
          (unsigned long long)masked_candidate_cycles,
          (unsigned long long)masked_incremental_cycles,
          (unsigned long long)masked_lost_cycles, masked_forward_net_cycles,
          (unsigned long long)masked_store_baseline_miss_successor_deps,
          (unsigned long long)masked_store_candidate_miss_successor_deps,
          masked_successor_net_cycles, masked_net_cycles,
          (double)masked_net_cycles / 280000.0);

  fprintf(f,
          "  \"load_raw_distance1\":{"
          "\"width_order\":[\"byte\",\"half\",\"word\",\"other\"],"
          "\"consumer_order\":[");
  for (unsigned i = 0; i < K_NR; i++)
    fprintf(f, "%s\"%s\"", i ? "," : "", kind_names[i]);
  fprintf(f, "],\"source_order\":[\"rs1_only\",\"rs2_only\",\"both\"],"
             "\"consumer_counts\":");
  matrix(f, load_raw);
  fprintf(f, ",\"source_counts_by_width\":[");
  for (unsigned i = 0; i < W_NR; i++) {
    if (i)
      fputc(',', f);
    fputc('[', f);
    arr(f, load_raw_source[i], 3);
    fputc(']', f);
  }
  fprintf(f, "],\"lsu_idu_eligible_counts\":");
  matrix(f, load_raw_eligible);
  fprintf(f, ",\"current_hit_eligible_counts\":");
  matrix(f, load_raw_current_hit);
  fprintf(f, ",\"proposed_hit_eligible_counts\":");
  matrix(f, load_raw_proposed_hit);
  fprintf(f, ",\"incremental_hit_counts\":");
  matrix(f, load_raw_incremental_hit);
  fprintf(f, ",\"lost_hit_counts\":");
  matrix(f, load_raw_lost_hit);
  fprintf(f,
          ",\"eligibility_note\":\"all dependent operands must be available; "
          "load/store/jalr rs1 address dependencies are excluded\","
          "\"one_cycle_saving_estimate\":{"
          "\"assumption\":\"one cycle saved per eligible cache-hit distance-1 "
          "consumer\",\"current_cycles\":%llu,\"proposed_cycles\":%llu,"
          "\"incremental_hits\":%llu,\"lost_hits\":%llu,"
          "\"net_cycles\":%lld,\"net_ms_at_280mhz\":%.6f}},\n",
          (unsigned long long)current_bypass,
          (unsigned long long)proposed_bypass,
          (unsigned long long)incremental_bypass,
          (unsigned long long)lost_bypass, net_bypass, net_ms_280mhz);

  fprintf(f,
          "  \"load_m_raw_distance1\":{"
          "\"width_order\":[\"byte\",\"half\",\"word\",\"other\"],"
          "\"m_op_order\":[\"mul\",\"mulh\",\"mulhsu\",\"mulhu\","
          "\"div\",\"divu\",\"rem\",\"remu\"],"
          "\"source_order\":[\"rs1_only\",\"rs2_only\",\"both\"],"
          "\"counts\":");
  load_m_cube(f, load_m_raw);
  fprintf(f, ",\"lsu_idu_eligible_counts\":");
  load_m_cube(f, load_m_raw_eligible);
  fprintf(f, ",\"current_hit_eligible_counts\":");
  load_m_cube(f, load_m_raw_current_hit);
  fprintf(f, ",\"proposed_hit_eligible_counts\":");
  load_m_cube(f, load_m_raw_proposed_hit);
  fprintf(f, "},\n");

  fprintf(f,
          "  \"load_branch_raw_distance1\":{"
          "\"width_order\":[\"byte\",\"half\",\"word\",\"other\"],"
          "\"branch_funct3_order\":[\"beq\",\"bne\",\"reserved2\","
          "\"reserved3\",\"blt\",\"bge\",\"bltu\",\"bgeu\"],"
          "\"source_order\":[\"rs1_only\",\"rs2_only\",\"both\"],"
          "\"counts\":");
  load_branch_cube(f, load_branch_raw);
  fprintf(f, ",\"eligible_counts\":");
  load_branch_cube(f, load_branch_eligible);
  fprintf(f, ",\"current_hit_counts\":");
  load_branch_cube(f, load_branch_current_hit);
  fprintf(f, ",\"proposed_hit_counts\":");
  load_branch_cube(f, load_branch_proposed_hit);
  fprintf(f, "},\n");

  fprintf(f, "  \"phases_16m\":[");
  unsigned np = (total + PHASE_LEN - 1) / PHASE_LEN;
  for (unsigned i = 0; i < np; i++)
    fprintf(f,
            "%s{\"inst\":%llu,\"control\":%llu,\"taken\":%llu,"
            "\"load\":%llu,\"store\":%llu,\"raw\":%llu,"
            "\"current_bypass\":%llu,\"proposed_bypass\":%llu,"
            "\"proposed_bypass_byte\":%llu,"
            "\"proposed_bypass_half\":%llu,"
            "\"proposed_bypass_narrow\":%llu,"
            "\"incremental_bypass\":%llu,\"lost_bypass\":%llu,"
            "\"late_load_branch_eligible\":%llu,"
            "\"late_load_branch_hit\":%llu,"
            "\"late_load_branch_hit_cycles\":%llu,"
            "\"late_load_branch_saved_cycles\":%llu,"
            "\"rtl_btb32_misses\":%llu,"
            "\"rtl_btb32_skip_not_taken_misses\":%llu,"
            "\"rtl_btb32_skip_not_taken_saved_misses\":%lld,"
            "\"rtl_btb32_skip_not_taken_saved_cycles\":%lld}",
            i ? "," : "", (unsigned long long)phases[i].inst,
            (unsigned long long)phases[i].control,
            (unsigned long long)phases[i].taken,
            (unsigned long long)phases[i].load,
            (unsigned long long)phases[i].store,
            (unsigned long long)phases[i].raw,
            (unsigned long long)phases[i].current_bypass,
            (unsigned long long)phases[i].proposed_bypass,
            (unsigned long long)phases[i].proposed_bypass_by_width[W_BYTE],
            (unsigned long long)phases[i].proposed_bypass_by_width[W_HALF],
            (unsigned long long)(phases[i].proposed_bypass_by_width[W_BYTE] +
                                 phases[i].proposed_bypass_by_width[W_HALF]),
            (unsigned long long)phases[i].incremental_bypass,
            (unsigned long long)phases[i].lost_bypass,
            (unsigned long long)phases[i].late_load_branch_eligible,
            (unsigned long long)phases[i].late_load_branch_hit,
            (unsigned long long)phases[i].late_load_branch_hit,
            (unsigned long long)phases[i].late_load_branch_eligible,
            (unsigned long long)phases[i].rtl_btb32_miss,
            (unsigned long long)phases[i].rtl_btb32_skip_not_taken_miss,
            (long long)phases[i].rtl_btb32_miss -
                (long long)phases[i].rtl_btb32_skip_not_taken_miss,
            3 * ((long long)phases[i].rtl_btb32_miss -
                 (long long)phases[i].rtl_btb32_skip_not_taken_miss));
  fprintf(f, "],\n  \"load_raw_top_pc_pairs\":[");
  qsort(load_pairs, PAIR_SLOTS, sizeof(*load_pairs), pair_cmp);
  for (unsigned i = 0, n = 0; i < PAIR_SLOTS && n < 64; i++)
    if (load_pairs[i].count) {
      LoadPairStat *pair = &load_pairs[i];
      fprintf(f,
              "%s{\"producer_pc\":\"0x%08x\","
              "\"consumer_pc\":\"0x%08x\",\"width\":\"%s\","
              "\"consumer\":\"%s\",\"count\":%llu,\"eligible\":%llu,"
              "\"current_hit\":%llu,\"proposed_hit\":%llu,"
              "\"incremental_hit\":%llu,\"lost_hit\":%llu,"
              "\"assoc_2way_hit\":%llu,\"assoc_4way_hit\":%llu,"
              "\"assoc_8way_hit\":%llu,"
              "\"source_rs1_rs2_both\":[%llu,%llu,%llu]}",
              n++ ? "," : "", pair->producer_pc, pair->consumer_pc,
              width_names[pair->width], kind_names[pair->consumer],
              (unsigned long long)pair->count,
              (unsigned long long)pair->eligible,
              (unsigned long long)pair->current_hit,
              (unsigned long long)pair->proposed_hit,
              (unsigned long long)pair->incremental_hit,
              (unsigned long long)pair->lost_hit,
              (unsigned long long)pair->assoc_hit[0],
              (unsigned long long)pair->assoc_hit[1],
              (unsigned long long)pair->assoc_hit[2],
              (unsigned long long)pair->source[0],
              (unsigned long long)pair->source[1],
              (unsigned long long)pair->source[2]);
    }
  fprintf(f, "],\n  \"load_raw_pair_table_full\":%llu,\n  \"pc_hotspots\":[",
          (unsigned long long)pair_table_full);
  qsort(pcs, PC_SLOTS, sizeof(*pcs), pc_cmp);
  for (unsigned i = 0, n = 0; i < PC_SLOTS && n < 64; i++)
    if (pcs[i].n)
      fprintf(f,
              "%s{\"pc\":\"0x%08x\",\"count\":%llu,\"taken\":%llu}",
              n++ ? "," : "", pcs[i].pc, (unsigned long long)pcs[i].n,
              (unsigned long long)pcs[i].taken);
  fprintf(f, "]\n}\n");
  fclose(f);
  free(pcs);
  free(load_pairs);
  pcs = NULL;
  load_pairs = NULL;
  out_path = NULL;
}
