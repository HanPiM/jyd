#include <stdint.h>

typedef uint8_t ee_u8;
typedef int16_t ee_s16;
typedef uint16_t ee_u16;
typedef int32_t ee_s32;
typedef uint32_t ee_u32;
typedef int16_t MATDAT;
typedef int32_t MATRES;

enum {
    __CM_STATE_START = 0, __CM_STATE_INVALID = 1, __CM_STATE_S1 = 2,
    __CM_STATE_S2 = 3, __CM_STATE_INT = 4, __CM_STATE_FLOAT = 5,
    __CM_STATE_EXPONENT = 6, __CM_STATE_SCIENTIFIC = 7
};

static inline __attribute__((always_inline)) void
__cm_xstatec_init(void)
{
    asm volatile(".insn r 0x5b, 0, 0, x0, x0, x0" ::: "memory");
}

static inline __attribute__((always_inline)) void
__cm_xstatec_inc(ee_u32 state)
{
    asm volatile(".insn r 0x5b, 1, 0, x0, %0, x0" :: "r"(state) : "memory");
}

static inline __attribute__((always_inline)) ee_u32
__cm_xstatec_read(ee_u32 state)
{
    ee_u32 value;
    asm volatile(".insn r 0x5b, 2, 0, %0, %1, x0"
                 : "=r"(value) : "r"(state) : "memory");
    return value;
}

static inline __attribute__((always_inline)) void
__cm_xstatec_commit(ee_u32 mask)
{
    asm volatile(".insn r 0x5b, 3, 0, x0, %0, x0"
                 :: "r"(mask) : "memory");
}

static inline __attribute__((always_inline)) ee_u32
__cm_xstate2_step(ee_u32 state, const ee_u8 *str)
{
    ee_u32 result;
    asm volatile(".insn r 0x5b, 4, 0, %0, %1, %2"
                 : "=r"(result) : "r"(state), "r"(str) : "memory");
    return result;
}

static inline __attribute__((always_inline)) ee_u32
__cm_xstate4_step(ee_u32 state, const ee_u8 *str)
{
    ee_u32 result;
    asm volatile(".insn r 0x5b, 5, 0, %0, %1, %2"
                 : "=r"(result) : "r"(state), "r"(str) : "memory");
    return result;
}

static inline __attribute__((always_inline)) int
__cm_isdigit(ee_u8 c)
{
    return (ee_u8)(c - (ee_u8)'0') <= 9;
}

static __attribute__((noinline)) ee_u32
__cm_xstatec_transition(ee_u8 **instr)
{
    ee_u8 *str = *instr;
    ee_u32 state = __CM_STATE_START;
    for (; *str && state != __CM_STATE_INVALID; str++) {
        ee_u8 c = *str;
        if (c == ',') { str++; break; }
        switch (state) {
        case __CM_STATE_START:
            if (__cm_isdigit(c)) state = __CM_STATE_INT;
            else if (c == '+' || c == '-') state = __CM_STATE_S1;
            else if (c == '.') state = __CM_STATE_FLOAT;
            else { state = __CM_STATE_INVALID; __cm_xstatec_inc(__CM_STATE_INVALID); }
            __cm_xstatec_inc(__CM_STATE_START);
            break;
        case __CM_STATE_S1:
            if (__cm_isdigit(c)) state = __CM_STATE_INT;
            else if (c == '.') state = __CM_STATE_FLOAT;
            else state = __CM_STATE_INVALID;
            __cm_xstatec_inc(__CM_STATE_S1);
            break;
        case __CM_STATE_INT:
            if (c == '.') { state = __CM_STATE_FLOAT; __cm_xstatec_inc(__CM_STATE_INT); }
            else if (!__cm_isdigit(c)) { state = __CM_STATE_INVALID; __cm_xstatec_inc(__CM_STATE_INT); }
            break;
        case __CM_STATE_FLOAT:
            if (c == 'E' || c == 'e') { state = __CM_STATE_S2; __cm_xstatec_inc(__CM_STATE_FLOAT); }
            else if (!__cm_isdigit(c)) { state = __CM_STATE_INVALID; __cm_xstatec_inc(__CM_STATE_FLOAT); }
            break;
        case __CM_STATE_S2:
            state = (c == '+' || c == '-') ? __CM_STATE_EXPONENT : __CM_STATE_INVALID;
            __cm_xstatec_inc(__CM_STATE_S2);
            break;
        case __CM_STATE_EXPONENT:
            state = __cm_isdigit(c) ? __CM_STATE_SCIENTIFIC : __CM_STATE_INVALID;
            __cm_xstatec_inc(__CM_STATE_EXPONENT);
            break;
        case __CM_STATE_SCIENTIFIC:
            if (!__cm_isdigit(c)) { state = __CM_STATE_INVALID; __cm_xstatec_inc(__CM_STATE_INVALID); }
            break;
        default: break;
        }
    }
    *instr = str;
    return state;
}

static inline __attribute__((always_inline)) ee_u32
__cm_xstate2_transition(ee_u8 **instr)
{
    ee_u8 *str = *instr;
    ee_u32 state = __CM_STATE_START;
    ee_u32 mask = 0;

    for (;;) {
        ee_u32 result = __cm_xstate2_step(state, str);
        state = result & 7u;
        str += (result >> 3) & 3u;
        mask |= (result >> 6) & 0xffu;
        if (result & (1u << 5)) break;
    }
    __cm_xstatec_commit(mask);
    *instr = str;
    return state;
}

static inline __attribute__((always_inline)) ee_u32
__cm_xstate4_transition(ee_u8 **instr)
{
    ee_u8 *str = *instr;
    ee_u32 state = __CM_STATE_START;
    ee_u32 mask = 0;

    for (;;) {
        ee_u32 result = __cm_xstate4_step(state, str);
        state = result & 7u;
        str += (result >> 3) & 7u;
        mask |= (result >> 7) & 0xffu;
        if (result & (1u << 6)) break;
    }
    __cm_xstatec_commit(mask);
    *instr = str;
    return state;
}

static inline __attribute__((always_inline)) ee_u16
__cm_crc32_u8(ee_u32 data, ee_u16 crc)
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
__cm_xstatec_bench(ee_u32 blksize, ee_u8 *memblock, ee_s16 seed1,
                   ee_s16 seed2, ee_s16 step, ee_u16 crc)
{
    ee_u32 final_counts[8] = {0};
    ee_u8 *p = memblock;
    __cm_xstatec_init();
    while (*p != 0) final_counts[__cm_xstatec_transition(&p)]++;
    p = memblock;
    while (p < memblock + blksize) { if (*p != ',') *p ^= (ee_u8)seed1; p += step; }
    p = memblock;
    while (*p != 0) final_counts[__cm_xstatec_transition(&p)]++;
    p = memblock;
    while (p < memblock + blksize) { if (*p != ',') *p ^= (ee_u8)seed2; p += step; }
    for (ee_u32 i = 0; i < 8; i++) {
        crc = __cm_crc32_u8(final_counts[i], crc);
        crc = __cm_crc32_u8(__cm_xstatec_read(i), crc);
    }
    return crc;
}

static __attribute__((noinline, used, optimize("O3"))) ee_u16
core_bench_state_xstate2(ee_u32 blksize, ee_u8 *memblock, ee_s16 seed1,
                         ee_s16 seed2, ee_s16 step, ee_u16 crc)
{
    ee_u32 final_counts[8] = {0};
    ee_u8 *p = memblock;
    __cm_xstatec_init();
    while (*p != 0) final_counts[__cm_xstate2_transition(&p)]++;
    p = memblock;
    while (p < memblock + blksize) { if (*p != ',') *p ^= (ee_u8)seed1; p += step; }
    p = memblock;
    while (*p != 0) final_counts[__cm_xstate2_transition(&p)]++;
    p = memblock;
    while (p < memblock + blksize) { if (*p != ',') *p ^= (ee_u8)seed2; p += step; }
    for (ee_u32 i = 0; i < 8; i++) {
        crc = __cm_crc32_u8(final_counts[i], crc);
        crc = __cm_crc32_u8(__cm_xstatec_read(i), crc);
    }
    return crc;
}

static __attribute__((noinline, used, optimize("O3"))) ee_u16
core_bench_state_xstate4(ee_u32 blksize, ee_u8 *memblock, ee_s16 seed1,
                         ee_s16 seed2, ee_s16 step, ee_u16 crc)
{
    ee_u32 final_counts[8] = {0};
    ee_u8 *p = memblock;
    __cm_xstatec_init();
    while (*p != 0) final_counts[__cm_xstate4_transition(&p)]++;
    p = memblock;
    while (p < memblock + blksize) { if (*p != ',') *p ^= (ee_u8)seed1; p += step; }
    p = memblock;
    while (*p != 0) final_counts[__cm_xstate4_transition(&p)]++;
    p = memblock;
    while (p < memblock + blksize) { if (*p != ',') *p ^= (ee_u8)seed2; p += step; }
    for (ee_u32 i = 0; i < 8; i++) {
        crc = __cm_crc32_u8(final_counts[i], crc);
        crc = __cm_crc32_u8(__cm_xstatec_read(i), crc);
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

static inline __attribute__((always_inline, used)) void *
__cm_xlrev(void *list)
{
    void *result;
    asm volatile(".insn r 0x0b, 7, 0, %0, %1, x0"
                 : "=r"(result)
                 : "r"(list)
                 : "memory");
    return result;
}

static inline __attribute__((always_inline, used)) void *
__cm_xlrev1(void *list)
{
    if (!list)
        return 0;
    asm volatile(".insn r 0x0b, 6, 0, %0, %0, zero"
                 : "+r"(list)
                 :
                 : "memory");
    while (list)
    {
        asm volatile(".insn r 0x0b, 6, 1, %0, %0, zero"
                     : "+r"(list)
                     :
                     : "memory");
    }
    asm volatile(".insn r 0x0b, 6, 1, %0, %0, zero"
                 : "+r"(list)
                 :
                 : "memory");
    return list;
}

static inline __attribute__((always_inline, used)) void *
__cm_xlrev2(void *list)
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

static inline __attribute__((always_inline, used)) int
__cm_xstate(ee_u8 **instr, ee_u32 *transition_count)
{
    ee_u32 result;
    asm volatile(".insn r 0x0b, 7, 1, %0, %1, %2"
                 : "=r"(result)
                 : "r"(instr), "r"(transition_count)
                 : "memory");
    return (int)result;
}

static inline __attribute__((always_inline, used)) ee_s16
__cm_xmsum(ee_u32 n, MATRES *c, MATDAT clipval)
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
__cm_xbmul_matrix(ee_u32 n, MATRES *c, MATDAT *a, MATDAT *b)
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
__cm_xmac_vect(ee_u32 n, MATRES *c, MATDAT *a, MATDAT *b)
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
__cm_xmac_matrix(ee_u32 n, MATRES *c, MATDAT *a, MATDAT *b)
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
__cm_xdot_vect(ee_u32 n, MATRES *c, MATDAT *a, MATDAT *b)
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
__cm_xdot_matrix(ee_u32 n, MATRES *c, MATDAT *a, MATDAT *b)
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
