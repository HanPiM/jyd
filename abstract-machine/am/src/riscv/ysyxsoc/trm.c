#include <am.h>
#include <klib-macros.h>
#include <klib.h>
#include <stdint.h>

#include <my_putnum.h>

#include "soc_devreg.h"

extern char _heap_start;
extern char _heap_end;

typedef int (*mainfunc_t)(const char *args);

int main(const char *args);

extern char _pmem_start;
#define PMEM_SIZE (8 * 1024)
#define PMEM_END ((uintptr_t) & _pmem_start + PMEM_SIZE)

Area heap = RANGE(&_heap_start, &_heap_end);
static const char mainargs[MAINARGS_MAX_LEN] =
    TOSTRING(MAINARGS_PLACEHOLDER); // defined in CFLAGS

void init_serial() {

  // set UART to 8 bits, no parity, one stop bit
  // 0x3 = 0b11 : Select each character 8 bits
  // 0x80 = 0b10000000 : Divisor Latch Access bit
  *UART_LCR = 0x83u;
  if (*UART_LCR != 0x83u) {
    halt(-114514);
  }

  // set baud rate to 115200
  *UART_DL_MSB = 0;
  *UART_DL_LSB = 1;
  // clear DLAB bit
  *UART_LCR = 0x3;
  // enable FIFO with 14-byte threshold
  *UART_FIFO_CTRL = (0x3 << 6);
  // disable all interrupts
  *UART_IER = 0x0;
}

void putch(char ch) {
  while (!(*UART_LSR & 0x20)) {}
  *(volatile uint8_t *)(UART_BASE + 0x00) = ch;
}

void halt(int code) {
  asm volatile("mv a0, %0; ebreak" : : "r"(code));
  while (1) {
  } // make sure no return
}

void print_csr() {
  uint32_t mvendor_id, marchid;
  asm volatile("csrr %0, mvendorid" : "=r"(mvendor_id));
  asm volatile("csrr %0, marchid" : "=r"(marchid));
  char *vendor = (char *)&mvendor_id;
  putstr("mvendor: 0x");
  putnum_base16(mvendor_id);
  putch(' ');
  putch('(');
  putch(vendor[3]);
  putch(vendor[2]);
  putch(vendor[1]);
  putch(vendor[0]);
  putch(')');
  putch('\n');
  putstr("marchid: 0x");
  // base 10 too slow
  putnum_base16(marchid);
  putch('\n');
}

extern char _data, _edata, _text, _etext;
extern char _bss, _ebss;

extern char __data_load_start__;
extern char __data_size__;

void _trm_init() {
  init_serial();

	// print_csr();

  memcpy((void *)&_data, (void *)&__data_load_start__,
         (uintptr_t)&__data_size__);

  // printf("%d\n",(uintptr_t)&__data_size__);

  memset((void *)&_bss, 0, (uintptr_t)&_ebss - (uintptr_t)&_bss);
  int ret = main(mainargs);
  halt(ret);
}
