#include <aht10.h>
#include <finsh.h>
#include <rtthread.h>
#include <stdint.h>

#define AHT10_WATCH_STACK_SIZE 2048
#define AHT10_WATCH_PRIORITY   19
#define AHT10_WATCH_TIMESLICE  10
#define AHT10_WATCH_PERIOD_MS  2000

extern uint32_t SEG_REG[];

static struct rt_thread aht10_watch_thread;
static struct rt_semaphore aht10_watch_sem;
static rt_uint8_t aht10_watch_stack[AHT10_WATCH_STACK_SIZE];
static rt_bool_t aht10_watch_initialized;
static volatile rt_bool_t aht10_watch_enabled;

static uint32_t to_bcd(uint32_t value) {
  uint32_t result = 0;

  for (uint32_t shift = 0; shift < 16; shift += 4) {
    result |= (value % 10) << shift;
    value /= 10;
  }
  return result;
}

static uint32_t signed_magnitude(int32_t value) {
  volatile int32_t observed = value;

  if (observed < 0) return (uint32_t)(-(observed + 1)) + 1;
  return (uint32_t)observed;
}

static void update_seg(int32_t temperature) {
  uint32_t magnitude = signed_magnitude(temperature);
  *(volatile uint32_t *)SEG_REG = to_bcd(magnitude % 10000);
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
  update_seg(temperature);
  return RT_EOK;
}

static int aht10(int argc, char **argv) {
  (void)argc;
  (void)argv;
  return print_sample();
}
MSH_CMD_EXPORT(aht10, show one AHT10 sample and update SEG.);

static void aht10_watch_entry(void *parameter) {
  (void)parameter;
  while (1) {
    uint32_t last_sequence;

    rt_sem_take(&aht10_watch_sem, RT_WAITING_FOREVER);
    last_sequence = aht10_sample_seq() - 1;
    while (aht10_watch_enabled) {
      uint32_t sequence = aht10_sample_seq();

      if (sequence != last_sequence) {
        last_sequence = sequence;
        print_sample();
      }
      rt_thread_mdelay(AHT10_WATCH_PERIOD_MS);
    }
  }
}

static int aht10_watch(int argc, char **argv) {
  rt_err_t result;

  (void)argc;
  (void)argv;
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

    aht10_watch_enabled = RT_TRUE;
    result = rt_thread_startup(&aht10_watch_thread);
    if (result != RT_EOK) {
      aht10_watch_enabled = RT_FALSE;
      rt_thread_detach(&aht10_watch_thread);
      rt_sem_detach(&aht10_watch_sem);
      rt_kprintf("AHT10 watch thread startup failed: %d\n", result);
      return result;
    }
    aht10_watch_initialized = RT_TRUE;
    rt_sem_release(&aht10_watch_sem);
  } else {
    if (!aht10_watch_enabled) {
      aht10_watch_enabled = RT_TRUE;
      rt_sem_release(&aht10_watch_sem);
    }
  }

  rt_kprintf("AHT10 watch started\n");
  return RT_EOK;
}
MSH_CMD_EXPORT(aht10_watch, print updated AHT10 samples every two seconds.);

static int aht10_stop(int argc, char **argv) {
  (void)argc;
  (void)argv;
  aht10_watch_enabled = RT_FALSE;
  rt_kprintf("AHT10 watch stopped\n");
  return RT_EOK;
}
MSH_CMD_EXPORT(aht10_stop, stop the AHT10 watch output.);
