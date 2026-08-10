#ifndef XCRC_HW_H
#define XCRC_HW_H

#include "core_portme.h"

extern inline __attribute__((always_inline, gnu_inline)) ee_u16
__hw_crcu8(ee_u8 data, ee_u16 crc)
{
    ee_u32 result;
    asm volatile(".insn r 0x0b, 0, 0, %0, %1, %2"
                 : "=r"(result)
                 : "r"((ee_u32)data), "r"((ee_u32)crc));
    return (ee_u16)result;
}

static __attribute__((noinline, unused)) ee_u16
__hw_crcu16(ee_u16 data, ee_u16 crc)
{
    crc = __hw_crcu8((ee_u8)data, crc);
    return __hw_crcu8((ee_u8)(data >> 8), crc);
}

static __attribute__((noinline, unused)) ee_u16
__hw_crcu32(ee_u32 data, ee_u16 crc)
{
    crc = __hw_crcu16((ee_u16)data, crc);
    return __hw_crcu16((ee_u16)(data >> 16), crc);
}

static __attribute__((noinline, unused)) ee_u16
__hw_crc16(ee_s16 data, ee_u16 crc)
{
    return __hw_crcu16((ee_u16)data, crc);
}

#define crcu8 __hw_crcu8
#define crcu16 __hw_crcu16
#define crc16 __hw_crc16
#define crcu32 __hw_crcu32

#endif
