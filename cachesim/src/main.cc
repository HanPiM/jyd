#include <bit>
#include <cassert>
#include <cstddef>
#include <cstdint>
#include <iostream>
#include <itrace_pack.h>
#include <vector>

uint32_t extractBits(uint32_t num, int high, int low) {
  assert(high >= low);
  assert(high < 32);
  assert(low >= 0);
  assert((high - low + 1) < 32);
  return (num >> low) & ((1u << (high - low + 1)) - 1);
}

class ICacheSim {
public:
  const size_t blockSize;
  const size_t blockNum;

  const uint32_t blockSizeWidth = std::countr_zero(blockSize);
  const uint32_t blockNumWidth = std::countr_zero(blockNum);
  const uint32_t tagWidth = 32 - blockSizeWidth - blockNumWidth;

  using addr_t = uint32_t;

  // store tag only
  std::vector<addr_t> blocks;
  std::vector<bool> valid;

  size_t totalMissCount = 0;
  size_t totalAccessCount = 0;

  size_t getBlockIndex(addr_t addr) {
    return extractBits(addr, blockSizeWidth + blockNumWidth - 1,
                       blockSizeWidth);
  }
  addr_t getTag(addr_t addr) {
    return extractBits(addr, 31, blockSizeWidth + blockNumWidth);
  }
  size_t getBlockOffset(addr_t addr) {
    return extractBits(addr, blockSizeWidth - 1, 0);
  }

  ICacheSim(size_t bSize, size_t bNum) : blockSize(bSize), blockNum(bNum) {
    blocks.resize(blockNum, 0);
    valid.resize(blockNum, false);
    printf("ICacheSim: blockSize=%zu, blockNum=%zu\n", blockSize, blockNum);
    assert(std::has_single_bit(blockSize));
    assert(std::has_single_bit(blockNum));
  }
  void access(addr_t addr) {
    totalAccessCount++;
    size_t bIndex = getBlockIndex(addr);
    addr_t tag = getTag(addr);
    if (!valid[bIndex] || blocks[bIndex] != tag) {
      // miss
      totalMissCount++;
      blocks[bIndex] = tag;
      valid[bIndex] = true;
    }
  }

  void dumpStats(double missPenalty) {
    printf("ICacheSim Stats:\n");
    printf("  Total Access: %zu\n", totalAccessCount);
    printf("  Total Miss:   %zu\n", totalMissCount);
    double hitCount = totalAccessCount - totalMissCount;
    printf("  Hit Rate:     %.2f%%\n", hitCount / totalAccessCount * 100.0);
    double totalMissTime = totalMissCount * missPenalty;
    printf("  Total Miss Time: %.2f cycles\n", totalMissTime);
  }
};

int main() {
  itrace_pack_t pack = itrace_pack_open("../nemu/itrace_pack.bin");
  assert(pack != NULL);

  ICacheSim icache(4, 16);

  uint32_t pc = 0;
  do {
    pc = itrace_pack_pickone(pack);
    if (pc != 0) {
			// printf("%08x\n", pc);
      icache.access(pc);
    }
  } while (pc != 0);
  icache.dumpStats(65.8862);

  return 0;
}
