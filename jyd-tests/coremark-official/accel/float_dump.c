#include "float_dump.h"

#include <string.h>

#ifndef MEM_STATIC
#define MEM_STATIC 0
#endif

extern char *mem_name[3];

static CORE_TICKS report_ticks;
static ee_u32 report_iterations;
static ee_u8 report_short_run;
static ee_u8 report_2k_performance;

ee_u32
__fp12_time_in_secs(CORE_TICKS ticks)
{
    ee_u32 seconds;

    report_ticks = ticks;
    seconds = ticks / COREMARK_TICKS_PER_SEC;
    return ticks != 0 && seconds == 0 ? 1 : seconds;
}

int
__fp12_banner(const char *fmt)
{
    report_ticks = 0;
    report_iterations = 0;
    report_short_run = 0;
    report_2k_performance =
        strcmp(fmt, "2K performance run parameters for coremark.\n") == 0;
    return ee_printf("%s", fmt);
}

int
__fp12_suppress_value(const char *fmt, int value)
{
    (void)fmt;
    (void)value;
    return 0;
}

int
__fp12_short_run(const char *fmt)
{
    (void)fmt;
    report_short_run = 1;
    return 0;
}

int
__fp12_iterations(const char *fmt, unsigned long iterations)
{
    report_iterations = (ee_u32)iterations;
    ee_print_ratio("Total time (secs): ",
                   report_ticks,
                   COREMARK_TICKS_PER_SEC,
                   1,
                   1);
    if (report_ticks != 0)
        ee_print_ratio("Iterations/Sec   : ",
                       report_iterations,
                       report_ticks,
                       COREMARK_TICKS_PER_SEC,
                       1);
    if (report_short_run)
        ee_printf("ERROR! Must execute for at least 10 secs for a valid result!\n");
    return ee_printf(fmt, iterations);
}

int
__fp12_validated(const char *fmt)
{
    int written = ee_printf("%s", fmt);

    if (!report_2k_performance)
        return written;

    ee_print_ratio("CoreMark 1.0 : ",
                   report_iterations,
                   report_ticks,
                   COREMARK_TICKS_PER_SEC,
                   0);
    written += ee_printf(" / %s %s", COMPILER_VERSION, COMPILER_FLAGS);
#if defined(MEM_LOCATION) && !defined(MEM_LOCATION_UNSPEC)
    written += ee_printf(" / %s", MEM_LOCATION);
#else
    written += ee_printf(" / %s", mem_name[MEM_METHOD]);
#endif
#if (MULTITHREAD > 1)
    written += ee_printf(" / %d:%s", default_num_contexts, PARALLEL_METHOD);
#endif
    written += ee_printf("\n");
    return written;
}
