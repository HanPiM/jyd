#include <am.h>
#include <klib.h>
#include <stdint.h>

#define MAX_N 31

static int16_t a_storage[MAX_N + 2] __attribute__((aligned(4)));
static int16_t b_storage[MAX_N * MAX_N + 2] __attribute__((aligned(4)));

static void fail(unsigned mode, unsigned n, unsigned alignment,
                 uint32_t expected, uint32_t actual) {
  printf("xdotn mismatch mode=%u n=%u alignment=%u expected=%x actual=%x\n",
         mode, n, alignment, expected, actual);
  halt(1);
}

static inline void xdotn_config(unsigned n) {
  asm volatile(".insn r 0x0b, 4, 3, x0, %0, x0" : : "r"(n) : "memory");
}

static inline uint32_t xdotn_signed(const int16_t *a, const int16_t *b) {
  uint32_t result;
  asm volatile(".insn r 0x0b, 4, 4, %0, %1, %2"
               : "=r"(result)
               : "r"(a), "r"(b)
               : "memory");
  return result;
}

static inline uint32_t xdotn_bit(const int16_t *a, const int16_t *b) {
  uint32_t result;
  asm volatile(".insn r 0x0b, 4, 5, %0, %1, %2"
               : "=r"(result)
               : "r"(a), "r"(b)
               : "memory");
  return result;
}

static uint32_t reference_signed(const int16_t *a, const int16_t *b,
                                 unsigned n) {
  uint32_t result = 0;
  for (unsigned k = 0; k < n; k++)
    result += (uint32_t)((int32_t)a[k] * (int32_t)b[k * n]);
  return result;
}

static uint32_t reference_bit(const int16_t *a, const int16_t *b,
                              unsigned n) {
  uint32_t result = 0;
  for (unsigned k = 0; k < n; k++) {
    uint32_t product = (uint32_t)((int32_t)a[k] * (int32_t)b[k * n]);
    result += ((product >> 2) & 0xfu) * ((product >> 5) & 0x7fu);
  }
  return result;
}

static void verify(const int16_t *a, const int16_t *b, unsigned n,
                   unsigned alignment) {
  uint32_t expected_signed = reference_signed(a, b, n);
  uint32_t expected_bit = reference_bit(a, b, n);

  for (unsigned repeat = 0; repeat < 3; repeat++) {
    uint32_t actual_signed = xdotn_signed(a, b);
    uint32_t actual_bit = xdotn_bit(a, b);
    if (actual_signed != expected_signed)
      fail(0, n, alignment, expected_signed, actual_signed);
    if (actual_bit != expected_bit)
      fail(1, n, alignment, expected_bit, actual_bit);
  }
}

static void run_case(unsigned n, unsigned alignment) {
  int16_t *a = a_storage + alignment;
  int16_t *b = b_storage + alignment;

  for (unsigned k = 0; k < n; k++)
    a[k] = (int16_t)((int)(k * 37u + n * 11u) % 257 - 128);
  for (unsigned row = 0; row < n; row++)
    for (unsigned column = 0; column < n; column++)
      b[row * n + column] =
          (int16_t)((int)(row * 19u + column * 23u + n * 7u) % 251 - 125);

  xdotn_config(n);
  verify(a, b, n, alignment);

  if (n != 0) {
    volatile uint8_t *a_bytes = (volatile uint8_t *)a;
    a_bytes[(n / 2) * sizeof(*a)] ^= 0x5au;
    verify(a, b, n, alignment);

    volatile int16_t *a_halves = (volatile int16_t *)a;
    a_halves[n - 1] ^= (int16_t)0x1357;
    verify(a, b, n, alignment);
  }
}

int main(void) {
  static const unsigned lengths[] = {0, 1, 2, 8, 9, 15, 16, 17, 31};

  for (unsigned alignment = 0; alignment < 2; alignment++)
    for (unsigned test = 0; test < sizeof(lengths) / sizeof(lengths[0]); test++)
      run_case(lengths[test], alignment);

  printf("xdotn-directed: PASS\n");
  return 0;
}
