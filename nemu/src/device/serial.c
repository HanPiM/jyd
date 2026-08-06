/***************************************************************************************
 * Copyright (c) 2014-2024 Zihao Yu, Nanjing University
 *
 * NEMU is licensed under Mulan PSL v2.
 * You can use this software according to the terms and conditions of the Mulan
 *PSL v2. You may obtain a copy of Mulan PSL v2 at:
 *          http://license.coscl.org.cn/MulanPSL2
 *
 * THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY
 *KIND, EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
 *NON-INFRINGEMENT, MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
 *
 * See the Mulan PSL v2 for more details.
 ***************************************************************************************/

#include "memory/paddr.h"
#include <device/map.h>
#include <utils.h>
#include <fcntl.h>
#include <poll.h>
#include <signal.h>
#include <termios.h>
#include <unistd.h>

/* http://en.wikibooks.org/wiki/Serial_Programming/8250_UART_Programming */
// NOTE: this is compatible to 16550

#define CH_OFFSET 0

static uint8_t *serial_base = NULL;

// uint32_t g_serial_delay = 0;

#define SERIAL_RX_FIFO_SIZE 256
static uint8_t serial_rx_fifo[SERIAL_RX_FIFO_SIZE];
static int serial_rx_head = 0;
static int serial_rx_tail = 0;

static bool serial_rx_available(void) {
  return serial_rx_head != serial_rx_tail;
}

static void serial_rx_push(uint8_t ch) {
  int next = (serial_rx_head + 1) % SERIAL_RX_FIFO_SIZE;
  if (next == serial_rx_tail) {
    return; // FIFO full, drop the byte
  }
  serial_rx_fifo[serial_rx_head] = ch;
  serial_rx_head = next;
}

static uint8_t serial_rx_pop(void) {
  if (!serial_rx_available()) {
    return 0xff;
  }
  uint8_t ch = serial_rx_fifo[serial_rx_tail];
  serial_rx_tail = (serial_rx_tail + 1) % SERIAL_RX_FIFO_SIZE;
  return ch;
}

/* Defined in src/monitor/sdb/sdb.c. */
extern bool sdb_is_batch_mode(void);

static struct termios serial_saved_termios;
static bool serial_termios_active = false;

static void serial_restore_terminal(void) {
  if (serial_termios_active) {
    tcsetattr(STDIN_FILENO, TCSANOW, &serial_saved_termios);
    serial_termios_active = false;
  }
}

static void serial_terminal_signal_handler(int sig) {
  serial_restore_terminal();
  signal(sig, SIG_DFL);
  raise(sig);
}

static void serial_enable_unbuffered_input(void) {
  if (serial_termios_active || !isatty(STDIN_FILENO)) {
    return;
  }
  /* Keep interactive sdb mode on the same terminal; batch mode owns stdin. */
  if (!sdb_is_batch_mode()) {
    return;
  }
  if (tcgetattr(STDIN_FILENO, &serial_saved_termios) != 0) {
    return;
  }

  struct termios termios = serial_saved_termios;
  termios.c_lflag &= ~(ICANON | ECHO);
  termios.c_cc[VMIN] = 0;
  termios.c_cc[VTIME] = 0;
  if (tcsetattr(STDIN_FILENO, TCSANOW, &termios) != 0) {
    return;
  }

  serial_termios_active = true;
  atexit(serial_restore_terminal);
  signal(SIGINT, serial_terminal_signal_handler);
  signal(SIGTERM, serial_terminal_signal_handler);
  signal(SIGHUP, serial_terminal_signal_handler);
}

#ifdef CONFIG_SERIAL_INPUT_FIFO
static int serial_fifo_fd = -1;

static void serial_fill_input_fifo(void) {
  if (serial_fifo_fd < 0) {
    serial_fifo_fd = open("/tmp/nemu.serial", O_RDONLY | O_NONBLOCK);
  }
  if (serial_fifo_fd < 0) {
    return;
  }

  uint8_t buf[64];
  ssize_t n;
  while ((n = read(serial_fifo_fd, buf, sizeof(buf))) > 0) {
    for (ssize_t i = 0; i < n; i++) {
      serial_rx_push(buf[i]);
    }
  }
}
#endif

static void serial_fill_input(void) {
  serial_enable_unbuffered_input();

#ifdef CONFIG_SERIAL_INPUT_FIFO
  serial_fill_input_fifo();
#endif

  struct pollfd stdin_poll = {
      .fd = STDIN_FILENO,
      .events = POLLIN,
      .revents = 0,
  };
  if (poll(&stdin_poll, 1, 0) <= 0 || !(stdin_poll.revents & POLLIN)) {
    return;
  }

  uint8_t buf[64];
  ssize_t n = read(STDIN_FILENO, buf, sizeof(buf));
  if (n <= 0) {
    return;
  }
  for (ssize_t i = 0; i < n; i++) {
    serial_rx_push(buf[i]);
  }
}

static void serial_putc(char ch) {
  MUXDEF(CONFIG_TARGET_AM, putch(ch), putc(ch, stderr));
}

static void serial_io_handler(uint32_t offset, int len, bool is_write) {
  assert(len == 1);
  switch (offset) {
  /* We bind the serial port with the host stderr in NEMU. */
  case CH_OFFSET:
    if (is_write) {
      serial_putc(serial_base[0]);
    } else {
      serial_fill_input();
      serial_base[0] = serial_rx_pop();
      serial_base[5] = serial_rx_available() ? 0x21 : 0x20;
    }
    break;
  default: {
    if (isSoC) {
      // 5 : Line Status Register (LSR)
      if (offset == 5) {
        serial_fill_input();
        serial_base[5] = serial_rx_available() ? 0x21 : 0x20;
      }
    } else {
      panic("do not support offset = %d", offset);
    }
  }
  }
}

void init_serial() {
  serial_base = new_space(8);
#ifdef CONFIG_HAS_PORT_IO
  add_pio_map("serial", CONFIG_SERIAL_PORT, serial_base, 8, serial_io_handler);
#else
  add_mmio_map("serial", CONFIG_SERIAL_MMIO, serial_base, 8, serial_io_handler);
#endif
}
