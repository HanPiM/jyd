# GCC machine-description accelerator experiments

This directory holds the machine-description (MD) migration of the custom
accelerator instruction selections that the CoreMark experiment drives.  The
goal is to replace the name-gated `xaccel_plugin` call substitution with
backend and middle-end recognition of the ordinary benchmark source, so the
plugin is no longer required.

Apply `active-accel-gcc16.patch` to GCC 16, configure an RV32-capable RISC-V
cross compiler, and pass the resulting compiler to the checkers below.  All
checkers pass against the compiler built from this patch as it stands today.

## Accelerators and their MD paths

| Accelerator | Selection | Flag | Encodings |
|---|---|---|---|
| xbmul | peephole on the matrix bit-extraction expression | `-mxbmul` | `.insn r 0x0b, 5, 0` |
| xcrcu8 | GCC 16's standard CRC loop recognition | `-mxcrcu8` | `.insn r 0x0b, 0, 0` |
| xmsum | middle-end loop recognizer (`clipped_rising_score_reduce`) | `-mclipped-rising-score-reduce` | `.insn r 0x0b, 7, 2` |
| xlrev2 | middle-end list-reversal recognizer | `-mxlrev2` | `.insn r 0x0b, 6, 0` / `.insn r 0x0b, 6, 2` |
| xdfa4h | middle-end `core_bench_state` rewrite | `-mxdfa4h` | `.insn r 0x5b, 0, 0` / `.insn r 0x5b, 5, 1` / `.insn r 0x5b, 2, 0` |

### xbmul (packed field multiply)

The peephole recognizes the ordinary C expression

```c
((value >> 2) & 0xfu) * ((value >> 5) & 0x7fu)
```

in the RISC-V backend.  It does not inspect function names and does not use a
GIMPLE call-replacement plugin.  The `-mxbmul` target option enables a
`define_peephole2` pattern that selects the existing custom-0 encoding through
the semantic `packed_field_mul` `define_insn`.

The control build must retain the five standard arithmetic instructions, while
the enabled build must contain one `.insn r 0x0b, 5, 0`.

The peephole deliberately requires the exact two fields from one source value
and verifies that eliminated temporaries are dead. Arithmetic and logical
right shifts are both accepted because the masks retain only low bits where
their values are equal. Expressions
with different masks, shifts, operands, or live intermediate values do not
match.

### xcrcu8 (byte CRC16)

GCC 16 recognizes the benchmark's ordinary eight-iteration CRC loop and lowers
it to `.CRC_REV(crc, byte, 0x8005)`; the `bitmanip.md` pattern `-mxcrcu8`
selects the instruction only for that data width, CRC width, and polynomial.
No function or macro name is involved.

### xmsum (clipped rising-score reduce)

The remaining xmsum use in `core_bench_state` is a memory-reading nested
reduction with no ordinary scalar RTL expression or standard optab for an MD
file to match.  The middle-end pass `pass_clipped_rising_score` (file
`tree-clipped-rising-score.cc`, pass id `clippedscore`, run after early VRP)
recognizes the loop by its shape — three parameters whose types match the
clipped rising-score reduction, verified by `clipped_rising_score_p` — and
lowers it through an IFN, optab, and the `clipped_rising_score_reduce`
`define_insn`.  No source identifier is consulted.

### xlrev2 (in-place list reversal)

The same pass recognizes the benchmark's list-walk-and-relink idiom
(`list_reverse_p` on the function parameter), replaces the walk with an
`IFN_XLREV2` call lowered to the two-instruction `xlrev2si2` pattern, and
keeps the software fallback return path for empty lists.  `-mxlrev2` gates
recognition.

### xdfa4h (numeric-token DFA scan)

The pass rewrites `core_bench_state`'s two per-character
`core_state_transition` scans into step loops over the hardware DFA
(`IFN_XDFA4H_STEP`, `.insn r 0x5b, 5, 1`), resets the hardware counters before
the first scan (`IFN_XDFACNT_INIT`, `.insn r 0x5b, 0, 0`), and replaces the
`track_counts[]` loads in the CRC epilogue with per-state counter reads
(`IFN_XDFA4H_READ`, `.insn r 0x5b, 2, 0`).  The software `final_counts[]`
remain the final-count source; only the per-character transition counting
moves to hardware.

The DFA is fixed target hardware, so recognition is gated on the function
name `core_bench_state`, but the surrounding shape (two transition calls, a
`track_counts` array read) is still verified so a refactored or renamed
function is left alone.

## NEMU validation

Build the benchmark with the patched compiler and no plugin:

```sh
make ARCH=riscv32-nemu \
  CROSS_COMPILE=/path/to/md-toolchain/riscv64-linux-gnu- \
  COREMARK_XEXTS= \
  EXTRA_CFLAGS="-mxbmul -mxcrcu8 -mxlrev2 -mxdfa4h -mclipped-rising-score-reduce" \
  run
```

The run must print the standard CRC quartet (`0xe714` / `0x1fd7` / `0x8e3a` /
`0x988c`), "Correct operation validated", and `HIT GOOD TRAP`.  The plugin
build with `COREMARK_XEXTS=xbmul,xcrcu8,xlrev2,xmsum,xdfa4h` must produce the
same CRCs.

## Checkers

- `check-xbmul-pattern.sh <gcc>` — xbmul control/enabled assembly contrast,
  plus `-mxcrcu8` against unmodified `src/core_util.c` and the xbmul pattern
  against unmodified `src/core_matrix.c`.
- `check-clipped-rising-score.sh <gcc>` — `-mclipped-rising-score-reduce`
  positive/negative selection tests and the unmodified `src/core_matrix.c`
  match.
- `check-xlrev2.sh <gcc>` — `-mxlrev2` positive (list reversal) and negative
  (plain walk) selection, plus the unmodified `src/core_list_join.c` four
  `.insn r 0x0b, 6` sites.
- `check-xdfa4h.sh <gcc>` — compiles `src/core_state.c` with `-mxdfa4h`,
  checks the 046t dump for the init, two step loops, and counter read, and
  counts the emitted `.insn r 0x5b` mnemonics.

## History

`xmac16` and `xdot16` are intentionally outside this migration because their
matrix/vector substitutions are not enabled.

`archived-clipped-rising-score-gcc16.patch` preserves the standalone
clipped-score experiment (recognizer + IFN + optab + pattern in one patch,
without the xlrev2/xdfa4h additions).  Its checkers and pattern sources
(`clipped-rising-score-*.c`, `check-clipped-rising-score.sh`) remain valid.
