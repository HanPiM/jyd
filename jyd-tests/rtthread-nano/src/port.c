#include <am.h>
#include <klib.h>
#include <rtthread.h>

static rt_ubase_t switch_from;
static rt_ubase_t switch_to;

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
  return try_getch();
}

void rt_hw_cpu_shutdown(void) {
  halt(0);
}
