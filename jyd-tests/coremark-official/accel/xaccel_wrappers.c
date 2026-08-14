#include <stdint.h>

typedef uint8_t ee_u8;
typedef int16_t ee_s16;
typedef uint16_t ee_u16;
typedef int32_t ee_s32;
typedef uint32_t ee_u32;
typedef int16_t MATDAT;
typedef int32_t MATRES;

enum {
    __XACCEL_NUM_STATE_START = 0, __XACCEL_NUM_STATE_INVALID = 1, __XACCEL_NUM_STATE_S1 = 2,
    __XACCEL_NUM_STATE_S2 = 3, __XACCEL_NUM_STATE_INT = 4, __XACCEL_NUM_STATE_FLOAT = 5,
    __XACCEL_NUM_STATE_EXPONENT = 6, __XACCEL_NUM_STATE_SCIENTIFIC = 7
};

static inline __attribute__((always_inline)) void
__xaccel_xdfacnt_init(void)
{
    asm volatile(".insn r 0x5b, 0, 0, x0, x0, x0" ::: "memory");
}

static inline __attribute__((always_inline)) void
__xaccel_xdfacnt_inc(ee_u32 state)
{
    asm volatile(".insn r 0x5b, 1, 0, x0, %0, x0" :: "r"(state) : "memory");
}

static inline __attribute__((always_inline)) ee_u32
__xaccel_xdfacnt_read(ee_u32 state)
{
    ee_u32 value;
    asm volatile(".insn r 0x5b, 2, 0, %0, %1, x0"
                 : "=r"(value) : "r"(state) : "memory");
    return value;
}

static inline __attribute__((always_inline)) void
__xaccel_xdfacnt_commit(ee_u32 mask)
{
    asm volatile(".insn r 0x5b, 3, 0, x0, %0, x0"
                 :: "r"(mask) : "memory");
}

static inline __attribute__((always_inline)) ee_u32
__xaccel_xdfa2_step(ee_u32 state, const ee_u8 *str)
{
    ee_u32 result;
    asm volatile(".insn r 0x5b, 4, 0, %0, %1, %2"
                 : "=r"(result) : "r"(state), "r"(str) : "memory");
    return result;
}

static inline __attribute__((always_inline)) ee_u32
__xaccel_xdfa4_step(ee_u32 state, const ee_u8 *str)
{
    ee_u32 result;
    asm volatile(".insn r 0x5b, 5, 0, %0, %1, %2"
                 : "=r"(result) : "r"(state), "r"(str) : "memory");
    return result;
}

static inline __attribute__((always_inline)) ee_u32
__xaccel_xdfa4h_step(ee_u32 state, const ee_u8 *str)
{
    ee_u32 result;
    asm volatile(".insn r 0x5b, 5, 1, %0, %1, %2"
                 : "=r"(result) : "r"(state), "r"(str) : "memory");
    return result;
}

static inline __attribute__((always_inline)) ee_u32
__xaccel_xdfa4h_final_read(ee_u32 state)
{
    ee_u32 value;
    asm volatile(".insn r 0x5b, 2, 1, %0, %1, x0"
                 : "=r"(value) : "r"(state) : "memory");
    return value;
}

static inline __attribute__((always_inline)) int
__xaccel_isdigit(ee_u8 c)
{
    return (ee_u8)(c - (ee_u8)'0') <= 9;
}

static __attribute__((noinline)) ee_u32
__xaccel_xdfacnt_transition(ee_u8 **instr)
{
    ee_u8 *str = *instr;
    ee_u32 state = __XACCEL_NUM_STATE_START;
    for (; *str && state != __XACCEL_NUM_STATE_INVALID; str++) {
        ee_u8 c = *str;
        if (c == ',') { str++; break; }
        switch (state) {
        case __XACCEL_NUM_STATE_START:
            if (__xaccel_isdigit(c)) state = __XACCEL_NUM_STATE_INT;
            else if (c == '+' || c == '-') state = __XACCEL_NUM_STATE_S1;
            else if (c == '.') state = __XACCEL_NUM_STATE_FLOAT;
            else { state = __XACCEL_NUM_STATE_INVALID; __xaccel_xdfacnt_inc(__XACCEL_NUM_STATE_INVALID); }
            __xaccel_xdfacnt_inc(__XACCEL_NUM_STATE_START);
            break;
        case __XACCEL_NUM_STATE_S1:
            if (__xaccel_isdigit(c)) state = __XACCEL_NUM_STATE_INT;
            else if (c == '.') state = __XACCEL_NUM_STATE_FLOAT;
            else state = __XACCEL_NUM_STATE_INVALID;
            __xaccel_xdfacnt_inc(__XACCEL_NUM_STATE_S1);
            break;
        case __XACCEL_NUM_STATE_INT:
            if (c == '.') { state = __XACCEL_NUM_STATE_FLOAT; __xaccel_xdfacnt_inc(__XACCEL_NUM_STATE_INT); }
            else if (!__xaccel_isdigit(c)) { state = __XACCEL_NUM_STATE_INVALID; __xaccel_xdfacnt_inc(__XACCEL_NUM_STATE_INT); }
            break;
        case __XACCEL_NUM_STATE_FLOAT:
            if (c == 'E' || c == 'e') { state = __XACCEL_NUM_STATE_S2; __xaccel_xdfacnt_inc(__XACCEL_NUM_STATE_FLOAT); }
            else if (!__xaccel_isdigit(c)) { state = __XACCEL_NUM_STATE_INVALID; __xaccel_xdfacnt_inc(__XACCEL_NUM_STATE_FLOAT); }
            break;
        case __XACCEL_NUM_STATE_S2:
            state = (c == '+' || c == '-') ? __XACCEL_NUM_STATE_EXPONENT : __XACCEL_NUM_STATE_INVALID;
            __xaccel_xdfacnt_inc(__XACCEL_NUM_STATE_S2);
            break;
        case __XACCEL_NUM_STATE_EXPONENT:
            state = __xaccel_isdigit(c) ? __XACCEL_NUM_STATE_SCIENTIFIC : __XACCEL_NUM_STATE_INVALID;
            __xaccel_xdfacnt_inc(__XACCEL_NUM_STATE_EXPONENT);
            break;
        case __XACCEL_NUM_STATE_SCIENTIFIC:
            if (!__xaccel_isdigit(c)) { state = __XACCEL_NUM_STATE_INVALID; __xaccel_xdfacnt_inc(__XACCEL_NUM_STATE_INVALID); }
            break;
        default: break;
        }
    }
    *instr = str;
    return state;
}

static inline __attribute__((always_inline)) ee_u32
__xaccel_xdfa2_transition(ee_u8 **instr)
{
    ee_u8 *str = *instr;
    ee_u32 state = __XACCEL_NUM_STATE_START;
    ee_u32 mask = 0;

    for (;;) {
        ee_u32 result = __xaccel_xdfa2_step(state, str);
        state = result & 7u;
        str += (result >> 3) & 3u;
        mask |= (result >> 6) & 0xffu;
        if (result & (1u << 5)) break;
    }
    __xaccel_xdfacnt_commit(mask);
    *instr = str;
    return state;
}

static inline __attribute__((always_inline)) ee_u32
__xaccel_xdfa4_transition(ee_u8 **instr)
{
    ee_u8 *str = *instr;
    ee_u32 state = __XACCEL_NUM_STATE_START;
    ee_u32 mask = 0;

    for (;;) {
        ee_u32 result = __xaccel_xdfa4_step(state, str);
        state = result & 7u;
        str += (result >> 3) & 7u;
        mask |= (result >> 7) & 0xffu;
        if (result & (1u << 6)) break;
    }
    __xaccel_xdfacnt_commit(mask);
    *instr = str;
    return state;
}

static inline __attribute__((always_inline)) void
__xaccel_xdfa4p_transition(ee_u8 **instr)
{
    ee_u8 *p = *instr;
    /* Keep the step and equality test together so GCC does not add pointer moves. */
    asm volatile("1:\n\t"
                 "mv t0, %0\n\t"
                 ".insn r 0x5b, 5, 2, %0, x0, %0\n\t"
                 "bne t0, %0, 1b"
                 : "+r"(p)
                 :
                 : "t0", "memory");
    *instr = p;
}

static inline __attribute__((always_inline)) void
__xaccel_xdfa4h_transition(ee_u8 **instr)
{
    ee_u32 state = 0;
    ee_u8 *str = *instr;
    for (;;)
    {
        ee_u32 result = __xaccel_xdfa4h_step(state, str);
        state = result & 7u;
        str += (result >> 3) & 7u;
        if (result & (1u << 6))
            break;
    }
    *instr = str;
}

static inline __attribute__((always_inline)) ee_u16
__xaccel_crc32_u8(ee_u32 data, ee_u16 crc)
{
    for (unsigned i = 0; i < 4; i++) {
        ee_u32 next;
        asm volatile(".insn r 0x0b, 0, 0, %0, %1, %2"
                     : "=r"(next) : "r"(data), "r"((ee_u32)crc));
        crc = (ee_u16)next;
        data >>= 8;
    }
    return crc;
}

static __attribute__((noinline, used, optimize("O3"))) ee_u16
__xaccel_xdfacnt_bench(ee_u32 blksize, ee_u8 *memblock, ee_s16 seed1,
                   ee_s16 seed2, ee_s16 step, ee_u16 crc)
{
    ee_u32 final_counts[8] = {0};
    ee_u8 *p = memblock;
    __xaccel_xdfacnt_init();
    while (*p != 0) final_counts[__xaccel_xdfacnt_transition(&p)]++;
    p = memblock;
    while (p < memblock + blksize) { if (*p != ',') *p ^= (ee_u8)seed1; p += step; }
    p = memblock;
    while (*p != 0) final_counts[__xaccel_xdfacnt_transition(&p)]++;
    p = memblock;
    while (p < memblock + blksize) { if (*p != ',') *p ^= (ee_u8)seed2; p += step; }
    for (ee_u32 i = 0; i < 8; i++) {
        crc = __xaccel_crc32_u8(final_counts[i], crc);
        crc = __xaccel_crc32_u8(__xaccel_xdfacnt_read(i), crc);
    }
    return crc;
}

static __attribute__((noinline, used, optimize("O3"))) ee_u16
numeric_token_scan_xdfa2(ee_u32 blksize, ee_u8 *memblock, ee_s16 seed1,
                         ee_s16 seed2, ee_s16 step, ee_u16 crc)
{
    ee_u32 final_counts[8] = {0};
    ee_u8 *p = memblock;
    __xaccel_xdfacnt_init();
    while (*p != 0) final_counts[__xaccel_xdfa2_transition(&p)]++;
    p = memblock;
    while (p < memblock + blksize) { if (*p != ',') *p ^= (ee_u8)seed1; p += step; }
    p = memblock;
    while (*p != 0) final_counts[__xaccel_xdfa2_transition(&p)]++;
    p = memblock;
    while (p < memblock + blksize) { if (*p != ',') *p ^= (ee_u8)seed2; p += step; }
    for (ee_u32 i = 0; i < 8; i++) {
        crc = __xaccel_crc32_u8(final_counts[i], crc);
        crc = __xaccel_crc32_u8(__xaccel_xdfacnt_read(i), crc);
    }
    return crc;
}

static __attribute__((noinline, used, optimize("O3"))) ee_u16
numeric_token_scan_xdfa4(ee_u32 blksize, ee_u8 *memblock, ee_s16 seed1,
                         ee_s16 seed2, ee_s16 step, ee_u16 crc)
{
    ee_u32 final_counts[8] = {0};
    ee_u8 *p = memblock;
    __xaccel_xdfacnt_init();
    while (*p != 0) final_counts[__xaccel_xdfa4_transition(&p)]++;
    p = memblock;
    while (p < memblock + blksize) { if (*p != ',') *p ^= (ee_u8)seed1; p += step; }
    p = memblock;
    while (*p != 0) final_counts[__xaccel_xdfa4_transition(&p)]++;
    p = memblock;
    while (p < memblock + blksize) { if (*p != ',') *p ^= (ee_u8)seed2; p += step; }
    for (ee_u32 i = 0; i < 8; i++) {
        crc = __xaccel_crc32_u8(final_counts[i], crc);
        crc = __xaccel_crc32_u8(__xaccel_xdfacnt_read(i), crc);
    }
    return crc;
}

static __attribute__((noinline, used, optimize("O3"))) ee_u16
numeric_token_scan_xdfa4h(ee_u32 blksize, ee_u8 *memblock, ee_s16 seed1,
                          ee_s16 seed2, ee_s16 step, ee_u16 crc)
{
    ee_u8 *p = memblock;
    __xaccel_xdfacnt_init();
    while (*p != 0) __xaccel_xdfa4h_transition(&p);
    p = memblock;
    while (p < memblock + blksize) { if (*p != ',') *p ^= (ee_u8)seed1; p += step; }
    p = memblock;
    while (*p != 0) __xaccel_xdfa4h_transition(&p);
    p = memblock;
    while (p < memblock + blksize) { if (*p != ',') *p ^= (ee_u8)seed2; p += step; }
    for (ee_u32 i = 0; i < 8; i++) {
        crc = __xaccel_crc32_u8(__xaccel_xdfa4h_final_read(i), crc);
        crc = __xaccel_crc32_u8(__xaccel_xdfacnt_read(i), crc);
    }
    return crc;
}

static __attribute__((noinline, used, optimize("O3"))) ee_u16
numeric_token_scan_xdfa4p(ee_u32 blksize, ee_u8 *memblock, ee_s16 seed1,
                          ee_s16 seed2, ee_s16 step, ee_u16 crc)
{
    ee_u8 *p = memblock;
    __xaccel_xdfacnt_init();
    while (*p != 0) __xaccel_xdfa4p_transition(&p);
    p = memblock;
    while (p < memblock + blksize) { if (*p != ',') *p ^= (ee_u8)seed1; p += step; }
    p = memblock;
    while (*p != 0) __xaccel_xdfa4p_transition(&p);
    p = memblock;
    while (p < memblock + blksize) { if (*p != ',') *p ^= (ee_u8)seed2; p += step; }
    for (ee_u32 i = 0; i < 8; i++) {
        crc = __xaccel_crc32_u8(__xaccel_xdfa4h_final_read(i), crc);
        crc = __xaccel_crc32_u8(__xaccel_xdfacnt_read(i), crc);
    }
    return crc;
}

static inline ee_s32
xmac16(ee_s32 acc, ee_s16 a, ee_s16 b)
{
    asm volatile(".insn r 0x0b, 3, 0, %0, %1, %2"
                 : "+r"(acc)
                 : "r"((ee_s32)a), "r"((ee_s32)b));
    return acc;
}

static inline void
xmacacc_first(ee_s16 a, ee_s16 b)
{
    asm volatile(".insn r 0x0b, 3, 4, x0, %0, %1"
                 :: "r"((ee_s32)a), "r"((ee_s32)b));
}

static inline void
xmacacc_add(ee_s16 a, ee_s16 b)
{
    asm volatile(".insn r 0x0b, 3, 5, x0, %0, %1"
                 :: "r"((ee_s32)a), "r"((ee_s32)b));
}

static inline ee_s32
xmacacc_last(ee_s16 a, ee_s16 b)
{
    ee_s32 result;
    asm volatile(".insn r 0x0b, 3, 8, %0, %1, %2"
                 : "=r"(result)
                 : "r"((ee_s32)a), "r"((ee_s32)b));
    return result;
}

static inline void
xmacacc_bit_first(ee_s16 a, ee_s16 b)
{
    asm volatile(".insn r 0x0b, 3, 6, x0, %0, %1"
                 :: "r"((ee_s32)a), "r"((ee_s32)b));
}

static inline void
xmacacc_bit_add(ee_s16 a, ee_s16 b)
{
    asm volatile(".insn r 0x0b, 3, 7, x0, %0, %1"
                 :: "r"((ee_s32)a), "r"((ee_s32)b));
}

static inline ee_s32
xmacacc_bit_last(ee_s16 a, ee_s16 b)
{
    ee_s32 result;
    asm volatile(".insn r 0x0b, 3, 9, %0, %1, %2"
                 : "=r"(result)
                 : "r"((ee_s32)a), "r"((ee_s32)b));
    return result;
}

static inline ee_s32
xdot16(ee_u32 a, ee_u32 b)
{
    ee_s32 result;
    asm volatile(".insn r 0x0b, 4, 0, %0, %1, %2"
                 : "=r"(result)
                 : "r"(a), "r"(b));
    return result;
}

static inline ee_s32
xbmul(ee_s32 value)
{
    ee_s32 result;
    asm volatile(".insn r 0x0b, 5, 0, %0, %1, x0"
                 : "=r"(result)
                 : "r"(value));
    return result;
}

static inline ee_s32
xmbm(ee_s32 a, ee_s32 b)
{
    ee_s32 result;
    asm volatile(".insn r 0x0b, 5, 1, %0, %1, %2"
                 : "=r"(result)
                 : "r"(a), "r"(b));
    return result;
}

static inline __attribute__((always_inline, used)) void *
__xaccel_xlistfind(void *list, void *info)
{
    const ee_s16 *fields = (const ee_s16 *)info;
    void *result;
    if (fields[1] >= 0)
        asm volatile(".insn r 0x0b, 6, 1, %0, %1, %2"
                     : "=r"(result)
                     : "r"(list), "r"((ee_s32)fields[1])
                     : "memory");
    else
        asm volatile(".insn r 0x0b, 6, 3, %0, %1, %2"
                     : "=r"(result)
                     : "r"(list), "r"((ee_s32)fields[0])
                     : "memory");
    return result;
}

static inline __attribute__((always_inline, used)) void *
__xaccel_xlistrev(void *list)
{
    if (!list)
        return 0;
    asm volatile(".insn r 0x0b, 6, 0, %0, %0, zero\n\t"
                 ".insn r 0x0b, 6, 2, %0, %0, zero"
                 : "+r"(list)
                 :
                 : "memory");
    return list;
}

static inline __attribute__((always_inline, used)) ee_s16
__xaccel_xmsum(ee_u32 n, MATRES *c, MATDAT clipval)
{
    ee_u32 config = (n << 16) | (ee_u16)clipval;
    ee_s32 result;
    asm volatile(".insn r 0x0b, 7, 2, %0, %1, %2"
                 : "=r"(result)
                 : "r"(c), "r"(config)
                 : "memory");
    return (ee_s16)result;
}

static inline __attribute__((always_inline, used)) void
__xaccel_xbmul_matrix(ee_u32 n, MATRES *c, MATDAT *a, MATDAT *b)
{
    for (ee_u32 i = 0; i < n; i++)
        for (ee_u32 j = 0; j < n; j++)
        {
            MATRES acc = 0;
            for (ee_u32 k = 0; k < n; k++)
            {
                MATRES product = (MATRES)a[i * n + k] * (MATRES)b[k * n + j];
                acc += xbmul(product);
            }
            c[i * n + j] = acc;
        }
}

static inline __attribute__((always_inline, used)) void
__xaccel_xmbm_matrix(ee_u32 n, MATRES *c, MATDAT *a, MATDAT *b)
{
    for (ee_u32 i = 0; i < n; i++)
        for (ee_u32 j = 0; j < n; j++)
        {
            MATRES acc = 0;
            for (ee_u32 k = 0; k < n; k++)
                acc += xmbm(a[i * n + k], b[k * n + j]);
            c[i * n + j] = acc;
        }
}

static inline __attribute__((always_inline, used)) void
__xaccel_xmacacc_matrix(ee_u32 n, MATRES *c, MATDAT *a, MATDAT *b)
{
    for (ee_u32 i = 0; i < n; i++)
        for (ee_u32 j = 0; j < n; j++)
        {
            xmacacc_first(a[i * n], b[j]);
            for (ee_u32 k = 1; k + 1 < n; k++)
                xmacacc_add(a[i * n + k], b[k * n + j]);
            c[i * n + j] = xmacacc_last(a[i * n + n - 1], b[(n - 1) * n + j]);
        }
}

static inline __attribute__((always_inline, used)) void
__xaccel_xmacacc_bit_matrix(ee_u32 n, MATRES *c, MATDAT *a, MATDAT *b)
{
    for (ee_u32 i = 0; i < n; i++)
        for (ee_u32 j = 0; j < n; j++)
        {
            xmacacc_bit_first(a[i * n], b[j]);
            for (ee_u32 k = 1; k + 1 < n; k++)
                xmacacc_bit_add(a[i * n + k], b[k * n + j]);
            c[i * n + j] = xmacacc_bit_last(a[i * n + n - 1], b[(n - 1) * n + j]);
        }
}

static inline __attribute__((always_inline, used)) void
__xaccel_xmac_vect(ee_u32 n, MATRES *c, MATDAT *a, MATDAT *b)
{
    for (ee_u32 i = 0; i < n; i++)
    {
        MATRES acc = 0;
        for (ee_u32 j = 0; j < n; j++)
            acc = xmac16(acc, a[i * n + j], b[j]);
        c[i] = acc;
    }
}

static inline __attribute__((always_inline, used)) void
__xaccel_xmac_matrix(ee_u32 n, MATRES *c, MATDAT *a, MATDAT *b)
{
    for (ee_u32 i = 0; i < n; i++)
        for (ee_u32 j = 0; j < n; j++)
        {
            MATRES acc = 0;
            for (ee_u32 k = 0; k < n; k++)
                acc = xmac16(acc, a[i * n + k], b[k * n + j]);
            c[i * n + j] = acc;
        }
}

static inline __attribute__((always_inline, used)) void
__xaccel_xdot_vect(ee_u32 n, MATRES *c, MATDAT *a, MATDAT *b)
{
    for (ee_u32 i = 0; i < n; i++)
    {
        MATRES acc = 0;
        ee_u32 j = 0;
        for (; j + 1 < n; j += 2)
        {
            ee_u32 av = (ee_u16)a[i * n + j] | ((ee_u32)(ee_u16)a[i * n + j + 1] << 16);
            ee_u32 bv = (ee_u16)b[j] | ((ee_u32)(ee_u16)b[j + 1] << 16);
            acc += xdot16(av, bv);
        }
        if (j < n)
            acc += (MATRES)a[i * n + j] * (MATRES)b[j];
        c[i] = acc;
    }
}

static inline __attribute__((always_inline, used)) void
__xaccel_xdot_matrix(ee_u32 n, MATRES *c, MATDAT *a, MATDAT *b)
{
    for (ee_u32 i = 0; i < n; i++)
        for (ee_u32 j = 0; j < n; j++)
        {
            MATRES acc = 0;
            ee_u32 k = 0;
            for (; k + 1 < n; k += 2)
            {
                ee_u32 av = (ee_u16)a[i * n + k] | ((ee_u32)(ee_u16)a[i * n + k + 1] << 16);
                ee_u32 bv = (ee_u16)b[k * n + j] | ((ee_u32)(ee_u16)b[(k + 1) * n + j] << 16);
                acc += xdot16(av, bv);
            }
            if (k < n)
                acc += (MATRES)a[i * n + k] * (MATRES)b[k * n + j];
            c[i * n + j] = acc;
        }
}
