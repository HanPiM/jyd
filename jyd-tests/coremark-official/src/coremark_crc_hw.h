#ifndef COREMARK_CRC_HW_H
#define COREMARK_CRC_HW_H

#include "core_portme.h"

#if !defined(COREMARK_CRC_ACCEL_U8) && !defined(COREMARK_CRC_ACCEL_NATIVE)
#error "Select a CoreMark CRC acceleration mode"
#endif

extern inline __attribute__((always_inline, gnu_inline)) ee_u16
__hw_crcu8(ee_u8 data, ee_u16 crc)
{
    ee_u32 result;
    asm volatile(".insn r 0x0b, 0, 0, %0, %1, %2"
                 : "=r"(result)
                 : "r"((ee_u32)data), "r"((ee_u32)crc));
    return (ee_u16)result;
}

extern inline __attribute__((always_inline, gnu_inline)) ee_u16
__hw_crcu16(ee_u16 data, ee_u16 crc)
{
#if defined(COREMARK_CRC_ACCEL_NATIVE)
    ee_u32 result;
    asm volatile(".insn r 0x0b, 1, 0, %0, %1, %2"
                 : "=r"(result)
                 : "r"((ee_u32)data), "r"((ee_u32)crc));
    return (ee_u16)result;
#else
    crc = __hw_crcu8((ee_u8)data, crc);
    return __hw_crcu8((ee_u8)(data >> 8), crc);
#endif
}

extern inline __attribute__((always_inline, gnu_inline)) ee_u16
__hw_crcu32(ee_u32 data, ee_u16 crc)
{
#if defined(COREMARK_CRC_ACCEL_NATIVE)
    ee_u32 result;
    asm volatile(".insn r 0x0b, 2, 0, %0, %1, %2"
                 : "=r"(result)
                 : "r"(data), "r"((ee_u32)crc));
    return (ee_u16)result;
#else
    crc = __hw_crcu16((ee_u16)data, crc);
    return __hw_crcu16((ee_u16)(data >> 16), crc);
#endif
}

extern inline __attribute__((always_inline, gnu_inline)) ee_u16
__hw_crc16(ee_s16 data, ee_u16 crc)
{
    return __hw_crcu16((ee_u16)data, crc);
}

#define crcu8 __hw_crcu8
#define crcu16 __hw_crcu16
#define crc16 __hw_crc16
#define crcu32 __hw_crcu32

#endif
