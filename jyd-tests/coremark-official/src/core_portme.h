/*
 * JYD AbstractMachine port for the official EEMBC CoreMark sources.
 *
 * The benchmark sources remain unmodified.  Startup, UART, timer, halt and
 * device support are supplied by the AM libraries selected by ARCH.
 */
#ifndef CORE_PORTME_H
#define CORE_PORTME_H

#include <am.h>
#include <klib-macros.h>
#include <stdint.h>

#ifndef ITERATIONS
#define ITERATIONS 1000
#endif
#define MEM_METHOD MEM_STATIC

#define HAS_FLOAT 1
#define HAS_TIME_H 0
#define USE_CLOCK 0
#define HAS_STDIO 0
/* Use EEMBC's open-source barebones formatter, not AM klib printf. */
#define HAS_PRINTF 0

#define COMPILER_VERSION "GCC" __VERSION__
#ifndef COMPILER_FLAGS
#define COMPILER_FLAGS "unknown"
#endif
#define MEM_LOCATION mem_name[MEM_METHOD]

typedef signed short ee_s16;
typedef unsigned short ee_u16;
typedef signed int ee_s32;
typedef double ee_f32;
typedef unsigned char ee_u8;
typedef unsigned int ee_u32;
typedef ee_u32 ee_ptr_int;
typedef size_t ee_size_t;

#define align_mem(x) (void *)(4 + (((ee_ptr_int)(x) - 1) & ~3))

#define CORETIMETYPE ee_u32
typedef ee_u32 CORE_TICKS;

#ifdef COREMARK_EMBEDDED_RTT
#define COREMARK_TICKS_PER_SEC 1000u
#elif defined(ARCH_IS_NEMU)
#define COREMARK_TICKS_PER_SEC 1000000u
#else
#define COREMARK_TICKS_PER_SEC 50000000u
#endif

#define SEED_METHOD SEED_VOLATILE

#define MULTITHREAD 1
#define USE_PTHREAD 0
#define USE_FORK 0
#define USE_SOCKET 0

#define MAIN_HAS_NOARGC 1
#define MAIN_HAS_NORETURN 0

#ifdef COREMARK_EMBEDDED_RTT
#define main coremark_main
#endif

extern ee_u32 default_num_contexts;

typedef struct CORE_PORTABLE_S {
  ee_u8 portable_id;
} core_portable;

void portable_init(core_portable *p, int *argc, char *argv[]);
void portable_fini(core_portable *p);

#if !defined(PROFILE_RUN) && !defined(PERFORMANCE_RUN) && !defined(VALIDATION_RUN)
#if (TOTAL_DATA_SIZE == 1200)
#define PROFILE_RUN 1
#elif (TOTAL_DATA_SIZE == 2000)
#define PERFORMANCE_RUN 1
#else
#define VALIDATION_RUN 1
#endif
#endif

int ee_printf(const char *fmt, ...);

#endif
