#include <am.h>
#include <klib.h>
#include <klib-macros.h>

int main(const char *args);

extern char _heap_start;
extern char _heap_end;

Area heap = RANGE(&_heap_start, &_heap_end);
static const char mainargs[MAINARGS_MAX_LEN] =
    TOSTRING(MAINARGS_PLACEHOLDER); // defined in CFLAGS

extern char _my_ext_serial_port;
#define SERIAL_PORT ((uintptr_t)(&_my_ext_serial_port))

extern uint32_t CNT_REG[];
extern uint32_t LED_REG[];
extern uint32_t SEG_REG[];
#define MMIO_CNT_REG ((volatile uint32_t *)(&CNT_REG))
#define MMIO_LED_REG ((volatile uint32_t *)(&LED_REG))
#define MMIO_SEG_REG ((volatile uint32_t *)(&SEG_REG))

#define CNT_MAGIC_CMD_START 0x80000000u
#define CNT_MAGIC_CMD_STOP 0xffffffffu

#define LED_MAGIC_SHAPE_CORRECT 0x1221c08u
#define LED_MAGIC_SHAPE_WRONG 0x24181824u

static void _start_cnt() {
	for (int i = 0; i < 10; i++) {
		*MMIO_CNT_REG = CNT_MAGIC_CMD_START;
	}
}
static void _stop_cnt() {
	for (int i = 0; i < 10; i++) {
		*MMIO_CNT_REG = CNT_MAGIC_CMD_STOP;
	}
}

static uint32_t _tobcd(uint32_t x) {
	uint32_t res = 0;
	for (uint32_t shift = 0; shift < 32; shift += 4) {
		res |= (x % 10) << shift;
		x /= 10;
	}
	return res;
}

static void _update_seg(uint32_t v){
	*MMIO_SEG_REG = _tobcd(v);
}

void putch(char ch) { *(uint8_t *)(SERIAL_PORT + 0x00) = ch; }
char try_getch() { return *(volatile uint8_t *)(SERIAL_PORT + 0x00); }

void halt(int code) {
	uint32_t cnt = *MMIO_CNT_REG;
	_update_seg(cnt);
	*MMIO_LED_REG = (code == 0) ? LED_MAGIC_SHAPE_CORRECT : LED_MAGIC_SHAPE_WRONG;

  asm volatile("mv a0, %0; ebreak" : : "r"(code));
  while (1) {
  } // make sure no return
}

void _trm_init() {
	_start_cnt();
  int ret = main(mainargs);
	_stop_cnt();
  halt(ret);
}
