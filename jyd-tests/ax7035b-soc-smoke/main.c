#include <am.h>
#include <klib.h>
#include <klib-macros.h>
#include <my_putnum.h>
#include <stdint.h>

extern uint32_t SEG_REG[];
extern uint32_t LED_REG[];

int main(const char *args) {
  (void)args;
  putstr("JYD AX7035B SoC alive\n");
  // 0x37 is the JYD simulator's success marker; the AX7035B scanner displays
  // only the low six BCD digits and blanks leading zeroes, so it shows 1234.
  *(volatile uint32_t *)SEG_REG = 0x37001234;
  *(volatile uint32_t *)LED_REG = 0x01221c08;
  while (1) {
    asm volatile("" ::: "memory");
  }
}
