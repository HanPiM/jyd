#ifndef AM_AHT10_H
#define AM_AHT10_H

#include <stdint.h>

#define AHT10_STATUS_VALID 0x1u
#define AHT10_STATUS_BUSY  0x2u
#define AHT10_STATUS_ERROR 0x4u

uint32_t aht10_status(void);
int32_t aht10_temperature_x10(void);
uint32_t aht10_humidity_x10(void);
uint32_t aht10_sample_seq(void);

#endif
