#include "../public/btrace_pack.h"

#include <bit>
#include <cassert>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <functional>
#include <memory>
#include <vector>

namespace {

constexpr const char *kCompressedTracePath = "../nemu/btrace_pack.bin.bz2";
constexpr size_t kDefaultBTBSize = 16;
constexpr size_t kDefaultCounterTableSize = 16;
constexpr uint32_t kDefaultGHRBits = 10;

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

class TournamentPredictor {
public:
  TournamentPredictor(size_t bimodal_size, size_t gshare_size,
                      size_t chooser_size, uint32_t ghr_bits)
      : bimodal_(bimodal_size), gshare_(gshare_size, ghr_bits),
        chooser_(chooser_size, 2) {}

  bool predict_taken(uint32_t pc) const {
    const bool bimodal_taken = bimodal_.prefer_high(get_bimodal_index(pc));
    const bool gshare_taken = gshare_.predict_taken(pc);
    return chooser_.prefer_high(get_chooser_index(pc)) ? gshare_taken
                                                        : bimodal_taken;
  }

  void update(uint32_t pc, bool taken) {
    const size_t chooser_index = get_chooser_index(pc);
    const bool bimodal_taken = bimodal_.prefer_high(get_bimodal_index(pc));
    const bool gshare_taken = gshare_.predict_taken(pc);
    if (bimodal_taken != gshare_taken) {
      if (gshare_taken == taken) {
        chooser_.update(chooser_index, true);
      } else if (bimodal_taken == taken) {
        chooser_.update(chooser_index, false);
      }
    }
    bimodal_.update(get_bimodal_index(pc), taken);
    gshare_.update(pc, taken);
  }

private:
  size_t get_bimodal_index(uint32_t pc) const { return (pc >> 2) % bimodal_.size(); }
  size_t get_chooser_index(uint32_t pc) const { return (pc >> 2) % chooser_.size(); }

  CounterTable bimodal_;
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
  bool direct_branch_target = false;
  bool direct_jal_target = false;
  predict_t predict_taken;
  update_t update;
};

btrace_pack_t open_compressed_pack() {
  char command[512];
  const int written =
      std::snprintf(command, sizeof(command), "bzcat %s", kCompressedTracePath);
  if (written <= 0 || static_cast<size_t>(written) >= sizeof(command)) {
    std::fprintf(stderr, "failed to build bzcat command for %s\n",
                 kCompressedTracePath);
    return nullptr;
  }

  FILE *pipe = popen(command, "r");
  if (pipe == nullptr) {
    std::perror("popen");
    return nullptr;
  }

  btrace_pack_t pack = btrace_pack_open_fp(pipe, 1);
  if (pack == nullptr) {
    std::fprintf(stderr, "failed to open btrace pack stream from %s\n",
                 kCompressedTracePath);
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

AlgoConfig make_tournament_algo(size_t btb_size, size_t counter_table_size,
                                size_t chooser_size, uint32_t ghr_bits) {
  auto predictor = std::make_shared<TournamentPredictor>(
      counter_table_size, counter_table_size, chooser_size, ghr_bits);
  return {
      .name = "tournament + direct branch target",
      .btb_size = btb_size,
      .counter_table_size = counter_table_size,
      .chooser_size = chooser_size,
      .ghr_bits = ghr_bits,
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

bool test_algo(const AlgoConfig &algo) {
  btrace_pack_t pack = open_compressed_pack();
  if (pack == nullptr) {
    return false;
  }

  btrace_record_t record;
  size_t total = 0;
  size_t wrong = 0;
  BTB btb(algo.btb_size);

  while (btrace_pack_pick(pack, &record) != 0) {
    PredictContext ctx = {
        .pc = record.pc,
        .code = record.code,
        .entry = btb.query(record.pc),
        .decoded = decode_branch(record.pc, record.code),
    };
    const bool predict_taken_flag = algo.predict_taken(ctx);
    const uint32_t prediction = choose_target(algo, ctx, predict_taken_flag);
    if (prediction != record.nxt_pc) {
      wrong++;
    }
    total++;
    algo.update(ctx, record.nxt_pc != record.pc + 4, record.nxt_pc, btb);
  }

  btrace_pack_close(pack);

  const size_t correct = total - wrong;
  std::printf("Total: %zu, Wrong: %zu, Correct: %zu, Accuracy: %.2f%%\n", total,
              wrong, correct, static_cast<double>(correct) / total * 100.0);
  return true;
}

void print_algo_header(const AlgoConfig &algo) {
  std::printf("Testing %s algorithm: BTB size = %zu", algo.name, algo.btb_size);
  if (algo.counter_table_size != 0 && algo.ghr_bits == 0) {
    std::printf(", BHT size = %zu", algo.counter_table_size);
  } else if (algo.counter_table_size != 0) {
    std::printf(", PHT size = %zu, GHR bits = %u", algo.counter_table_size,
                algo.ghr_bits);
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

int main() {
  const std::vector<AlgoConfig> algorithms = {
      {
          .name = "BTFN",
          .btb_size = kDefaultBTBSize,
          .counter_table_size = 0,
          .chooser_size = 0,
          .ghr_bits = 0,
          .predict_taken = predict_btfn,
          .update = default_update,
      },
      make_two_bit_algo(kDefaultBTBSize, kDefaultCounterTableSize),
      make_gshare_algo(kDefaultBTBSize, kDefaultCounterTableSize, kDefaultGHRBits,
                       false),
      make_gshare_algo(kDefaultBTBSize, kDefaultCounterTableSize, kDefaultGHRBits,
                       true),
      make_tournament_algo(kDefaultBTBSize, kDefaultCounterTableSize,
                           kDefaultCounterTableSize, kDefaultGHRBits),
  };

  for (const auto &algo : algorithms) {
    print_algo_header(algo);
    if (!test_algo(algo)) {
      return 1;
    }
  }

  return 0;
}
