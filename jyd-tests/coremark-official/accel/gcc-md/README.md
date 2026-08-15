# GCC machine-description accelerator selection

This directory contains the GCC 16 implementation of the custom accelerators.
The build compiles ordinary C sources. GCC recognizes the supported source idioms and
emits the custom instructions through internal functions and RISC-V machine
descriptions.

Apply `active-accel-gcc16.patch` to GCC at base commit `39064899496`, then
configure and build an RV32-capable RISC-V cross compiler in separate source,
build, and install directories.  The validated patched source commit is
`9fc2dbe6960b6990eb536858d879c3b20e3d1034`; the patch SHA-256 is
`c6633c43fbc9eda8eb3eaa4b6c59d7bf5ba331eedb47a5d4116e1ad92f97e01a`.

## Selection paths

| Accelerator | GCC selection | Flag | Custom encoding |
|---|---|---|---|
| xmbm | matrix bit-extraction expression | `-mxmbm` | custom-0, funct3 5, funct7 1 |
| xcrcu8 | GCC reversed-CRC builtin/optab | `-mxcrcu8` | custom-0, funct3 0, funct7 0 |
| xmsum | clipped rising-score loop recognizer | `-mclipped-rising-score-reduce` | custom-0, funct3 7, funct7 2 |
| xlistrev | in-place list-reversal recognizer | `-mxlistrev` | custom-0, funct3 6, funct7 0/2 |
| xdfa4p | numeric-token state-scan recognizer | `-mxdfa4p` | custom-2, funct3 5, funct7 2 |
| xlistfind | linked-list search recognizer | `-mxlistfind` | custom-0, funct3 6, funct7 1/3 |
| xmacacc | matrix multiply recognizer and target loop expansion | `-mxmacacc` | custom-0, funct3 3, funct7 4-9 |

The combined build enables both `-mxmbm` and `-mxmacacc`. `xmacacc` lowers
the entire matrix multiply and bit-extract loops, so no xmbm site remains in
that ELF; the ELF auditor reports xmbm as superseded.  Building with `-mxmbm`
without `-mxmacacc` still selects the two expected xmbm sites from unmodified
`core_matrix.c`.

The xcrcu8 integration uses GCC's generic
`__builtin_rev_crc16_data8(crc, data, 0x8005)` interface in `xcrc_hw.h`.  The
RISC-V CRC optab selects xcrcu8 for this width and polynomial; the benchmark
header contains no custom inline assembly.

The xmsum and xlistrev recognizers are shape based.  The numeric DFA path also
verifies the expected scan and counter structure.  Reporting is outside the
accelerator pass and uses the ordinary EEMBC formatter and AM SoftFloat path.

## Build and audit

Build the patched compiler, then use the checked-in defaults:

```sh
./accel/gcc-md/build-md-gcc.sh /path/to/md-gcc
make ARCH=riscv32-jyd \
  CROSS_COMPILE=/path/to/md-gcc/bin/riscv64-unknown-linux-gnu- \
  run
```

`coremark-defaults.mk` supplies the final standard-extension set, accelerator
identity, and matching `-m` flags. RT-Thread Nano imports the same file only for
its embedded CoreMark objects.

Audit the selected configuration's ELF with:

```sh
make ARCH=riscv32-jyd \
  CROSS_COMPILE=/path/to/md-gcc/bin/riscv64-unknown-linux-gnu- \
  audit-accel
```

The auditor requires all enabled instruction families, every xlistfind and
xmacacc sub-operation, and the xdfa final-counter read. Soft-float helper symbols are expected in the normal report
path and are not accelerator-audit failures.

## Validation

The frozen compiler passed:

- GCC `all-gcc` and `install-gcc` with 16 jobs.
- Every checker listed below.
- `make -C npc checkformat` and `make -C npc verilog`.
- NPC ITERATIONS=10 with difftest: 1,641,168 cycles, 804,670 retired
  instructions, correct CRCs, and GOOD TRAP.
- NPC ITERATIONS=100 without difftest: 15,930,335 cycles, 7,767,333 retired
  instructions, correct CRCs, and GOOD TRAP.
- The affine 10/100 estimate for ITERATIONS=10000 at 300 MHz:
  1,587,738,705 cycles, or `5.292462350s`, leaving `107.537650ms` below the
  strict 5.4-second target.
- Exact NEMU ITERATIONS=10000 through the owning AM `make run` target:
  CRCs `e714/1fd7/8e3a/988c`, Correct operation validated, GOOD TRAP, and
  773,441,484 guest instructions.

The final ELF has 38 static xcrcu8 sites, one xlistrev site, five xmsum sites,
two xdfa4p step sites, four xlistfind sites, six xmacacc sites, and one xdfa
final-counter read.  No Vivado implementation was needed for this migration:
the RTL and the custom instruction encodings are unchanged, while the generated
program image was exercised by NEMU and NPC difftest.

## Checkers

- `check-xbmul-pattern.sh <gcc>` checks the legacy packed-field pattern and
  xcrcu8 selection from unmodified CoreMark sources.
- `check-xmbm-xdfa4p.sh <gcc>` checks xmbm and xdfa4p control/enabled builds.
- `check-clipped-rising-score.sh <gcc>` checks xmsum positive and negative
  selection.
- `check-xlistrev.sh <gcc>` checks list-reversal positive and negative
  selection.
- `check-xdfa4h.sh <gcc>` preserves coverage for the older xdfa4h mode.
- `check-xlistfind-xmacacc.sh <gcc>` checks both xlistfind sub-operations and
  all six xmacacc sub-operations.
- `check-backend-integrity.sh` rejects symbol-name matching, pseudo-float
  support, and alternate compiler-extension paths.

## History

`xbmul` and `xdfa4h` remain in the backend and their checkers remain valid, but
the current combined image uses xmbm and xdfa4p.  `xmac16` and `xdot16` are not
part of the current combined build.
