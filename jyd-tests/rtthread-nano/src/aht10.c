#include <aht10.h>
#include <finsh.h>
#include <rtthread.h>
#include <stdint.h>

#define AHT10_WATCH_STACK_SIZE 2048
#define AHT10_WATCH_PRIORITY   19
#define AHT10_WATCH_TIMESLICE  10
#define AHT10_WATCH_POLL_MS    100
#define AHT10_WATCH_DEFAULT_COUNT 10
#define AHT10_WATCH_MAX_COUNT 10000

static struct rt_thread aht10_watch_thread;
static struct rt_semaphore aht10_watch_sem;
static rt_uint8_t aht10_watch_stack[AHT10_WATCH_STACK_SIZE];
static rt_bool_t aht10_watch_initialized;
static volatile rt_bool_t aht10_watch_enabled;
static volatile uint32_t aht10_watch_remaining;

static uint32_t signed_magnitude(int32_t value) {
  volatile int32_t observed = value;

  if (observed < 0) return (uint32_t)(-(observed + 1)) + 1;
  return (uint32_t)observed;
}

static int print_sample(void) {
  uint32_t status = aht10_status();
  int32_t temperature;
  uint32_t humidity;
  uint32_t magnitude;

  if ((status & AHT10_STATUS_VALID) == 0) {
    rt_kprintf("AHT10 data not valid (status=0x%x)\n", status);
    return -RT_ERROR;
  }

  temperature = aht10_temperature_x10();
  humidity = aht10_humidity_x10();
  magnitude = signed_magnitude(temperature);

  rt_kprintf("AHT10 T=%s%d.%dC H=%d.%d%cRH seq=%u\n",
             temperature < 0 ? "-" : "",
             magnitude / 10,
             magnitude % 10,
             humidity / 10,
             humidity % 10,
             '%',
             aht10_sample_seq());
  return RT_EOK;
}

static int aht10(int argc, char **argv) {
  (void)argc;
  (void)argv;
  return print_sample();
}
MSH_CMD_EXPORT(aht10, show one current AHT10 sample.);

static void aht10_watch_entry(void *parameter) {
  (void)parameter;
  while (1) {
    uint32_t last_sequence;

    rt_sem_take(&aht10_watch_sem, RT_WAITING_FOREVER);
    last_sequence = aht10_sample_seq();
    while (aht10_watch_enabled) {
      uint32_t sequence = aht10_sample_seq();

      if (sequence != last_sequence) {
        last_sequence = sequence;
        print_sample();
        if (--aht10_watch_remaining == 0) {
          aht10_watch_enabled = RT_FALSE;
          rt_kprintf("AHT10 watch finished\n");
          break;
        }
      }
      rt_thread_mdelay(AHT10_WATCH_POLL_MS);
    }
  }
}

static int parse_watch_count(const char *text, uint32_t *count) {
  uint32_t value = 0;

  if (text == RT_NULL || *text == '\0') return -RT_EINVAL;
  while (*text != '\0') {
    if (*text < '0' || *text > '9') return -RT_EINVAL;
    value = value * 10 + (uint32_t)(*text - '0');
    if (value > AHT10_WATCH_MAX_COUNT) return -RT_EINVAL;
    text++;
  }
  if (value == 0) return -RT_EINVAL;
  *count = value;
  return RT_EOK;
}

static int aht10_watch(int argc, char **argv) {
  rt_err_t result;
  uint32_t count = AHT10_WATCH_DEFAULT_COUNT;

  if (argc > 2 || (argc == 2 && parse_watch_count(argv[1], &count) != RT_EOK)) {
    rt_kprintf("usage: aht10_watch [count], count=1..%d\n", AHT10_WATCH_MAX_COUNT);
    return -RT_EINVAL;
  }
  if (aht10_watch_enabled) {
    rt_kprintf("AHT10 watch already running\n");
    return -RT_ERROR;
  }

  if (!aht10_watch_initialized) {
    result = rt_sem_init(&aht10_watch_sem, "aht10s", 0, RT_IPC_FLAG_FIFO);
    if (result != RT_EOK) {
      rt_kprintf("AHT10 watch semaphore init failed: %d\n", result);
      return result;
    }

    result = rt_thread_init(&aht10_watch_thread,
                            "aht10",
                            aht10_watch_entry,
                            RT_NULL,
                            aht10_watch_stack,
                            sizeof(aht10_watch_stack),
                            AHT10_WATCH_PRIORITY,
                            AHT10_WATCH_TIMESLICE);
    if (result != RT_EOK) {
      rt_sem_detach(&aht10_watch_sem);
      rt_kprintf("AHT10 watch thread init failed: %d\n", result);
      return result;
    }

    result = rt_thread_startup(&aht10_watch_thread);
    if (result != RT_EOK) {
      rt_thread_detach(&aht10_watch_thread);
      rt_sem_detach(&aht10_watch_sem);
      rt_kprintf("AHT10 watch thread startup failed: %d\n", result);
      return result;
    }
    aht10_watch_initialized = RT_TRUE;
  }

  aht10_watch_remaining = count;
  aht10_watch_enabled = RT_TRUE;
  rt_sem_release(&aht10_watch_sem);
  rt_kprintf("AHT10 watch started: %u samples\n", count);
  return RT_EOK;
}
MSH_CMD_EXPORT(aht10_watch, print a bounded number of new AHT10 samples.);

static int aht10_stop(int argc, char **argv) {
  (void)argc;
  (void)argv;
  if (!aht10_watch_enabled) {
    rt_kprintf("AHT10 watch is not running\n");
    return RT_EOK;
  }
  aht10_watch_enabled = RT_FALSE;
  rt_kprintf("AHT10 watch stopped\n");
  return RT_EOK;
}
MSH_CMD_EXPORT(aht10_stop, stop the AHT10 watch output.);
