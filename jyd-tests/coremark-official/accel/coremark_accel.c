#include <stdint.h>

typedef uint8_t ee_u8;
typedef int16_t ee_s16;
typedef uint16_t ee_u16;
typedef int32_t ee_s32;
typedef uint32_t ee_u32;
typedef int16_t MATDAT;
typedef int32_t MATRES;

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
