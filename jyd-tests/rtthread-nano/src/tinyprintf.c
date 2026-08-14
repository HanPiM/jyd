/*
 * Copyright (c) 2006-2022, RT-Thread Development Team
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Local size-oriented RT-Thread formatter, extended with '-' left-justify and
 * decimal width for %s. Upstream msh's help format ("%-16s - %s") relies on it; the
 * original formatter emitted the flag/width text literally. Numeric widths
 * are parsed but not applied, keeping the formatter small.
 */

#include <rthw.h>
#include <rtthread.h>

#ifdef RT_USING_TINY_PRINTF

#include <stdarg.h>

static int _console_putc(char ch)
{
    char output[2];

    output[0] = ch;
    output[1] = '\0';
    rt_hw_console_output(output);
    return 1;
}

static int _console_put_unsigned(rt_uint32_t value, rt_uint32_t base, rt_bool_t upper)
{
    char buffer[16];
    const char *digits = upper ? "0123456789ABCDEF" : "0123456789abcdef";
    int index = 0;
    int length = 0;

    do
    {
        buffer[index++] = digits[value % base];
        value /= base;
    }
    while (value != 0);

    while (index != 0)
        length += _console_putc(buffer[--index]);
    return length;
}

static int _console_put_string(const char *str, int width, rt_bool_t left)
{
    int length = 0;
    int len;

    if (str == RT_NULL)
        str = "(NULL)";

    len = 0;
    while (str[len] != '\0')
        len++;

    if (!left)
    {
        while (len < width)
        {
            length += _console_putc(' ');
            len++;
        }
    }
    while (*str != '\0')
        length += _console_putc(*str++);
    if (left)
    {
        while (len < width)
        {
            length += _console_putc(' ');
            len++;
        }
    }

    return length;
}

void rt_kprintf(const char *fmt, ...)
{
    int length = 0;
    va_list args;

    va_start(args, fmt);
    while (*fmt != '\0')
    {
        if (*fmt != '%')
        {
            length += _console_putc(*fmt++);
            continue;
        }

        {
            const char *start = fmt;
            rt_bool_t left = RT_FALSE;
            int width = 0;

            fmt++;
            while (*fmt == '-')
            {
                left = RT_TRUE;
                fmt++;
            }
            while (*fmt >= '0' && *fmt <= '9')
            {
                width = width * 10 + (*fmt - '0');
                fmt++;
            }

            if (*fmt == 's')
            {
                length += _console_put_string(va_arg(args, const char *), width, left);
            }
            else if (*fmt == 'c')
            {
                length += _console_putc((char)va_arg(args, int));
            }
            else if (*fmt == 'd' || *fmt == 'i')
            {
                rt_int32_t value = va_arg(args, rt_int32_t);

                if (value < 0)
                {
                    length += _console_putc('-');
                    length += _console_put_unsigned(0U - (rt_uint32_t)value, 10, RT_FALSE);
                }
                else
                {
                    length += _console_put_unsigned((rt_uint32_t)value, 10, RT_FALSE);
                }
            }
            else if (*fmt == 'u')
            {
                length += _console_put_unsigned(va_arg(args, rt_uint32_t), 10, RT_FALSE);
            }
            else if (*fmt == 'x' || *fmt == 'X')
            {
                rt_bool_t upper = (*fmt == 'X') ? RT_TRUE : RT_FALSE;

                length += _console_put_unsigned(va_arg(args, rt_uint32_t), 16, upper);
            }
            else if (*fmt == '%')
            {
                length += _console_putc('%');
            }
            else
            {
                const char *p;

                /* Unsupported conversion: reproduce '%' plus any consumed
                 * flag/width text and the conversion character literally. */
                for (p = start; p < fmt; p++)
                    length += _console_putc(*p);
                if (*fmt != '\0')
                    length += _console_putc(*fmt);
            }

            if (*fmt != '\0')
                fmt++;
        }
    }
    va_end(args);

    (void)length;
}

#endif /* RT_USING_TINY_PRINTF */
