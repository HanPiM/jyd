#include <am.h>
#include <finsh.h>
#include <rtthread.h>

int main(void) {
  extern void rt_hw_port_init(void);
  extern void rt_hw_tick_poll(void);
  extern int finsh_system_init(void);

  ioe_init();
  rt_hw_port_init();
  rt_system_timer_init();
  rt_system_scheduler_init();
  if (finsh_system_init() != RT_EOK) {
    halt(1);
  }
  rt_thread_idle_init();
  if (rt_thread_idle_sethook(rt_hw_tick_poll) != RT_EOK) {
    halt(1);
  }
  rt_system_scheduler_start();
  halt(1);
}
