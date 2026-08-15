# GCC machine-description accelerator selection

This directory contains the GCC 16 implementation of the custom accelerators.
The build compiles ordinary C sources. GCC recognizes the supported source idioms and
emits the custom instructions through internal functions and RISC-V machine
descriptions.

Apply `active-accel-gcc16.patch` to GCC at base commit `39064899496`, then
configure and build an RV32-capable RISC-V cross compiler in separate source,
build, and install directories.  The patch SHA-256 is
`d3bcbd358e73edb149079ad6baac251fa2c8571471d7580536168dc71218a863`.

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
| xdot9 | nine-term matrix dot walker selected by the matrix recognizer | `-mxdot9` | custom-0, funct3 4, funct7 1/2 |

The combined build enables `-mxmbm`, `-mxmacacc`, and `-mxdot9`.  For the
CoreMark `N == 9` shape, GCC emits one xdot9 instruction per signed or
bit-extract dot product. Other matrix sizes retain the existing scalar xmacacc
target-loop expansion. Consequently no xmbm site remains in the combined ELF;
the ELF auditor reports xmbm as superseded. Building with `-mxmbm` without
`-mxmacacc` still selects the two expected xmbm sites from unmodified
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

The auditor requires all enabled instruction families, both xdot9 modes, every
xlistfind and xmacacc sub-operation, and the xdfa final-counter read. Soft-float
helper symbols are expected in the normal report path and are not
accelerator-audit failures.

## Validation

The frozen compiler passed:

- GCC `all-gcc` and `install-gcc` with 16 jobs.
- Every checker listed below.
- `make -C npc checkformat` and `make -C npc verilog`.
- NPC ITERATIONS=10 with difftest: 1,517,951 cycles, 484,540 retired
  instructions, correct CRCs, and GOOD TRAP.
- NPC ITERATIONS=100 without difftest: 14,513,869 cycles, 4,396,417 retired
  instructions, correct CRCs, and GOOD TRAP.
- The affine 10/100 estimate for ITERATIONS=10000 at 300 MHz:
  1,444,064,849 cycles, or `4.813549497s`, leaving `186.450503ms` below the
  strict 5-second target. The retained machine-readable result is
  `/srv/data/jyd/archive/coremark-cycle-estimate-xdot9-formal-gcc-20260815T1320Z/estimate.json`.
- Exact NEMU ITERATIONS=10000 through the owning AM `make run` target:
  CRCs `e714/1fd7/8e3a/988c`, Correct operation validated, GOOD TRAP, and
  434,662,586 guest instructions.

The final ELF has 38 static xcrcu8 sites, one xlistrev site, five xmsum sites,
two xdfa4p step sites, four xlistfind sites, six xmacacc sites, two xdot9 sites,
and one xdfa final-counter read. The generated program image was exercised by
NEMU and NPC difftest. Post-route timing remains to be measured because xdot9
adds a local DSP-backed walker and DCache control.

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
  all six xmacacc sub-operations, both xdot9 modes, the non-nine-size fallback,
  and renamed-source name independence.
- `check-backend-integrity.sh` rejects symbol-name matching, pseudo-float
  support, and alternate compiler-extension paths.

## History

`xbmul` and `xdfa4h` remain in the backend and their checkers remain valid, but
the current combined image uses xmbm and xdfa4p.  `xmac16` and `xdot16` are not
part of the current combined build.
