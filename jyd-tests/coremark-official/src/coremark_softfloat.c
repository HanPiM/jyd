/*
 * Adapter between EEMBC's barebones cvt.c and the AM SoftFloat library.
 */
#include <am-softfloat.h>

double coremark_modf(double value, double *integer_part) {
  return am_softfloat_modf(value, integer_part);
}
