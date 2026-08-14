SECTIONS {
  .data.extra : ALIGN(4) {
    __fsymtab_start = .;
    KEEP(*(FSymTab))
    __fsymtab_end = .;
    __vsymtab_start = .;
    KEEP(*(VSymTab))
    __vsymtab_end = .;
  }
}
INSERT BEFORE .data;
