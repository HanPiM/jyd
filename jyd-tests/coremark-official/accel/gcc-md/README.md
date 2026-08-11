# GCC machine-description accelerator experiments

This experiment recognizes the ordinary C expression

```c
((value >> 2) & 0xfu) * ((value >> 5) & 0x7fu)
```

in the RISC-V backend.  It does not inspect function names and does not use a
GIMPLE call-replacement plugin.  The `-mxbmul` target option enables a
`define_peephole2` pattern that selects the existing custom-0 encoding through
the semantic `packed_field_mul` `define_insn`.

Apply `active-accel-gcc16.patch` to GCC 16, configure an RV32-capable RISC-V cross
compiler, and pass the resulting compiler to `check-xbmul-pattern.sh`.  The
control build must retain the five standard arithmetic instructions, while the
enabled build must contain one `.insn r 0x0b, 5, 0`.

The patch also maps GCC 16's standard CRC loop recognition to the byte CRC16
instruction. GCC reduces the
ordinary eight-iteration loop to `.CRC_REV(crc, byte, 0x8005)`; `-mxcrcu8`
selects the instruction only for that data width, CRC width, and polynomial.
No function or macro name is involved.

The checker also compiles the repository's unmodified `src/core_matrix.c`. Its
control assembly must contain no custom encoding, while `-mxbmul` must select
the instruction from the ordinary matrix bit-extraction expression. The same
checker verifies `-mxcrcu8` against unmodified `src/core_util.c`.

The peephole deliberately requires the exact two fields from one source value
and verifies that eliminated temporaries are dead. Arithmetic and logical
right shifts are both accepted because the masks retain only low bits where
their values are equal. Expressions
with different masks, shifts, operands, or live intermediate values do not
match.

`xmac16` and `xdot16` are intentionally outside this migration because their
matrix/vector substitutions are not enabled. `xmsum` remains enabled, but its
single instruction represents a complete memory-reading nested reduction.
That operation has no ordinary scalar RTL expression or standard optab for an
MD file to match; migrating it without a named-call substitution requires a
middle-end loop-idiom recognizer (or a narrower hardware instruction), not a
standalone `define_peephole2`.

An experimental semantic loop recognizer was implemented and evaluated, then
archived as `archived-clipped-rising-score-gcc16.patch`.  Its neutral semantic
name is `clipped_rising_score_reduce`; it recognizes the loop without checking
source identifiers and lowers through an IFN, optab, and RISC-V MD pattern.
`clipped-rising-score-pattern.c` and `check-clipped-rising-score.sh` retain the
positive and negative selection tests.  The experiment is deliberately not in
`active-accel-gcc16.patch`: the recognizer and its memory-ordering model are too
complex for the measured result, and the fair NEMU run remained slower than the
existing plugin path.  Production xmsum selection therefore stays in the
plugin.
