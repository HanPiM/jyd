#include <am.h>
#include <stdint.h>

extern uint32_t CNT_REG[]; // ms timer

static uint32_t _am_start_time_ms;

static inline uint32_t get_ms_time() {
	return CNT_REG[0];
}

void __am_timer_init() { 
	_am_start_time_ms = get_ms_time();
}

void __am_timer_uptime(AM_TIMER_UPTIME_T *uptime) {
  uptime->us = (get_ms_time() - _am_start_time_ms) * 1000;
}

void __am_timer_rtc(AM_TIMER_RTC_T *rtc) {

  rtc->second = 0;
  rtc->minute = 0;
  rtc->hour = 0;
  rtc->day = 0;
  rtc->month = 0;
  rtc->year = 1900;
}
