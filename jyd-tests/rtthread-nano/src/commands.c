#include <finsh.h>
#include <rtthread.h>

#if COREMARK_ENABLE
extern int coremark_main(void);

static int coremark(int argc, char **argv) {
  (void)argc;
  (void)argv;
  return coremark_main();
}
MSH_CMD_EXPORT(coremark, run the embedded CoreMark workload.);
#endif

static const char *thread_status(rt_uint8_t status) {
  switch (status & RT_THREAD_STAT_MASK) {
    case RT_THREAD_READY:   return "ready";
    case RT_THREAD_SUSPEND: return "suspend";
    case RT_THREAD_INIT:    return "init";
    case RT_THREAD_CLOSE:   return "close";
    case RT_THREAD_RUNNING: return "running";
    default:                return "unknown";
  }
}

static int show_threads(void) {
  struct rt_object_information *info;
  rt_list_t *node;

  info = rt_object_get_information(RT_Object_Class_Thread);
  if (info == RT_NULL) return -RT_ERROR;

  rt_kprintf("thread priority status stack size tick error\n");
  for (node = info->object_list.next;
       node != &info->object_list;
       node = node->next) {
    struct rt_thread *thread;

    thread = rt_list_entry(node, struct rt_thread, list);
    rt_kprintf("%s %u %s %u %u %d\n",
               thread->name,
               thread->current_priority,
               thread_status(thread->stat),
               thread->stack_size,
               thread->remaining_tick,
               thread->error);
  }

  return RT_EOK;
}

static int list_thread(int argc, char **argv) {
  (void)argc;
  (void)argv;
  return show_threads();
}
MSH_CMD_EXPORT(list_thread, list threads in the system.);

static int ps(int argc, char **argv) {
  (void)argc;
  (void)argv;
  return show_threads();
}
MSH_CMD_EXPORT(ps, list threads in the system.);

static int version(int argc, char **argv) {
  (void)argc;
  (void)argv;
  rt_show_version();
  return RT_EOK;
}
MSH_CMD_EXPORT(version, show RT-Thread version information.);

static int list_semaphore(int argc, char **argv) {
  struct rt_object_information *info;
  rt_list_t *node;

  (void)argc;
  (void)argv;
  info = rt_object_get_information(RT_Object_Class_Semaphore);
  if (info == RT_NULL) return -RT_ERROR;

  rt_kprintf("semaphore value suspended\n");
  for (node = info->object_list.next;
       node != &info->object_list;
       node = node->next) {
    struct rt_semaphore *sem;

    sem = rt_list_entry(node, struct rt_semaphore, parent.parent.list);
    rt_kprintf("%s %u %u\n",
               sem->parent.parent.name,
               sem->value,
               rt_list_len(&sem->parent.suspend_thread));
  }

  return RT_EOK;
}
MSH_CMD_EXPORT(list_semaphore, list semaphores in the system.);
