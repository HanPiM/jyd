#include "../public/btrace_pack.h"

#include <algorithm>
#include <bit>
#include <array>
#include <cassert>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <functional>
#include <memory>
#include <string>
#include <utility>
#include <vector>

namespace {

constexpr const char *kDefaultTracePath = "../nemu/btrace_pack.bin.bz2";
constexpr size_t kDefaultBTBSize = 16;
constexpr uint32_t kDefaultGHRBits = 10;
constexpr uint32_t kDefaultLocalHistoryBits = 4;

uint32_t sign_extend(uint32_t value, uint32_t bits) {
  const uint32_t sign_bit = 1u << (bits - 1);
  return (value ^ sign_bit) - sign_bit;
}

uint32_t get_b_imm(uint32_t code) {
  const uint32_t imm12 = (code >> 31) & 0x1;
  const uint32_t imm11 = (code >> 7) & 0x1;
  const uint32_t imm10_5 = (code >> 25) & 0x3f;
  const uint32_t imm4_1 = (code >> 8) & 0xf;
  const uint32_t imm =
      (imm12 << 12) | (imm11 << 11) | (imm10_5 << 5) | (imm4_1 << 1);
  return sign_extend(imm, 13);
}

uint32_t get_j_imm(uint32_t code) {
  const uint32_t imm20 = (code >> 31) & 0x1;
  const uint32_t imm19_12 = (code >> 12) & 0xff;
  const uint32_t imm11 = (code >> 20) & 0x1;
  const uint32_t imm10_1 = (code >> 21) & 0x3ff;
  const uint32_t imm =
      (imm20 << 20) | (imm19_12 << 12) | (imm11 << 11) | (imm10_1 << 1);
  return sign_extend(imm, 21);
}

bool is_conditional_branch(uint32_t code) { return (code & 0x7f) == 0x63; }
bool is_jal(uint32_t code) { return (code & 0x7f) == 0x6f; }
bool is_jalr(uint32_t code) { return (code & 0x7f) == 0x67; }
bool is_exact_ret(uint32_t code) { return code == 0x00008067; }

enum class ControlKind : uint8_t {
  Branch,
  Jal,
  Ret,
  Jalr,
  Count,
};

ControlKind get_control_kind(uint32_t code) {
  if (is_conditional_branch(code))
    return ControlKind::Branch;
  if (is_jal(code))
    return ControlKind::Jal;
  if (is_exact_ret(code))
    return ControlKind::Ret;
  assert(is_jalr(code) && "branch trace contains a non-control instruction");
  return ControlKind::Jalr;
}

const char *control_kind_name(ControlKind kind) {
  switch (kind) {
  case ControlKind::Branch:
    return "branch";
  case ControlKind::Jal:
    return "jal";
  case ControlKind::Ret:
    return "ret";
  case ControlKind::Jalr:
    return "jalr_other";
  case ControlKind::Count:
    break;
  }
  return "invalid";
}

struct BTBEntry {
  uint32_t tag = 0;
  uint32_t target = 0;
  bool is_jal = false;
  bool valid = false;
};

struct HistoryEntry {
  bool valid = false;
  bool is_jal = false;
  uint32_t target = 0;
};

struct DecodedBranch {
  bool is_conditional = false;
  bool is_jal = false;
  bool direct_target_valid = false;
  uint32_t direct_target = 0;
};

struct PredictContext {
  uint32_t pc = 0;
  uint32_t code = 0;
  HistoryEntry entry;
  DecodedBranch decoded;
};

class BTB {
public:
  explicit BTB(size_t size) : entries_(size) {
    assert(std::has_single_bit(size) && "BTB size must be a power of 2");
  }

  HistoryEntry query(uint32_t pc) const {
    HistoryEntry result;
    const auto &entry = entries_[get_index(pc)];
    if (entry.valid && entry.tag == get_tag(pc)) {
      result.valid = true;
      result.is_jal = entry.is_jal;
      result.target = entry.target;
    }
    return result;
  }

  void update(uint32_t pc, uint32_t target, bool is_jal_branch) {
    entries_[get_index(pc)] = {.tag = get_tag(pc),
                               .target = target,
                               .is_jal = is_jal_branch,
                               .valid = true};
  }

private:
  size_t get_index(uint32_t pc) const { return (pc >> 2) % entries_.size(); }

  uint32_t get_tag(uint32_t pc) const {
    const uint32_t index_bits = std::countr_zero(entries_.size());
    return pc >> (2 + index_bits);
  }

  std::vector<BTBEntry> entries_;
};

class CounterTable {
public:
  explicit CounterTable(size_t size, uint8_t initial_value = 1)
      : counters_(size, initial_value) {
    assert(std::has_single_bit(size) && "Counter table size must be a power of 2");
    assert(initial_value <= 3 && "Counter init value must fit in 2 bits");
  }

  bool prefer_high(size_t index) const { return counters_[index] >= 2; }

  void update(size_t index, bool toward_high) {
    auto &counter = counters_[index];
    if (toward_high) {
      if (counter < 3) {
        counter++;
      }
    } else if (counter > 0) {
      counter--;
    }
  }

  size_t size() const { return counters_.size(); }

private:
  std::vector<uint8_t> counters_;
};

class GSharePredictor {
public:
  GSharePredictor(size_t pht_size, uint32_t ghr_bits)
      : pht_(pht_size), ghr_mask_(make_mask(ghr_bits)), ghr_(0) {
    assert(ghr_bits > 0 && "GHR bits must be positive");
  }

  bool predict_taken(uint32_t pc) const { return pht_.prefer_high(get_index(pc)); }

  void update(uint32_t pc, bool taken) {
    pht_.update(get_index(pc), taken);
    ghr_ = ((ghr_ << 1) | static_cast<uint32_t>(taken)) & ghr_mask_;
  }

private:
  static uint32_t make_mask(uint32_t bits) {
    return bits >= 32 ? ~0u : ((1u << bits) - 1);
  }

  size_t get_index(uint32_t pc) const {
    return (((pc >> 2) ^ ghr_) & ghr_mask_) % pht_.size();
  }

  CounterTable pht_;
  uint32_t ghr_mask_;
  uint32_t ghr_;
};

class LocalPredictor {
public:
  LocalPredictor(size_t lht_size, uint32_t history_bits, size_t pht_size)
      : local_histories_(lht_size, 0), history_mask_(make_mask(history_bits)),
        pht_(pht_size) {
    assert(history_bits > 0 && "Local history bits must be positive");
  }

  bool predict_taken(uint32_t pc) const {
    return pht_.prefer_high(get_pht_index(pc));
  }

  void update(uint32_t pc, bool taken) {
    pht_.update(get_pht_index(pc), taken);
    auto &history = local_histories_[get_history_index(pc)];
    history = ((history << 1) | static_cast<uint32_t>(taken)) & history_mask_;
  }

private:
  static uint32_t make_mask(uint32_t bits) {
    return bits >= 32 ? ~0u : ((1u << bits) - 1);
  }

  size_t get_history_index(uint32_t pc) const {
    return (pc >> 2) % local_histories_.size();
  }

  size_t get_pht_index(uint32_t pc) const {
    return local_histories_[get_history_index(pc)] % pht_.size();
  }

  std::vector<uint32_t> local_histories_;
  uint32_t history_mask_;
  CounterTable pht_;
};

class LocalGlobalTournamentPredictor {
public:
  LocalGlobalTournamentPredictor(size_t lht_size, uint32_t local_history_bits,
                                 size_t local_pht_size, size_t gshare_size,
                                 size_t chooser_size, uint32_t ghr_bits)
      : local_(lht_size, local_history_bits, local_pht_size),
        gshare_(gshare_size, ghr_bits), chooser_(chooser_size, 2) {}

  bool predict_taken(uint32_t pc) const {
    const bool local_taken = local_.predict_taken(pc);
    const bool gshare_taken = gshare_.predict_taken(pc);
    return chooser_.prefer_high(get_chooser_index(pc)) ? gshare_taken
                                                        : local_taken;
  }

  void update(uint32_t pc, bool taken) {
    const size_t chooser_index = get_chooser_index(pc);
    const bool local_taken = local_.predict_taken(pc);
    const bool gshare_taken = gshare_.predict_taken(pc);
    if (local_taken != gshare_taken) {
      if (gshare_taken == taken) {
        chooser_.update(chooser_index, true);
      } else if (local_taken == taken) {
        chooser_.update(chooser_index, false);
      }
    }
    local_.update(pc, taken);
    gshare_.update(pc, taken);
  }

private:
  size_t get_chooser_index(uint32_t pc) const { return (pc >> 2) % chooser_.size(); }

  LocalPredictor local_;
  GSharePredictor gshare_;
  CounterTable chooser_;
};

using predict_t = std::function<bool(const PredictContext &)>;
using update_t = std::function<void(const PredictContext &, bool taken, uint32_t nxt_pc,
                                    BTB &btb)>;

struct AlgoConfig {
  const char *name;
  size_t btb_size;
  size_t counter_table_size;
  size_t chooser_size;
  uint32_t ghr_bits;
  uint32_t local_history_bits = 0;
  size_t local_history_table_size = 0;
  bool direct_branch_target = false;
  bool direct_jal_target = false;
  predict_t predict_taken;
  update_t update;
};

std::string shell_quote(const std::string &value) {
  std::string result = "'";
  for (const char ch : value) {
    result += ch == '\'' ? "'\\''" : std::string(1, ch);
  }
  return result + "'";
}

btrace_pack_t open_trace(const std::string &path) {
  if (!path.ends_with(".bz2")) {
    FILE *file = std::fopen(path.c_str(), "rb");
    uint32_t count = 0;
    if (file == nullptr || std::fread(&count, sizeof(count), 1, file) != 1 ||
        std::fseek(file, 0, SEEK_END) != 0) {
      if (file != nullptr)
        std::fclose(file);
      std::fprintf(stderr, "failed to read trace header from %s\n", path.c_str());
      return nullptr;
    }
    const long size = std::ftell(file);
    std::fclose(file);
    const uint64_t expected = sizeof(count) + static_cast<uint64_t>(count) * sizeof(btrace_record_t);
    if (size < 0 || static_cast<uint64_t>(size) != expected) {
      std::fprintf(stderr, "invalid trace size for %s: got %ld, expected %llu\n",
                   path.c_str(), size, static_cast<unsigned long long>(expected));
      return nullptr;
    }
    return btrace_pack_open(path.c_str());
  }
  const std::string command = "bzip2 -dc -- " + shell_quote(path);
  FILE *pipe = popen(command.c_str(), "r");
  if (pipe == nullptr) {
    std::perror("popen");
    return nullptr;
  }

  btrace_pack_t pack = btrace_pack_open_fp(pipe, 1);
  if (pack == nullptr) {
    std::fprintf(stderr, "failed to open btrace pack stream from %s\n", path.c_str());
  }
  return pack;
}

DecodedBranch decode_branch(uint32_t pc, uint32_t code) {
  DecodedBranch decoded;
  decoded.is_conditional = is_conditional_branch(code);
  decoded.is_jal = is_jal(code);
  if (decoded.is_conditional) {
    decoded.direct_target_valid = true;
    decoded.direct_target = pc + get_b_imm(code);
  } else if (decoded.is_jal) {
    decoded.direct_target_valid = true;
    decoded.direct_target = pc + get_j_imm(code);
  }
  return decoded;
}

uint32_t choose_target(const AlgoConfig &algo, const PredictContext &ctx,
                       bool predict_taken_flag) {
  if (!predict_taken_flag) {
    return ctx.pc + 4;
  }
  if (ctx.decoded.is_conditional && algo.direct_branch_target &&
      ctx.decoded.direct_target_valid) {
    return ctx.decoded.direct_target;
  }
  if (ctx.decoded.is_jal && algo.direct_jal_target &&
      ctx.decoded.direct_target_valid) {
    return ctx.decoded.direct_target;
  }
  if (ctx.entry.valid) {
    return ctx.entry.target;
  }
  return ctx.pc + 4;
}

void default_update(const PredictContext &ctx, bool taken, uint32_t nxt_pc, BTB &btb) {
  if (taken) {
    btb.update(ctx.pc, nxt_pc, ctx.decoded.is_jal);
  }
}

AlgoConfig make_two_bit_algo(size_t btb_size, size_t bht_size) {
  auto bht = std::make_shared<CounterTable>(bht_size);
  return {
      .name = "2-bit saturating counter",
      .btb_size = btb_size,
      .counter_table_size = bht_size,
      .chooser_size = 0,
      .ghr_bits = 0,
      .predict_taken =
          [bht](const PredictContext &ctx) {
            if (ctx.decoded.is_jal) {
              return ctx.entry.valid && ctx.entry.is_jal;
            }
            if (!ctx.entry.valid) {
              return false;
            }
            return bht->prefer_high((ctx.pc >> 2) % bht->size());
          },
      .update =
          [bht](const PredictContext &ctx, bool taken, uint32_t nxt_pc, BTB &btb) {
            if (ctx.decoded.is_conditional) {
              bht->update((ctx.pc >> 2) % bht->size(), taken);
            }
            default_update(ctx, taken, nxt_pc, btb);
          },
  };
}

AlgoConfig make_gshare_algo(size_t btb_size, size_t pht_size, uint32_t ghr_bits,
                            bool direct_branch_target) {
  auto predictor = std::make_shared<GSharePredictor>(pht_size, ghr_bits);
  return {
      .name = direct_branch_target ? "gshare + direct branch target" : "gshare",
      .btb_size = btb_size,
      .counter_table_size = pht_size,
      .chooser_size = 0,
      .ghr_bits = ghr_bits,
      .direct_branch_target = direct_branch_target,
      .direct_jal_target = direct_branch_target,
      .predict_taken =
          [predictor, direct_branch_target](const PredictContext &ctx) {
            if (ctx.decoded.is_jal) {
              return direct_branch_target || (ctx.entry.valid && ctx.entry.is_jal);
            }
            if (ctx.decoded.is_conditional) {
              if (direct_branch_target) {
                return predictor->predict_taken(ctx.pc);
              }
              return ctx.entry.valid && predictor->predict_taken(ctx.pc);
            }
            return ctx.entry.valid && predictor->predict_taken(ctx.pc);
          },
      .update = [predictor](const PredictContext &ctx, bool taken, uint32_t nxt_pc,
                            BTB &btb) {
        if (ctx.decoded.is_conditional) {
          predictor->update(ctx.pc, taken);
        }
        default_update(ctx, taken, nxt_pc, btb);
      },
  };
}

AlgoConfig make_local_algo(size_t btb_size, size_t lht_size,
                           uint32_t local_history_bits, size_t local_pht_size,
                           bool direct_branch_target) {
  auto predictor = std::make_shared<LocalPredictor>(lht_size, local_history_bits,
                                                    local_pht_size);
  return {
      .name = direct_branch_target ? "local + direct branch target" : "local",
      .btb_size = btb_size,
      .counter_table_size = local_pht_size,
      .chooser_size = 0,
      .ghr_bits = 0,
      .local_history_bits = local_history_bits,
      .local_history_table_size = lht_size,
      .direct_branch_target = direct_branch_target,
      .direct_jal_target = direct_branch_target,
      .predict_taken =
          [predictor, direct_branch_target](const PredictContext &ctx) {
            if (ctx.decoded.is_jal) {
              return direct_branch_target || (ctx.entry.valid && ctx.entry.is_jal);
            }
            if (ctx.decoded.is_conditional) {
              return direct_branch_target
                         ? predictor->predict_taken(ctx.pc)
                         : ctx.entry.valid && predictor->predict_taken(ctx.pc);
            }
            return ctx.entry.valid;
          },
      .update = [predictor](const PredictContext &ctx, bool taken, uint32_t nxt_pc,
                            BTB &btb) {
        if (ctx.decoded.is_conditional) {
          predictor->update(ctx.pc, taken);
        }
        default_update(ctx, taken, nxt_pc, btb);
      },
  };
}

AlgoConfig make_local_gshare_tournament_algo(size_t btb_size, size_t lht_size,
                                             uint32_t local_history_bits,
                                             size_t local_pht_size,
                                             size_t chooser_size,
                                             uint32_t ghr_bits) {
  auto predictor = std::make_shared<LocalGlobalTournamentPredictor>(
      lht_size, local_history_bits, local_pht_size, local_pht_size, chooser_size,
      ghr_bits);
  return {
      .name = "local+gshare tournament + direct branch target",
      .btb_size = btb_size,
      .counter_table_size = local_pht_size,
      .chooser_size = chooser_size,
      .ghr_bits = ghr_bits,
      .local_history_bits = local_history_bits,
      .local_history_table_size = lht_size,
      .direct_branch_target = true,
      .direct_jal_target = true,
      .predict_taken =
          [predictor](const PredictContext &ctx) {
            if (ctx.decoded.is_jal) {
              return true;
            }
            if (ctx.decoded.is_conditional) {
              return predictor->predict_taken(ctx.pc);
            }
            return ctx.entry.valid;
          },
      .update = [predictor](const PredictContext &ctx, bool taken, uint32_t nxt_pc,
                            BTB &btb) {
        if (ctx.decoded.is_conditional) {
          predictor->update(ctx.pc, taken);
        }
        default_update(ctx, taken, nxt_pc, btb);
      },
  };
}

bool predict_btfn(const PredictContext &ctx) {
  return ctx.entry.valid && (ctx.entry.is_jal || ctx.entry.target < ctx.pc);
}

struct AlgoRunner {
  explicit AlgoRunner(AlgoConfig config)
      : algo(std::move(config)), btb(algo.btb_size) {}

  void process(const btrace_record_t &record) {
    PredictContext ctx = {
        .pc = record.pc,
        .code = record.code,
        .entry = btb.query(record.pc),
        .decoded = decode_branch(record.pc, record.code),
    };
    const bool predict_taken_flag = algo.predict_taken(ctx);
    const uint32_t prediction = choose_target(algo, ctx, predict_taken_flag);
    wrong += prediction != record.nxt_pc;
    total++;
    algo.update(ctx, record.nxt_pc != record.pc + 4, record.nxt_pc, btb);
  }

  AlgoConfig algo;
  BTB btb;
  size_t total = 0;
  size_t wrong = 0;
};

enum class RtlAllocationPolicy : uint8_t {
  Always,
  SkipConflictingNotTaken,
  ProtectJalFromBranches,
  SecondChanceJal,
};

struct RtlBTBEntry {
  uint32_t pc = 0;
  uint32_t target = 0;
  uint8_t direction_counter = 0;
  bool valid = false;
  bool is_jal = false;
  bool is_branch = false;
  bool jal_protected = false;
};

struct RtlModelConfig {
  std::string name;
  size_t btb_size;
  RtlAllocationPolicy allocation_policy;
  std::vector<unsigned> index_bits;
  size_t direction_table_size = 0;
  unsigned ghr_bits = 0;
};

struct RtlModelStats {
  uint64_t total = 0;
  uint64_t wrong = 0;
  uint64_t direction_wrong = 0;
  uint64_t target_wrong = 0;
  uint64_t no_prediction = 0;
  uint64_t skipped_not_taken_allocations = 0;
  std::array<uint64_t, static_cast<size_t>(ControlKind::Count)> total_by_kind{};
  std::array<uint64_t, static_cast<size_t>(ControlKind::Count)> wrong_by_kind{};
};

class RtlModelRunner {
public:
  explicit RtlModelRunner(RtlModelConfig config)
      : config_(config), entries_(config.btb_size),
        direction_counters_(config.direction_table_size, 1) {
    assert(std::has_single_bit(config.btb_size));
  }

  void process(const btrace_record_t &record) {
    const ControlKind kind = get_control_kind(record.code);
    const size_t kind_index = static_cast<size_t>(kind);
    const size_t index = get_index(record.pc);
    const RtlBTBEntry old_entry = entries_[index];
    const bool hit = old_entry.valid && old_entry.pc == record.pc;
    const bool branch_direction =
        config_.direction_table_size == 0
            ? old_entry.direction_counter >= 2
            : direction_counters_[get_direction_index(record.pc)] >= 2;
    const bool predicted_taken =
        hit && (old_entry.is_jal || (old_entry.is_branch && branch_direction));
    const uint32_t predicted_pc =
        predicted_taken ? old_entry.target : record.pc + 4;
    const bool actual_taken =
        kind != ControlKind::Branch || record.nxt_pc != record.pc + 4;
    const bool wrong = predicted_pc != record.nxt_pc;

    stats.total++;
    stats.total_by_kind[kind_index]++;
    if (wrong) {
      stats.wrong++;
      stats.wrong_by_kind[kind_index]++;
      if (predicted_taken != actual_taken) {
        stats.direction_wrong++;
      } else if (predicted_taken) {
        stats.target_wrong++;
      } else {
        stats.no_prediction++;
      }
    }

    const bool incoming_is_branch = kind == ControlKind::Branch;
    const bool incoming_is_jal =
        kind == ControlKind::Jal || kind == ControlKind::Ret;
    const bool matching_branch =
        hit && old_entry.is_branch && incoming_is_branch;
    if (incoming_is_branch && config_.direction_table_size != 0) {
      uint8_t &counter = direction_counters_[get_direction_index(record.pc)];
      if (actual_taken && counter != 3) {
        counter++;
      } else if (!actual_taken && counter != 0) {
        counter--;
      }
      if (config_.ghr_bits != 0) {
        const uint32_t mask = config_.ghr_bits >= 32
                                  ? ~0u
                                  : (1u << config_.ghr_bits) - 1;
        ghr_ = ((ghr_ << 1) | static_cast<uint32_t>(actual_taken)) & mask;
      }
    }
    const bool skip_conflicting_not_taken =
        config_.allocation_policy != RtlAllocationPolicy::Always &&
        incoming_is_branch && !actual_taken && !matching_branch;
    const bool protect_jal =
        config_.allocation_policy == RtlAllocationPolicy::ProtectJalFromBranches &&
        incoming_is_branch && !hit && old_entry.valid && old_entry.is_jal;
    const bool give_jal_second_chance =
        config_.allocation_policy == RtlAllocationPolicy::SecondChanceJal &&
        incoming_is_branch && !hit && old_entry.valid && old_entry.is_jal &&
        old_entry.jal_protected;
    const bool skip_allocation =
        skip_conflicting_not_taken || protect_jal || give_jal_second_chance;
    if (skip_allocation) {
      stats.skipped_not_taken_allocations++;
      if (give_jal_second_chance) {
        entries_[index].jal_protected = false;
      }
      return;
    }

    uint8_t next_direction = 0;
    if (incoming_is_branch) {
      if (!matching_branch) {
        next_direction = actual_taken ? 2 : 1;
      } else if (actual_taken && old_entry.direction_counter != 3) {
        next_direction = old_entry.direction_counter + 1;
      } else if (!actual_taken && old_entry.direction_counter != 0) {
        next_direction = old_entry.direction_counter - 1;
      } else {
        next_direction = old_entry.direction_counter;
      }
    }

    const DecodedBranch decoded =
        decode_branch(record.pc, record.code);
    uint32_t stored_target = record.nxt_pc;
    if (incoming_is_branch) {
      assert(decoded.direct_target_valid);
      stored_target = decoded.direct_target;
    }
    entries_[index] = {
        .pc = record.pc,
        .target = stored_target,
        .direction_counter = next_direction,
        .valid = true,
        .is_jal = incoming_is_jal,
        .is_branch = incoming_is_branch,
        .jal_protected = incoming_is_jal,
    };
  }

  const RtlModelConfig &config() const { return config_; }
  RtlModelStats stats;

private:
  size_t get_direction_index(uint32_t pc) const {
    const uint32_t history = config_.ghr_bits == 0 ? 0 : ghr_;
    return ((pc >> 2) ^ history) % config_.direction_table_size;
  }

  size_t get_index(uint32_t pc) const {
    if (!config_.index_bits.empty()) {
      size_t result = 0;
      for (size_t i = 0; i < config_.index_bits.size(); i++) {
        result |= ((pc >> (2 + config_.index_bits[i])) & 1u) << i;
      }
      return result;
    }
    return (pc >> 2) & (entries_.size() - 1);
  }

  RtlModelConfig config_;
  std::vector<RtlBTBEntry> entries_;
  std::vector<uint8_t> direction_counters_;
  uint32_t ghr_ = 0;
};

void print_algo_header(const AlgoConfig &algo) {
  std::printf("Testing %s algorithm: BTB size = %zu", algo.name, algo.btb_size);
  if (algo.counter_table_size != 0 && algo.ghr_bits == 0 &&
      algo.local_history_table_size == 0) {
    std::printf(", BHT size = %zu", algo.counter_table_size);
  } else if (algo.counter_table_size != 0) {
    std::printf(", PHT size = %zu", algo.counter_table_size);
    if (algo.ghr_bits != 0) {
      std::printf(", GHR bits = %u", algo.ghr_bits);
    }
  }
  if (algo.local_history_table_size != 0) {
    std::printf(", LHT size = %zu, local history bits = %u",
                algo.local_history_table_size, algo.local_history_bits);
  }
  if (algo.chooser_size != 0) {
    std::printf(", chooser size = %zu", algo.chooser_size);
  }
  if (algo.direct_branch_target) {
    std::printf(", direct branch target = on");
  }
  std::printf("\n");
}

} // namespace

int main(int argc, char **argv) {
  std::string trace_path = kDefaultTracePath;
  std::string json_path;
  bool rtl_only = false;
  bool rtl_index_search = false;
  for (int i = 1; i < argc; i++) {
    const std::string arg = argv[i];
    if ((arg == "--trace" || arg == "--json") && i + 1 < argc) {
      (arg == "--trace" ? trace_path : json_path) = argv[++i];
    } else if (arg == "--rtl-only") {
      rtl_only = true;
    } else if (arg == "--rtl-index-search") {
      rtl_index_search = true;
    } else if (arg == "--help") {
      std::printf(
          "usage: %s [--trace FILE.bin[.bz2]] [--json FILE|-] "
          "[--rtl-only] [--rtl-index-search]\n",
          argv[0]);
      return 0;
    } else {
      std::fprintf(stderr, "unknown or incomplete argument: %s\n", arg.c_str());
      return 2;
    }
  }

  std::vector<AlgoConfig> algorithms;
  if (!rtl_only) {
    for (size_t table_size : {kDefaultBTBSize, static_cast<size_t>(32),
                              static_cast<size_t>(64),
                              static_cast<size_t>(128)}) {
      algorithms.push_back({
          .name = "BTFN",
          .btb_size = table_size,
          .counter_table_size = 0,
          .chooser_size = 0,
          .ghr_bits = 0,
          .predict_taken = predict_btfn,
          .update = default_update,
      });
      algorithms.push_back(make_two_bit_algo(table_size, table_size));
      algorithms.push_back(
          make_gshare_algo(table_size, table_size, kDefaultGHRBits, true));
      algorithms.push_back(
          make_gshare_algo(table_size, table_size, kDefaultGHRBits, false));
      algorithms.push_back(make_local_algo(
          table_size, table_size, kDefaultLocalHistoryBits, table_size, true));
      algorithms.push_back(make_local_algo(
          table_size, table_size, kDefaultLocalHistoryBits, table_size, false));
      algorithms.push_back(make_local_gshare_tournament_algo(
          table_size, table_size, kDefaultLocalHistoryBits, table_size,
          table_size, kDefaultGHRBits));
    }
  }

  std::vector<AlgoRunner> runners;
  runners.reserve(algorithms.size());
  for (auto &algo : algorithms) {
    runners.emplace_back(std::move(algo));
  }
  std::vector<RtlModelRunner> rtl_runners;
  rtl_runners.emplace_back(RtlModelConfig{
      .name = "rtl-current",
      .btb_size = 32,
      .allocation_policy = RtlAllocationPolicy::Always,
      .index_bits = {},
  });
  rtl_runners.emplace_back(RtlModelConfig{
      .name = "rtl-skip-conflicting-not-taken",
      .btb_size = 32,
      .allocation_policy = RtlAllocationPolicy::SkipConflictingNotTaken,
      .index_bits = {},
  });
  rtl_runners.emplace_back(RtlModelConfig{
      .name = "rtl-current-capacity-64",
      .btb_size = 64,
      .allocation_policy = RtlAllocationPolicy::Always,
      .index_bits = {},
  });
  rtl_runners.emplace_back(RtlModelConfig{
      .name = "rtl-skip-conflicting-not-taken-capacity-64",
      .btb_size = 64,
      .allocation_policy = RtlAllocationPolicy::SkipConflictingNotTaken,
      .index_bits = {},
  });
  rtl_runners.emplace_back(RtlModelConfig{
      .name = "rtl-protect-jal-from-branches-capacity-64",
      .btb_size = 64,
      .allocation_policy = RtlAllocationPolicy::ProtectJalFromBranches,
      .index_bits = {},
  });
  rtl_runners.emplace_back(RtlModelConfig{
      .name = "rtl-second-chance-jal-capacity-64",
      .btb_size = 64,
      .allocation_policy = RtlAllocationPolicy::SecondChanceJal,
      .index_bits = {},
  });
  for (size_t direction_table_size : {32u, 64u, 128u, 256u}) {
    rtl_runners.emplace_back(RtlModelConfig{
        .name = "rtl-independent-bimodal-" +
                std::to_string(direction_table_size) + "-capacity-64",
        .btb_size = 64,
        .allocation_policy =
            RtlAllocationPolicy::SkipConflictingNotTaken,
        .index_bits = {},
        .direction_table_size = direction_table_size,
    });
  }
  for (size_t direction_table_size : {32u, 64u, 128u, 256u}) {
    rtl_runners.emplace_back(RtlModelConfig{
        .name = "rtl-independent-gshare-" +
                std::to_string(direction_table_size) + "-capacity-64",
        .btb_size = 64,
        .allocation_policy =
            RtlAllocationPolicy::SkipConflictingNotTaken,
        .index_bits = {},
        .direction_table_size = direction_table_size,
        .ghr_bits = 10,
    });
  }
  rtl_runners.emplace_back(RtlModelConfig{
      .name = "rtl-current-capacity-128",
      .btb_size = 128,
      .allocation_policy = RtlAllocationPolicy::Always,
      .index_bits = {},
  });
  rtl_runners.emplace_back(RtlModelConfig{
      .name = "rtl-skip-conflicting-not-taken-capacity-128",
      .btb_size = 128,
      .allocation_policy = RtlAllocationPolicy::SkipConflictingNotTaken,
      .index_bits = {},
  });
  if (rtl_index_search) {
    std::vector<std::vector<unsigned>> mappings;
    for (unsigned replaced = 0; replaced < 5; replaced++) {
      for (unsigned high = 5; high < 15; high++) {
        std::vector<unsigned> bits = {0, 1, 2, 3, 4};
        bits[replaced] = high;
        std::sort(bits.begin(), bits.end());
        mappings.push_back(std::move(bits));
      }
    }
    for (unsigned first = 1; first <= 10; first++) {
      mappings.push_back(
          {first, first + 1, first + 2, first + 3, first + 4});
    }
    std::sort(mappings.begin(), mappings.end());
    mappings.erase(std::unique(mappings.begin(), mappings.end()),
                   mappings.end());
    for (const auto &bits : mappings) {
      std::string suffix;
      for (const unsigned bit : bits) {
        if (!suffix.empty())
          suffix += "_";
        suffix += std::to_string(bit);
      }
      rtl_runners.emplace_back(RtlModelConfig{
          .name = "rtl-index-" + suffix,
          .btb_size = 32,
          .allocation_policy = RtlAllocationPolicy::Always,
          .index_bits = bits,
      });
      rtl_runners.emplace_back(RtlModelConfig{
          .name = "rtl-skip-not-taken-index-" + suffix,
          .btb_size = 32,
          .allocation_policy =
              RtlAllocationPolicy::SkipConflictingNotTaken,
          .index_bits = bits,
      });
    }
  }

  btrace_pack_t pack = open_trace(trace_path);
  if (pack == nullptr) {
    std::fprintf(stderr, "failed to open trace %s\n", trace_path.c_str());
    return 1;
  }
  btrace_record_t record;
  while (btrace_pack_pick(pack, &record) != 0) {
    for (auto &runner : runners) {
      runner.process(record);
    }
    for (auto &runner : rtl_runners) {
      runner.process(record);
    }
  }
  btrace_pack_close(pack);

  if (json_path != "-") {
    for (const auto &runner : runners) {
      print_algo_header(runner.algo);
      const size_t correct = runner.total - runner.wrong;
      std::printf("Total: %zu, Wrong: %zu, Correct: %zu, Accuracy: %.2f%%\n",
                  runner.total, runner.wrong, correct,
                  static_cast<double>(correct) / runner.total * 100.0);
    }
    for (const auto &runner : rtl_runners) {
      const auto &stats = runner.stats;
      std::printf("Testing %s: BTB size = %zu\n",
                  runner.config().name.c_str(),
                  runner.config().btb_size);
      std::printf("Total: %llu, Wrong: %llu, Accuracy: %.2f%%\n",
                  static_cast<unsigned long long>(stats.total),
                  static_cast<unsigned long long>(stats.wrong),
                  static_cast<double>(stats.total - stats.wrong) /
                      static_cast<double>(stats.total) * 100.0);
    }
  }

  if (!json_path.empty()) {
    FILE *json = json_path == "-" ? stdout : std::fopen(json_path.c_str(), "w");
    if (json == nullptr) {
      std::perror(json_path.c_str());
      return 1;
    }
    std::fprintf(json,
                 "{\n  \"schema\":1,\n  \"trace\":\"%s\",\n  "
                 "\"algorithms\":[\n",
                 trace_path.c_str());
    for (size_t i = 0; i < runners.size(); i++) {
      const auto &runner = runners[i];
      const double accuracy = runner.total == 0
                                  ? 0.0
                                  : static_cast<double>(runner.total - runner.wrong) /
                                        runner.total;
      std::fprintf(json,
                   "    %s{\"name\":\"%s\",\"btb_size\":%zu,"
                   "\"counter_table_size\":%zu,\"chooser_size\":%zu,"
                   "\"ghr_bits\":%u,\"local_history_bits\":%u,"
                   "\"local_history_table_size\":%zu,\"total\":%zu,"
                   "\"wrong\":%zu,\"accuracy\":%.9f}",
                   i ? ",\n    " : "", runner.algo.name, runner.algo.btb_size,
                   runner.algo.counter_table_size, runner.algo.chooser_size,
                   runner.algo.ghr_bits, runner.algo.local_history_bits,
                   runner.algo.local_history_table_size, runner.total,
                   runner.wrong, accuracy);
    }
    std::fprintf(json, "\n  ],\n  \"rtl_models\":[\n");
    for (size_t i = 0; i < rtl_runners.size(); i++) {
      const auto &runner = rtl_runners[i];
      const auto &stats = runner.stats;
      std::fprintf(
          json,
          "    %s{\"name\":\"%s\",\"btb_size\":%zu,"
          "\"allocation\":\"%s\",\"total\":%llu,\"wrong\":%llu,"
          "\"accuracy\":%.9f,\"direction_wrong\":%llu,"
          "\"target_wrong\":%llu,\"no_prediction\":%llu,"
          "\"skipped_not_taken_allocations\":%llu",
          i ? ",\n    " : "", runner.config().name.c_str(),
          runner.config().btb_size,
          runner.config().allocation_policy == RtlAllocationPolicy::Always
              ? "always"
              : runner.config().allocation_policy ==
                        RtlAllocationPolicy::SkipConflictingNotTaken
                    ? "skip_conflicting_not_taken"
                    : runner.config().allocation_policy ==
                              RtlAllocationPolicy::ProtectJalFromBranches
                          ? "protect_jal_from_branches"
                          : "second_chance_jal",
          static_cast<unsigned long long>(stats.total),
          static_cast<unsigned long long>(stats.wrong),
          stats.total == 0
              ? 0.0
              : static_cast<double>(stats.total - stats.wrong) /
                    static_cast<double>(stats.total),
          static_cast<unsigned long long>(stats.direction_wrong),
          static_cast<unsigned long long>(stats.target_wrong),
          static_cast<unsigned long long>(stats.no_prediction),
          static_cast<unsigned long long>(
              stats.skipped_not_taken_allocations));
      std::fprintf(json, ",\"index_bits\":[");
      for (size_t bit = 0; bit < runner.config().index_bits.size(); bit++) {
        std::fprintf(json, "%s%u", bit ? "," : "",
                     runner.config().index_bits[bit]);
      }
      std::fprintf(json, "],\"by_kind\":{");
      for (size_t kind = 0;
           kind < static_cast<size_t>(ControlKind::Count); kind++) {
        std::fprintf(
            json,
            "%s\"%s\":{\"total\":%llu,\"wrong\":%llu}",
            kind ? "," : "",
            control_kind_name(static_cast<ControlKind>(kind)),
            static_cast<unsigned long long>(stats.total_by_kind[kind]),
            static_cast<unsigned long long>(stats.wrong_by_kind[kind]));
      }
      std::fprintf(json, "}}");
    }
    std::fprintf(json, "\n  ]\n}\n");
    if (json != stdout) {
      std::fclose(json);
    }
  }

  return 0;
}
