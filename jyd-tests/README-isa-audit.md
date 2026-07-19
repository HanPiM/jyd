# Objdump ISA audit

`analyze-objdump-isa.py` counts instructions in a RISC-V objdump text listing and audits every 32-bit encoding against
the current JYD CPU implementation. It classifies encodings as implemented RV32I, RV32M, the current B subset,
implemented SYSTEM/CSR/privileged instructions, or unknown/potentially unimplemented. The B subset accepted by
`BExtensionUnit` is `clz`, `ctz`, `cpop`, `clmul`, `orc.b`, `xperm4`, `ror`, and `pack`.

The audit validates reserved `funct3`/`funct7` combinations rather than trusting mnemonic text or opcode alone. It
also reports the especially risky `opcode=0x13` (OP-IMM) and `opcode=0x33` (OP) encodings that the current IDU would
coarsely route to `BExtensionUnit`. Plain `fence`, `ecall`, `mret`, and the six CSR operation forms are included in
the support set; `fence.i`, `ebreak`, other unsupported privileged instructions, and unknown opcode families are not.

Run it from the repository root:

```sh
python3 jyd-tests/analyze-objdump-isa.py jyd-tests/2026/bin/xibei-withMext_clz.txt
```

Use `--entry 0xADDRESS` (repeatable) if the listing has another entry point, `--context N` to change the context
shown around suspicious instructions, or `--json` for machine-readable output. The command exits with status 1 if
it finds any unknown/potentially unimplemented 32-bit encoding; otherwise it exits with status 0.

The reachability label is only a direct-control-flow static approximation. It follows direct jumps and calls and
both sides of conditional branches, but it does not prove that an instruction executes at runtime and cannot resolve
indirect `jalr` or `mret` targets. Static presence, approximate reachability, and observed dynamic execution must
therefore be kept separate. The report also calls out non-32-bit entries because objdump may render padding or data as
`unimp`; their presence alone is not evidence that the CPU executes a compressed or unsupported instruction.
