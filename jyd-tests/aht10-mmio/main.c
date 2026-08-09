#include <aht10.h>
#include <am.h>
#include <klib.h>
#include <klib-macros.h>
#include <my_putnum.h>
#include <stdint.h>

extern uint32_t SEG_REG[];

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

int main(const char *args) {
  uint32_t status = aht10_status();
  int32_t temperature = aht10_temperature_x10();
  uint32_t humidity = aht10_humidity_x10();
  uint32_t sequence = aht10_sample_seq();
  uint32_t temperature_magnitude;

  (void)args;
  temperature_magnitude = signed_magnitude(temperature);

  putstr("AHT10 T=");
  if (temperature < 0) putch('-');
  putnum(temperature_magnitude / 10);
  putch('.');
  putnum(temperature_magnitude % 10);
  putstr("C H=");
  putnum(humidity / 10);
  putch('.');
  putnum(humidity % 10);
  putstr("%RH\nAHT10 status=0x");
  putnum_base16(status);
  putstr(" seq=");
  putnum(sequence);
  putch('\n');

  // Keep the display chain software-driven: sensor -> MMIO -> CPU -> SEG MMIO.
  *(volatile uint32_t *)SEG_REG = to_bcd(temperature_magnitude % 10000);

  if ((status & AHT10_STATUS_VALID) == 0 || temperature != 328 || humidity != 654 || sequence != 1) {
    putstr("AHT10 MMIO test FAIL\n");
    return 1;
  }

  putstr("AHT10 MMIO test PASS\n");
  return 0;
}
