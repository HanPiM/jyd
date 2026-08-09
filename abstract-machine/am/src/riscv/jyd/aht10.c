#include <aht10.h>

extern uint32_t AHT10_STATUS_REG[];
extern uint32_t AHT10_TEMP_REG[];
extern uint32_t AHT10_HUMI_REG[];
extern uint32_t AHT10_SEQ_REG[];

uint32_t aht10_status(void) {
  return *(volatile uint32_t *)AHT10_STATUS_REG;
}

int32_t aht10_temperature_x10(void) {
  return *(volatile int32_t *)AHT10_TEMP_REG;
}

uint32_t aht10_humidity_x10(void) {
  return *(volatile uint32_t *)AHT10_HUMI_REG;
}

uint32_t aht10_sample_seq(void) {
  return *(volatile uint32_t *)AHT10_SEQ_REG;
}
