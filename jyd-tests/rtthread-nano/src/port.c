#include <am.h>
#include <klib.h>
#include <rtthread.h>
#include <stdint.h>

#define JYD_COUNTER_HZ 50000000ULL
#define JYD_CYCLES_PER_RT_TICK (JYD_COUNTER_HZ / RT_TICK_PER_SECOND)

extern uint32_t CNT_REG[];

static rt_ubase_t switch_from;
static rt_ubase_t switch_to;
static uint32_t tick_last_counter;
static uint64_t tick_pending_cycles;

typedef struct {
  void *entry;
  void *parameter;
  void *exit;
} ThreadEntry;

static void thread_entry(void *opaque) {
  ThreadEntry *args = (ThreadEntry *)opaque;
  void (*entry)(void *) = (void (*)(void *))args->entry;
  void (*exit)(void) = (void (*)(void))args->exit;

  entry(args->parameter);
  exit();
}

static Context *event_handler(Event event, Context *context) {
  if (event.event == EVENT_YIELD) {
    if (switch_from != 0) {
      *(Context **)switch_from = context;
    }
    return *(Context **)switch_to;
  }

  halt(1);
}

void rt_hw_port_init(void) {
  cte_init(event_handler);
  tick_last_counter = *(volatile uint32_t *)CNT_REG;
  tick_pending_cycles = 0;
}

void rt_hw_tick_poll(void) {
  uint32_t current = *(volatile uint32_t *)CNT_REG;

  tick_pending_cycles += (uint32_t)(current - tick_last_counter);
  tick_last_counter = current;
  while (tick_pending_cycles >= JYD_CYCLES_PER_RT_TICK) {
    tick_pending_cycles -= JYD_CYCLES_PER_RT_TICK;
    rt_tick_increase();
  }
}

rt_base_t rt_hw_interrupt_disable(void) {
  return 0;
}

void rt_hw_interrupt_enable(rt_base_t level) {
  (void)level;
}

void rt_hw_context_switch(rt_ubase_t from, rt_ubase_t to) {
  switch_from = from;
  switch_to = to;
  yield();
}

void rt_hw_context_switch_to(rt_ubase_t to) {
  rt_hw_context_switch(0, to);
}

void rt_hw_context_switch_interrupt(rt_ubase_t from, rt_ubase_t to) {
  rt_hw_context_switch(from, to);
}

rt_uint8_t *rt_hw_stack_init(void *entry, void *parameter, rt_uint8_t *stack_addr, void *exit) {
  uintptr_t stack = (uintptr_t)stack_addr;
  ThreadEntry *args;

  stack &= ~(sizeof(uintptr_t) - 1);
  stack -= sizeof(ThreadEntry);
  args = (ThreadEntry *)stack;
  args->entry = entry;
  args->parameter = parameter;
  args->exit = exit;

  return (rt_uint8_t *)kcontext((Area){0, (void *)stack}, thread_entry, args);
}

void rt_hw_console_output(const char *text) {
  while (*text != '\0') {
    putch(*text++);
  }
}

char rt_hw_console_getchar(void) {
  rt_hw_tick_poll();
  return try_getch();
}

void rt_hw_cpu_shutdown(void) {
  halt(0);
}
