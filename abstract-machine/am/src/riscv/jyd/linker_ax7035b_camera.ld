ENTRY(_start)

MEMORY {
  IROM (rx)  : ORIGIN = 0x80000000, LENGTH = 16K
  DRAM (rwx) : ORIGIN = 0x80100000, LENGTH = 48K
}

SECTIONS {
  .text : {
    *(entry)
    *(.text*)
  } > IROM

  .rodata : ALIGN(4) {
    *(.rodata*)
    *(.srodata*)
  } > DRAM

  .data : {
    *(.data*)
    *(.sdata*)
  } > DRAM

  .bss : {
    *(.bss*)
    *(.sbss*)
    *(.scommon*)
  } > DRAM

  PERIPHERAL_BASE = 0x80200000;
  SW_LOW   = PERIPHERAL_BASE + 0x00;
  SW_HIGH  = PERIPHERAL_BASE + 0x04;
  KEY_REG  = PERIPHERAL_BASE + 0x10;
  SEG_REG  = PERIPHERAL_BASE + 0x20;
  LED_REG  = PERIPHERAL_BASE + 0x40;
  CNT_REG  = PERIPHERAL_BASE + 0x50;
  AHT10_STATUS_REG = PERIPHERAL_BASE + 0x60;
  AHT10_TEMP_REG   = PERIPHERAL_BASE + 0x64;
  AHT10_HUMI_REG   = PERIPHERAL_BASE + 0x68;
  AHT10_SEQ_REG    = PERIPHERAL_BASE + 0x6c;
  CAMERA_STATUS_REG      = PERIPHERAL_BASE + 0x70;
  CAMERA_FRAME_COUNT_REG = PERIPHERAL_BASE + 0x74;
  CAMERA_SAMPLE_RGB_REG  = PERIPHERAL_BASE + 0x78;
  CAMERA_CONTROL_REG     = PERIPHERAL_BASE + 0x7c;
  _my_ext_serial_port = PERIPHERAL_BASE + 0xa0;

  _stack_top = ORIGIN(DRAM) + LENGTH(DRAM);
  _stack_pointer = _stack_top;
  _heap_start = _stack_top;
  _heap_end = _stack_top;
}
