#include <stdbool.h>
#include <stdint.h>

#include <softfloat.h>

typedef union {
  double value;
  uint64_t bits;
} am_double_bits;

static float64_t to_softfloat(double value) {
  am_double_bits bits = {.value = value};
  return (float64_t){.v = bits.bits};
}

static double from_softfloat(float64_t value) {
  am_double_bits bits = {.bits = value.v};
  return bits.value;
}

double am_softfloat_modf(double value, double *integer_part) {
  double integer = from_softfloat(
      f64_roundToInt(to_softfloat(value), softfloat_round_minMag, false));
  *integer_part = integer;
  return from_softfloat(f64_sub(to_softfloat(value), to_softfloat(integer)));
}

double __adddf3(double a, double b) {
  return from_softfloat(f64_add(to_softfloat(a), to_softfloat(b)));
}

double __subdf3(double a, double b) {
  return from_softfloat(f64_sub(to_softfloat(a), to_softfloat(b)));
}

double __muldf3(double a, double b) {
  return from_softfloat(f64_mul(to_softfloat(a), to_softfloat(b)));
}

double __divdf3(double a, double b) {
  return from_softfloat(f64_div(to_softfloat(a), to_softfloat(b)));
}

double __floatunsidf(unsigned int value) {
  return from_softfloat(ui32_to_f64(value));
}

int __fixdfsi(double value) {
  return f64_to_i32_r_minMag(to_softfloat(value), false);
}

unsigned int __fixunsdfsi(double value) {
  return f64_to_ui32_r_minMag(to_softfloat(value), false);
}

int __eqdf2(double a, double b) {
  return f64_eq(to_softfloat(a), to_softfloat(b)) ? 0 : 1;
}

int __nedf2(double a, double b) {
  return __eqdf2(a, b);
}

int __ltdf2(double a, double b) {
  if (f64_lt(to_softfloat(a), to_softfloat(b))) return -1;
  return f64_eq(to_softfloat(a), to_softfloat(b)) ? 0 : 1;
}

int __gtdf2(double a, double b) {
  if (f64_lt(to_softfloat(b), to_softfloat(a))) return 1;
  return f64_eq(to_softfloat(a), to_softfloat(b)) ? 0 : -1;
}
