#ifndef FLOAT_DUMP_H
#define FLOAT_DUMP_H

#include "core_portme.h"

ee_u32 __fp12_time_in_secs(CORE_TICKS ticks);
int __fp12_banner(const char *fmt);
int __fp12_suppress_value(const char *fmt, int value);
int __fp12_short_run(const char *fmt);
int __fp12_iterations(const char *fmt, unsigned long iterations);
int __fp12_validated(const char *fmt);

#endif
