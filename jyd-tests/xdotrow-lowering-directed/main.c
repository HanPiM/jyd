#include <stdint.h>
#include <stdio.h>

#define MAX_N 17u

static int16_t a[MAX_N * MAX_N] __attribute__((aligned(4)));
static int16_t b[MAX_N * MAX_N] __attribute__((aligned(4)));
static uint32_t c[MAX_N * MAX_N] __attribute__((aligned(4)));
static uint32_t expected[MAX_N * MAX_N] __attribute__((aligned(4)));
static uint32_t alias_data[MAX_N * MAX_N] __attribute__((aligned(4)));
static uint32_t alias_reference[MAX_N * MAX_N] __attribute__((aligned(4)));

__attribute__((noinline)) static void matrix_signed(unsigned n, uint32_t *out,
                                                     int16_t *left, int16_t *right) {
  for (unsigned i = 0; i < n; i++) {
    for (unsigned j = 0; j < n; j++) {
      out[i * n + j] = 0;
      for (unsigned k = 0; k < n; k++)
        out[i * n + j] +=
            (uint32_t)((int32_t)left[i * n + k] * (int32_t)right[k * n + j]);
    }
  }
}

__attribute__((noinline)) static void matrix_bit(unsigned n, uint32_t *out,
                                                  int16_t *left, int16_t *right) {
  for (unsigned i = 0; i < n; i++) {
    for (unsigned j = 0; j < n; j++) {
      out[i * n + j] = 0;
      for (unsigned k = 0; k < n; k++) {
        uint32_t product =
            (uint32_t)((int32_t)left[i * n + k] * (int32_t)right[k * n + j]);
        out[i * n + j] += ((product >> 2) & 0xfu) * ((product >> 5) & 0x7fu);
      }
    }
  }
}

static void reference(unsigned n, unsigned bit_mode) {
  for (unsigned i = 0; i < n; i++) {
    for (unsigned j = 0; j < n; j++) {
      uint32_t sum = 0;
      for (unsigned k = 0; k < n; k++) {
        uint32_t product =
            (uint32_t)((int32_t)a[i * n + k] * (int32_t)b[k * n + j]);
        sum += bit_mode ? ((product >> 2) & 0xfu) * ((product >> 5) & 0x7fu) : product;
      }
      expected[i * n + j] = sum;
    }
  }
}

static void reference_alias(unsigned n, volatile uint32_t *out, volatile int16_t *left,
                            volatile int16_t *right, unsigned bit_mode) {
  for (unsigned i = 0; i < n; i++) {
    for (unsigned j = 0; j < n; j++) {
      out[i * n + j] = 0;
      for (unsigned k = 0; k < n; k++) {
        uint32_t product =
            (uint32_t)((int32_t)left[i * n + k] * (int32_t)right[k * n + j]);
        out[i * n + j] +=
            bit_mode ? ((product >> 2) & 0xfu) * ((product >> 5) & 0x7fu) : product;
      }
    }
  }
}

static void fail(unsigned n, unsigned bit_mode, unsigned index, uint32_t want,
                 uint32_t got) {
  printf("xdotrow lowering failed: n=%u mode=%u index=%u expected=%08x actual=%08x\n", n,
         bit_mode, index, want, got);
  __builtin_trap();
}

static void run_case(unsigned n, unsigned bit_mode) {
  unsigned elements = n * n;
  for (unsigned i = 0; i < elements; i++) {
    a[i] = (int16_t)((int)(i * 29u + n * 13u) % 257 - 128);
    b[i] = (int16_t)((int)(i * 17u + n * 31u) % 251 - 125);
    c[i] = 0xdeadbeefu;
  }

  reference(n, bit_mode);
  if (bit_mode)
    matrix_bit(n, c, a, b);
  else
    matrix_signed(n, c, a, b);

  for (unsigned i = 0; i < elements; i++)
    if (c[i] != expected[i])
      fail(n, bit_mode, i, expected[i], c[i]);
}

static void run_alias_case(unsigned bit_mode, unsigned alias_b) {
  const unsigned n = 4;
  for (unsigned i = 0; i < n * n; i++) {
    a[i] = (int16_t)((int)(i * 19u) - 73);
    b[i] = (int16_t)((int)(i * 23u) - 91);
    alias_data[i] = i * 0x000d0007u + 0x00110009u;
    alias_reference[i] = alias_data[i];
  }

  int16_t *target_left = alias_b ? a : (int16_t *)alias_data;
  int16_t *target_right = alias_b ? (int16_t *)alias_data : b;
  volatile int16_t *reference_left = alias_b ? a : (volatile int16_t *)alias_reference;
  volatile int16_t *reference_right = alias_b ? (volatile int16_t *)alias_reference : b;

  reference_alias(n, alias_reference, reference_left, reference_right, bit_mode);
  if (bit_mode)
    matrix_bit(n, alias_data, target_left, target_right);
  else
    matrix_signed(n, alias_data, target_left, target_right);

  for (unsigned i = 0; i < n * n; i++)
    if (alias_data[i] != alias_reference[i])
      fail(n, 2 + bit_mode * 2 + alias_b, i, alias_reference[i], alias_data[i]);
}

int main(void) {
  static const unsigned lengths[] = {0, 1, 3, 4, 9, 16, 17};

  for (unsigned mode = 0; mode < 2; mode++)
    for (unsigned test = 0; test < sizeof(lengths) / sizeof(lengths[0]); test++)
      run_case(lengths[test], mode);

  run_alias_case(0, 0);
  run_alias_case(0, 1);
  run_alias_case(1, 0);
  run_alias_case(1, 1);

  printf("xdotrow-lowering-directed: PASS\n");
  return 0;
}
