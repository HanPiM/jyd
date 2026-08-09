ENTRY(_start)

MEMORY {
  IROM (rx) : ORIGIN = 0x80000000, LENGTH = 128K
  DRAM (rwx) : ORIGIN = 0x80100000, LENGTH = 256K
	/* 赛题文档中没有指定堆区，是直接给出编译产物的结果，具体的堆区地址和大小是
	 * 主办方那边设置的，这里设置的堆区只是提供给自己的测试程序使用，实际比赛时
	 * 是跑给出的编译产物的 */
	/* 为了优化，访存不支持大于 256K 的内存，必须放置在 DRAM 中 */
	heap (rwx) : ORIGIN = 0x80120000, LENGTH = 128K
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
	/* NOTE: when dump to binary, .data section should not be included in 
	 * main binary, it should be placed in a separate binary */

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

	_my_ext_serial_port = PERIPHERAL_BASE + 0xa0;
	/* _my_ext_clint = PERIPHERAL_BASE + 0x300; */

	_stack_top = ORIGIN(DRAM) + LENGTH(DRAM);
  _stack_pointer  = _stack_top;

  _heap_start = ORIGIN(heap);
	_heap_end = ORIGIN(heap) + LENGTH(heap);
}
