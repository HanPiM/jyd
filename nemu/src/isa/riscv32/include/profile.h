#ifndef __RISCV32_PROFILE_H__
#define __RISCV32_PROFILE_H__

#include <cpu/decode.h>

void riscv_profile_set_output(const char *path);
bool riscv_profile_enabled(void);
void riscv_profile_record(const Decode *s, word_t inst, word_t rs1_before,
                          word_t rs2_before);
void riscv_profile_record_xaccel(unsigned op, uint64_t units,
                                 uint64_t modeled_cycles);
void riscv_profile_record_xstatec(unsigned op);
void riscv_profile_finish(void);

#endif
