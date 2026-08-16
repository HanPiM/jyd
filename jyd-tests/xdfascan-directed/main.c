#include <am.h>
#include <klib.h>
#include <stdint.h>

static uint32_t reference_transitions[8];
static uint32_t reference_finals[8];
static uint8_t storage[384] __attribute__((aligned(4)));

static void fail(unsigned kind, unsigned state, uint32_t expected,
                 uint32_t actual) {
  printf("xdfascan mismatch kind=%u state=%u expected=%u actual=%u\n", kind,
         state, expected, actual);
  halt(1);
}

static void reference_commit(uint32_t mask, uint32_t state) {
  for (unsigned next_state = 0; next_state < 8; next_state++)
    if (mask & (1u << next_state))
      reference_transitions[next_state]++;
  reference_finals[state]++;
}

static void reference_scan(const uint8_t *input) {
  uint32_t state = 0;
  uint32_t pending_mask = 0;

  for (;;) {
    uint8_t symbol = *input;
    if (symbol == 0) {
      if (pending_mask != 0)
        reference_commit(pending_mask, state);
      return;
    }
    input++;

    if (symbol == ',') {
      reference_commit(pending_mask, state);
      pending_mask = 0;
      state = 0;
      continue;
    }

    bool digit = (uint8_t)(symbol - '0') <= 9;
    switch (state) {
    case 0:
      if (digit)
        state = 4;
      else if (symbol == '+' || symbol == '-')
        state = 2;
      else if (symbol == '.')
        state = 5;
      else {
        state = 1;
        pending_mask |= 1u << 1;
      }
      pending_mask |= 1u << 0;
      break;
    case 2:
      if (digit)
        state = 4;
      else if (symbol == '.')
        state = 5;
      else
        state = 1;
      pending_mask |= 1u << 2;
      break;
    case 4:
      if (symbol == '.') {
        state = 5;
        pending_mask |= 1u << 4;
      } else if (!digit) {
        state = 1;
        pending_mask |= 1u << 4;
      }
      break;
    case 5:
      if (symbol == 'E' || symbol == 'e') {
        state = 3;
        pending_mask |= 1u << 5;
      } else if (!digit) {
        state = 1;
        pending_mask |= 1u << 5;
      }
      break;
    case 3:
      state = (symbol == '+' || symbol == '-') ? 6 : 1;
      pending_mask |= 1u << 3;
      break;
    case 6:
      state = digit ? 7 : 1;
      pending_mask |= 1u << 6;
      break;
    case 7:
      if (!digit) {
        state = 1;
        pending_mask |= 1u << 1;
      }
      break;
    default:
      halt(1);
    }

    if (state == 1) {
      reference_commit(pending_mask, state);
      pending_mask = 0;
      state = 0;
    }
  }
}

static inline void xdfa_init(void) {
  asm volatile(".insn r 0x5b, 0, 0, x0, x0, x0" ::: "memory");
}

static inline const uint8_t *xdfa_scan(const uint8_t *input) {
  const uint8_t *result;
  asm volatile(".insn r 0x5b, 5, 3, %0, x0, %1"
               : "=r"(result)
               : "r"(input)
               : "memory");
  return result;
}

static inline uint32_t xdfa_transition_read(unsigned state) {
  uint32_t result;
  asm volatile(".insn r 0x5b, 2, 0, %0, %1, x0"
               : "=r"(result)
               : "r"(state));
  return result;
}

static inline uint32_t xdfa_final_read(unsigned state) {
  uint32_t result;
  asm volatile(".insn r 0x5b, 2, 1, %0, %1, x0"
               : "=r"(result)
               : "r"(state));
  return result;
}

static void verify_counters(void) {
  for (unsigned state = 0; state < 8; state++) {
    uint32_t transition = xdfa_transition_read(state);
    uint32_t final = xdfa_final_read(state);
    if (transition != reference_transitions[state])
      fail(0, state, reference_transitions[state], transition);
    if (final != reference_finals[state])
      fail(1, state, reference_finals[state], final);
  }
}

static void run_case(const char *source, unsigned alignment) {
  static const uint8_t sentinel[] = ",9e+2,invalid";
  size_t length = strlen(source);
  uint8_t *input = storage + alignment;

  memset(storage, 0xa5, sizeof(storage));
  memcpy(input, source, length + 1);
  memcpy(input + length + 1, sentinel, sizeof(sentinel));
  memset(reference_transitions, 0, sizeof(reference_transitions));
  memset(reference_finals, 0, sizeof(reference_finals));
  xdfa_init();

  for (unsigned repeat = 0; repeat < 2; repeat++) {
    reference_scan(input);
    const uint8_t *end = xdfa_scan(input);
    if (end != input + length)
      fail(2, alignment, (uintptr_t)(input + length), (uintptr_t)end);
    verify_counters();
  }
}

int main(void) {
  static const char *const cases[] = {
      "",
      ",",
      ",,",
      "123",
      "+.123e-4,-99.0,12E+7",
      "x1,2q3,4",
      ".,+,E,1e,1e+",
      "0012345678901234567890,9",
  };

  for (unsigned alignment = 0; alignment < 4; alignment++)
    for (unsigned test = 0; test < sizeof(cases) / sizeof(cases[0]); test++)
      run_case(cases[test], alignment);

  char generated[256];
  unsigned cursor = 0;
  while (cursor + 8 < sizeof(generated)) {
    static const char token[] = "12.5e+3,";
    memcpy(generated + cursor, token, sizeof(token) - 1);
    cursor += sizeof(token) - 1;
  }
  memcpy(generated + cursor, "bad", 4);
  for (unsigned alignment = 0; alignment < 4; alignment++)
    run_case(generated, alignment);

  printf("xdfascan-directed: PASS\n");
  return 0;
}
