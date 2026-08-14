#ifndef XCRC_HW_H
#define XCRC_HW_H

#include "core_portme.h"

static inline __attribute__((always_inline, unused)) ee_u16
__gcc_crcu8(ee_u8 data, ee_u16 crc)
{
    return __builtin_rev_crc16_data8(crc, data, 0x8005);
}

static inline __attribute__((always_inline, unused)) ee_u16
__gcc_crcu16(ee_u16 data, ee_u16 crc)
{
    crc = __gcc_crcu8((ee_u8)data, crc);
    return __gcc_crcu8((ee_u8)(data >> 8), crc);
}

static inline __attribute__((always_inline, unused)) ee_u16
__gcc_crcu32(ee_u32 data, ee_u16 crc)
{
    crc = __gcc_crcu16((ee_u16)data, crc);
    return __gcc_crcu16((ee_u16)(data >> 16), crc);
}

static inline __attribute__((always_inline, unused)) ee_u16
__gcc_crc16(ee_s16 data, ee_u16 crc)
{
    return __gcc_crcu16((ee_u16)data, crc);
}

#define crcu8 __gcc_crcu8
#define crcu16 __gcc_crcu16
#define crc16 __gcc_crc16
#define crcu32 __gcc_crcu32

#endif
