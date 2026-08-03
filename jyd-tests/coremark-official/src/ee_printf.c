/*
Copyright 2018 Embedded Microprocessor Benchmark Consortium (EEMBC)

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

/*
 * CoreMark only uses %c, %s, %d, %u, %x, %lu and %f.  Keeping a formatter
 * specialized for that set avoids pulling the network-address, arbitrary
 * base, exponent and general-format machinery from the upstream formatter.
 * Floating-point conversion still goes through the original fcvt/SoftFloat
 * path, so result precision and rounding are unchanged.
 */

#include <coremark.h>
#include <stdarg.h>

#ifdef COREMARK_EMBEDDED_RTT
#include <rthw.h>
#endif

#define LEFT     (1 << 0)
#define ZEROPAD  (1 << 1)
#define SIGNED   (1 << 2)

char *fcvtbuf(double arg, int ndigits, int *decpt, int *sign, char *buf);

static int
is_digit(char c)
{
    return c >= '0' && c <= '9';
}

static ee_size_t
string_length(const char *s)
{
    const char *start = s;
    while (*s)
        s++;
    return s - start;
}

static char *
format_uint(char *out,
            unsigned long value,
            unsigned base,
            int width,
            int flags)
{
    static const char digits[] = "0123456789abcdef";
    char              tmp[16];
    int               len = 0;
    int               output_len;
    char              pad = (flags & ZEROPAD) ? '0' : ' ';

    do
    {
        tmp[len++] = digits[value % base];
        value /= base;
    } while (value);

    output_len = len;

    if (!(flags & LEFT))
        while (len < width--)
            *out++ = pad;
    while (len)
        *out++ = tmp[--len];
    if (flags & LEFT)
        while (output_len < width--)
            *out++ = ' ';
    return out;
}

static char *
format_int(char *out, long value, unsigned base, int width, int flags)
{
    unsigned long magnitude;

    if ((flags & SIGNED) && value < 0)
    {
        *out++ = '-';
        width--;
        magnitude = 0UL - (unsigned long)value;
    }
    else
    {
        magnitude = (unsigned long)value;
    }
    return format_uint(out, magnitude, base, width, flags);
}

#if HAS_FLOAT
static char *
format_float(char *out, double value)
{
    char digits_buf[80];
    int  decpt;
    int  sign;
    int  pos = 0;
    char *digits = fcvtbuf(value, 6, &decpt, &sign, digits_buf);

    if (sign)
        *out++ = '-';

    if (decpt <= 0)
    {
        *out++ = '0';
    }
    else
    {
        while (pos < decpt)
        {
            *out++ = *digits ? *digits++ : '0';
            pos++;
        }
    }

    *out++ = '.';
    while (decpt < 0)
    {
        *out++ = '0';
        decpt++;
        pos++;
    }
    while (pos < decpt + 6)
    {
        *out++ = *digits ? *digits++ : '0';
        pos++;
    }
    return out;
}
#endif

static int
ee_vsprintf(char *buf, const char *fmt, va_list args)
{
    char *out = buf;

    while (*fmt)
    {
        int flags = 0;
        int width = 0;
        int long_arg = 0;

        if (*fmt != '%')
        {
            *out++ = *fmt++;
            continue;
        }
        fmt++;

        if (*fmt == '-')
        {
            flags |= LEFT;
            fmt++;
        }
        if (*fmt == '0')
        {
            flags |= ZEROPAD;
            fmt++;
        }
        while (is_digit(*fmt))
            width = width * 10 + *fmt++ - '0';
        if (*fmt == 'l')
        {
            long_arg = 1;
            fmt++;
        }

        switch (*fmt++)
        {
            case 'c':
                *out++ = (char)va_arg(args, int);
                break;
            case 's':
            {
                const char *s = va_arg(args, const char *);
                int len;
                if (!s)
                    s = "<NULL>";
                len = string_length(s);
                if (!(flags & LEFT))
                    while (len < width--)
                        *out++ = ' ';
                while (*s)
                    *out++ = *s++;
                if (flags & LEFT)
                    while (len < width--)
                        *out++ = ' ';
                break;
            }
            case 'd':
            case 'i':
                flags |= SIGNED;
                out = format_int(out,
                                 long_arg ? va_arg(args, long)
                                          : va_arg(args, int),
                                 10,
                                 width,
                                 flags);
                break;
            case 'u':
                out = format_uint(out,
                                  long_arg ? va_arg(args, unsigned long)
                                           : va_arg(args, unsigned int),
                                  10,
                                  width,
                                  flags);
                break;
            case 'x':
                out = format_uint(out,
                                  long_arg ? va_arg(args, unsigned long)
                                           : va_arg(args, unsigned int),
                                  16,
                                  width,
                                  flags);
                break;
#if HAS_FLOAT
            case 'f':
                out = format_float(out, va_arg(args, double));
                break;
#endif
            case '%':
                *out++ = '%';
                break;
            default:
                *out++ = '%';
                *out++ = fmt[-1];
                break;
        }
    }

    *out = '\0';
    return out - buf;
}

static void
uart_send_char(char c)
{
#ifdef COREMARK_EMBEDDED_RTT
    char output[2] = {c, '\0'};
    rt_hw_console_output(output);
#else
    putch(c);
#endif
}

int
ee_printf(const char *fmt, ...)
{
    char    buf[1024];
    char *  p;
    va_list args;
    int     n = 0;

    va_start(args, fmt);
    ee_vsprintf(buf, fmt, args);
    va_end(args);

    for (p = buf; *p; p++)
    {
        uart_send_char(*p);
        n++;
    }
    return n;
}

#if COREMARK_PSEUDO_FLOAT
/* Print a positive rational without discarding raw timer ticks.  The scaled
 * numerator and the fractional remainder use 64-bit intermediates so a
 * 50 MHz tick rate can be applied directly.  The thirteenth digit is used to
 * round the requested twelve decimal places. */
void
ee_print_ratio(const char *prefix,
               ee_u32 numerator,
               ee_u32 denominator,
               ee_u32 numerator_scale,
               int newline)
{
    char   fraction[13];
    uint64_t scaled;
    uint64_t whole;
    uint64_t remainder;
    uint64_t denominator64;
    int    i;

    if (denominator == 0)
    {
        ee_printf("%s0.000000000000%s", prefix, newline ? "\n" : "");
        return;
    }

    denominator64 = denominator;
    scaled        = (uint64_t)numerator * numerator_scale;
    whole         = scaled / denominator64;
    remainder     = scaled % denominator64;
    for (i = 0; i < 13; i++)
    {
        remainder *= 10;
        fraction[i] = (char)('0' + remainder / denominator64);
        remainder %= denominator64;
    }

    if (fraction[12] >= '5')
    {
        for (i = 11; i >= 0 && fraction[i] == '9'; i--)
            fraction[i] = '0';
        if (i >= 0)
            fraction[i]++;
        else
            whole++;
    }
    fraction[12] = '\0';
    ee_printf("%s%u.%s%s", prefix, (ee_u32)whole, fraction, newline ? "\n" : "");
}
#endif
