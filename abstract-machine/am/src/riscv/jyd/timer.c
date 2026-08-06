#include <am.h>
#include <stdint.h>

extern uint32_t CNT_REG[]; // 50 MHz tick counter

static uint32_t _am_start_ticks;

static inline uint32_t get_ticks() {
	return CNT_REG[0];
}

void __am_timer_init() { 
	_am_start_ticks = get_ticks();
}

void __am_timer_uptime(AM_TIMER_UPTIME_T *uptime) {
  uptime->us = (get_ticks() - _am_start_ticks) / 50;
}
