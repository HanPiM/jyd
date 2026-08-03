#include "coremark.h"
#include "core_portme.h"

#if VALIDATION_RUN
volatile ee_s32 seed1_volatile = 0x3415;
volatile ee_s32 seed2_volatile = 0x3415;
volatile ee_s32 seed3_volatile = 0x66;
#endif
#if PERFORMANCE_RUN
volatile ee_s32 seed1_volatile = 0x0;
volatile ee_s32 seed2_volatile = 0x0;
volatile ee_s32 seed3_volatile = 0x66;
#endif
#if PROFILE_RUN
volatile ee_s32 seed1_volatile = 0x8;
volatile ee_s32 seed2_volatile = 0x8;
volatile ee_s32 seed3_volatile = 0x8;
#endif
volatile ee_s32 seed4_volatile = ITERATIONS;
volatile ee_s32 seed5_volatile = 0;

#ifndef COREMARK_EMBEDDED_RTT
/* CoreMark only needs the timer.  Calling the timer backend directly avoids
 * retaining AM's generic IOE dispatch table and all device handlers. */
void __am_timer_init(void);
void __am_timer_uptime(AM_TIMER_UPTIME_T *uptime);
#endif

static uint32_t uptime_ticks(void) {
#ifdef COREMARK_EMBEDDED_RTT
  return io_read(AM_TIMER_UPTIME).us / 1000;
#else
  extern uint32_t CNT_REG[];
  return CNT_REG[0];
#endif
}

static uint32_t start_time_val;
static uint32_t stop_time_val;

void start_time(void) {
  start_time_val = uptime_ticks();
}

void stop_time(void) {
  stop_time_val = uptime_ticks();
}

CORE_TICKS get_time(void) {
  return stop_time_val - start_time_val;
}

secs_ret time_in_secs(CORE_TICKS ticks) {
  return (secs_ret)ticks / (secs_ret)COREMARK_TICKS_PER_SEC;
}

ee_u32 default_num_contexts = 1;

void portable_init(core_portable *p, int *argc, char *argv[]) {
  (void)argc;
  (void)argv;

#ifndef COREMARK_EMBEDDED_RTT
  __am_timer_init();
#endif

  if (sizeof(ee_ptr_int) != sizeof(ee_u8 *)) {
    ee_printf("ERROR! Please define ee_ptr_int to a type that holds a pointer!\n");
  }
  if (sizeof(ee_u32) != 4) {
    ee_printf("ERROR! Please define ee_u32 to a 32b unsigned type!\n");
  }
  p->portable_id = 1;
}

void portable_fini(core_portable *p) {
  p->portable_id = 0;
}
