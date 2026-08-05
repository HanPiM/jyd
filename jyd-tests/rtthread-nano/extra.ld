SECTIONS {
  .data.extra : ALIGN(4) {
    __fsymtab_start = .;
    KEEP(*(FSymTab))
    __fsymtab_end = .;
  } > DRAM
}
INSERT BEFORE .data;
