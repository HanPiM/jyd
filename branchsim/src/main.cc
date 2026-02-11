#include "../public/btrace_pack.h"
#include <bit>
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <functional>

uint32_t GetBTypeIMMFrom(uint32_t code) {
	uint32_t imm12 = (code >> 31) & 1;
	uint32_t imm11 = (code >> 7) & 1;
	uint32_t imm10to5 = (code >> 25) & 0x3f;
	uint32_t imm4to1 = (code >> 8) & 0xf;
	return (imm12 << 12) | (imm11 << 11) | (imm10to5 << 5) | (imm4to1 << 1);
}
uint32_t GetFunc3tFrom(uint32_t code) {
	return (code >> 12) & 0x7;
}

using algo_t = std::function<bool(uint32_t pc, uint32_t func3t, uint32_t imm)>;

bool algo_always_take(uint32_t, uint32_t, uint32_t) { return true; }
bool algo_always_not_take(uint32_t, uint32_t, uint32_t) { return false; }
bool algo_BTFN(uint32_t , uint32_t , uint32_t imm) {
	// BTFN: Backward Taken, Forward Not taken
	if (imm & (1 << 12)) // backward
		return true;
	else
		return false;
}

void test_algo(algo_t algo) {
  btrace_pack_t pack = btrace_pack_open("../nemu/btrace_pack.bin");
  btrace_record_t record;

  size_t total = 0, wrong = 0;

  while (true) {
    if (btrace_pack_pick(pack, &record) == 0)
      break;
		uint32_t code = record.code;
		uint32_t imm = GetBTypeIMMFrom(code);
		uint32_t func3t = GetFunc3tFrom(code);
    bool ans_taken = record.nxt_pc != (record.pc + 4);
    bool pred_taken = algo(record.pc, func3t, imm);
    if (ans_taken != pred_taken) {
      wrong++;
      // printf(
      //     "Wrong prediction at pc: %08x, func3t: %d, imm: %d, code: %08x, ans: %d nxt_pc: %08x\n",
      //     record.pc, func3t, imm, code, ans_taken, record.nxt_pc);
    }
    total++;
  }
  btrace_pack_close(pack);
  size_t correct = total - wrong;
  printf("Total: %zu, Wrong: %zu, Correct: %zu, Accuracy: %.2f%%\n", total,
         wrong, correct, (double)correct / total * 100);
}

int main() { 
	printf("Testing always not take algorithm:\n");
	test_algo(algo_always_not_take);
	printf("Testing BTFN algorithm:\n");
	test_algo(algo_BTFN);
	return 0;
}
