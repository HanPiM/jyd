#ifndef __SDB_DIFFTEST_H__
#define __SDB_DIFFTEST_H__

#include <stdbool.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

enum { DIFFTEST_TO_DUT, DIFFTEST_TO_REF };

typedef struct {
  uint64_t v[2];
} difftest_freg_t;

typedef struct {
  difftest_freg_t fpr[32];
  uint32_t fcsr;
} riscv_fp_state;

typedef void (*difftest_fp_regcpy_t)(riscv_fp_state *state, bool direction);

#ifdef __cplusplus
}
#endif

#endif
