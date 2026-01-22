ENTRY(_start)
/* PHDRS { text PT_LOAD; data PT_LOAD; } */

MEMORY {
  mrom : ORIGIN = 0x20000000, LENGTH = 4K
  sram : ORIGIN = 0x0f000000, LENGTH = 8K
	flash : ORIGIN = 0x30000000, LENGTH = 128K
	psram : ORIGIN = 0x80000000, LENGTH = 4M
}

SECTIONS {
	. = ORIGIN(flash);
  .text : {
		_text = .;
    *(entry)
    *(.text*)
		_etext = .;
  } > flash

	__text_load_start__ = LOADADDR(.text); /* flash addr */
	__text_size__       = SIZEOF(.text);   /* LEN */

  /* .rodata : { */
  /*   *(.rodata*) */
  /* } > mrom AT> mrom */
  .data : {
		_data = .;
    *(.sdata*)
    *(.data*)
		_edata = .;
  } > psram AT> flash

	__data_load_start__ = LOADADDR(.data); /* MA */
	__data_size__       = SIZEOF(.data);   /* LEN */

  .bss : {
		_bss = .;
    *(.bss*)
    *(.sbss*)
    *(.scommon)
		_ebss = .;
  } > sram

	__sram_start__ = ORIGIN(sram);
	__sram_size__ = LENGTH(sram);
	__sram_end__ = __sram_start__ + __sram_size__;

	__psram_start__ = ORIGIN(psram);
	__psram_size__ = LENGTH(psram);
	__psram_end__ = __psram_start__ + __psram_size__;

  _stack_top = __sram_end__ - _stack_size;
  _stack_pointer  = _stack_top + _stack_size;

  _heap_start = _ebss;
	_heap_end = __sram_end__;
}
