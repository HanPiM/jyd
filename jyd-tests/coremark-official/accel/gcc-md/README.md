# GCC machine-description xbmul experiment

This experiment recognizes the ordinary C expression

```c
((value >> 2) & 0xfu) * ((value >> 5) & 0x7fu)
```

in the RISC-V backend.  It does not inspect function names and does not use a
GIMPLE call-replacement plugin.  The `-mxbmul` target option enables a
`define_peephole2` pattern that selects the existing custom-0 encoding through
the semantic `packed_field_mul` `define_insn`.

Apply `xbmul-gcc16.patch` to GCC 16, configure an RV32-capable RISC-V cross
compiler, and pass the resulting compiler to `check-xbmul-pattern.sh`.  The
control build must retain the five standard arithmetic instructions, while the
enabled build must contain one `.insn r 0x0b, 5, 0`.

The checker also compiles the repository's unmodified `src/core_matrix.c`. Its
control assembly must contain no custom encoding, while `-mxbmul` must select
the instruction from the ordinary matrix bit-extraction expression.

The peephole deliberately requires the exact two fields from one source value
and verifies that eliminated temporaries are dead. Arithmetic and logical
right shifts are both accepted because the masks retain only low bits where
their values are equal. Expressions
with different masks, shifts, operands, or live intermediate values do not
match.
