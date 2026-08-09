#include <aht10.h>
#include <am.h>
#include <klib.h>
#include <klib-macros.h>
#include <my_putnum.h>
#include <stdint.h>

extern uint32_t SEG_REG[];

static uint32_t to_bcd(uint32_t value) {
  uint32_t result = 0;
  for (uint32_t shift = 0; shift < 24; shift += 4) {
    result |= (value % 10) << shift;
    value /= 10;
  }
  return result;
}

static uint32_t magnitude(int32_t value) {
  volatile int32_t observed = value;
  if (observed < 0) return (uint32_t)(-(observed + 1)) + 1;
  return (uint32_t)observed;
}

static void print_sample(uint32_t status, uint32_t sequence) {
  int32_t temperature = aht10_temperature_x10();
  uint32_t humidity = aht10_humidity_x10();
  uint32_t absolute = magnitude(temperature);

  putstr("AHT10 T=");
  if (temperature < 0) putch('-');
  putnum(absolute / 10);
  putch('.');
  putnum(absolute % 10);
  putstr("C H=");
  putnum(humidity / 10);
  putch('.');
  putnum(humidity % 10);
  putstr("%RH status=0x");
  putnum_base16(status);
  putstr(" seq=");
  putnum(sequence);
  putch('\n');
  *(volatile uint32_t *)SEG_REG = to_bcd(absolute);
}

int main(const char *args) {
  uint32_t last_sequence = UINT32_MAX;
  uint32_t last_status = UINT32_MAX;

  (void)args;
  putstr("JYD AX7035B AHT10 board smoke\n");
  while (1) {
    uint32_t status = aht10_status();
    uint32_t sequence = aht10_sample_seq();
    if ((status & AHT10_STATUS_VALID) != 0 && sequence != last_sequence) {
      last_sequence = sequence;
      last_status = status;
      print_sample(status, sequence);
    } else if (status != last_status) {
      last_status = status;
      putstr("AHT10 waiting status=0x");
      putnum_base16(status);
      putch('\n');
    }
  }
}
