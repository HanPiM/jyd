# GCC machine-description accelerator selection

This directory contains the GCC 16 implementation of the custom accelerators.
The build compiles ordinary C sources. GCC recognizes the supported source idioms and
emits the custom instructions through internal functions and RISC-V machine
descriptions.

Check out public GCC base commit `ff20c357b3f`, then apply
`active-accel-gcc16.patch` with `git apply --index --unidiff-zero`. Configure
and build an RV32-capable RISC-V cross compiler in separate source, build, and
install directories. The patch SHA-256 is
`accc713e001e15a736172596863629c15ccc94bbd127b46f1593b3fbfa49b8c1`.
The patch includes the loop-bound analysis prerequisite that was previously a
local-only commit on top of that public base.

## Selection paths

| Accelerator | GCC selection | Flag | Custom encoding |
|---|---|---|---|
| xmbm | matrix bit-extraction expression | `-mxmbm` | custom-0, funct3 5, funct7 1 |
| xcrcu8 | GCC reversed-CRC builtin/optab | `-mxcrcu8` | custom-0, funct3 0, funct7 0 |
| xdup8lo | low-byte duplication expression | `-mxdup8lo` | custom-0, funct3 1, funct7 1, rs2 0 |
| xmsum | clipped rising-score loop recognizer | `-mclipped-rising-score-reduce` | custom-0, funct3 7, funct7 2 |
| xlistrev | in-place list-reversal recognizer | `-mxlistrev` | custom-0, funct3 6, funct7 0/2 |
| xdfa4p | numeric-token state-scan recognizer | `-mxdfa4p` | custom-2, funct3 5, funct7 2 |
| xdfascan | NUL-terminated numeric-token scan recognizer | `-mxdfascan` | custom-2, funct3 5, funct7 3 |
| xlistfind | linked-list search recognizer | `-mxlistfind` | custom-0, funct3 6, funct7 1/3 |
| xmacacc | matrix multiply recognizer and target loop expansion | `-mxmacacc` | custom-0, funct3 3, funct7 4-9 |
| xdotn | runtime-N matrix dot walker selected by the matrix recognizer | `-mxdotn` | custom-0, funct3 4, funct7 3/4/5 |
| xpaddh2 | aligned 16-bit matrix-add loop recognizer | `-mxpaddh2` | custom-0, funct3 1, funct7 2 |

The combined build enables `-mxmbm`, `-mxmacacc`, and `-mxdotn`. GCC emits a
runtime-dimension configuration operation followed by one xdotn instruction
per signed or bit-extract dot product. Dimensions from 1 through 65535 use the
walker; zero or larger dimensions retain the scalar xmacacc target-loop
expansion. Consequently no xmbm site remains in the combined ELF; the ELF
auditor reports xmbm as superseded. Building with `-mxmbm` without `-mxmacacc`
still selects the two expected xmbm sites from unmodified `core_matrix.c`.

The xcrcu8 integration uses GCC's generic
`__builtin_rev_crc16_data8(crc, data, 0x8005)` interface in `xcrc_hw.h`.  The
RISC-V CRC optab selects xcrcu8 for this width and polynomial; the benchmark
header contains no custom inline assembly.

The xdup8lo operation copies source byte 1 into byte 0 while preserving source
bits 31 through 8. Its peephole matches the exact four-operation RTL shape,
checks temporary-register lifetime and aliasing, and remains disabled without
`-mxdup8lo`.

The xmsum and xlistrev recognizers are shape based.  The numeric DFA path also
verifies the expected scan and counter structure.  Reporting is outside the
accelerator pass and uses the ordinary EEMBC formatter and AM SoftFloat path.
`xdfascan` consumes a runtime pointer until the first reachable NUL and has no
fixed string length, seed, iteration count, source symbol, or filename
condition. The xdup8lo and xpaddh2 selectors likewise use data/control-flow
shape and retain scalar fallbacks when their complete semantics cannot be
proved.

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

The auditor requires all enabled instruction families, xdotn configuration and
both data modes, every xlistfind and xmacacc sub-operation, and the xdfa
final-counter read. Soft-float helper symbols are expected in the normal report
path and are not accelerator-audit failures.

## Validation

The frozen compiler passed:

- GCC `all-gcc` and `install-gcc` with 16 jobs.
- Every checker listed below.
- Clean-tree patch application, backend-integrity, no-plugin, and
  name-independence audits.
- `make -C npc checkformat`, `make -C npc verilog`, the xdfascan directed
  comparison, and the `riscv32-jyd` add and load-store tests.
- NPC ITERATIONS=10 with difftest: 1,252,377 cycles, 334,735 retired
  instructions, CRCs `e714/1fd7/8e3a/fcaf`, and GOOD TRAP.
- NPC ITERATIONS=100 without difftest: 11,859,471 cycles, 2,901,530 retired
  instructions, CRCs `e714/1fd7/8e3a/988c`, and GOOD TRAP.
- The affine 10/100 estimate for ITERATIONS=10000 at 300 MHz:
  1,178,639,811 cycles and 285,248,980 instructions, or `3.928799370s`.
  This is an unboarded fitted result, not a complete 10,000-iteration NPC run
  or a reportable 10-second CoreMark score.
- The exact candidate's selected 300 MHz post-route result is WNS
  `-0.718ns`, TNS `-1231.531ns`, and WHS `+0.063ns`. It passes the experiment's
  strict `WNS > -0.8ns` acceptance boundary by `0.082ns`; Vivado still reports
  setup timing violations, so this is not zero-slack timing closure.
- Exact NEMU ITERATIONS=10000 through the owning AM `make run` target:
  CRCs `e714/1fd7/8e3a/988c`, GOOD TRAP, and 285,163,978 guest instructions.
  Its host-timer duration is below CoreMark's 10-second reporting minimum, so
  it is retained as semantic and instruction-count evidence only.

The final ELF has 38 static xcrcu8 sites, one xlistrev site, five xmsum sites,
two xdup8lo sites, two xdfascan sites, four xlistfind sites, six xmacacc sites,
four xdotn sites (two configuration, one signed, and one bit-extract), two
xpaddh2 sites, and one xdfa final-counter read. The generated program image was
exercised by NEMU and NPC difftest.

## Checkers

- `check-xbmul-pattern.sh <gcc>` checks the legacy packed-field pattern and
  xcrcu8 selection from unmodified CoreMark sources.
- `check-xdup8lo.sh <gcc>` checks exact and renamed positive shapes, explicit
  option disablement, live-temporary rejection, wrong shift/mask rejection,
  and selection from unmodified CoreMark sources.
- `check-xmbm-xdfa4p.sh <gcc>` checks xmbm and xdfa4p control/enabled builds.
- `check-clipped-rising-score.sh <gcc>` checks xmsum positive and negative
  selection.
- `check-xlistrev.sh <gcc>` checks list-reversal positive and negative
  selection.
- `check-xdfa4h.sh <gcc>` preserves coverage for the older xdfa4h mode.
- `check-xpaddh2.sh <gcc>` checks the aligned canonical loop, control-flow and
  conversion guards, renamed-source selection, and disable fallback.
- `check-xdfascan.sh <gcc>` checks the full runtime NUL scan and rejects
  delimiter, final-count, and mixed-callee near matches.
- `check-xlistfind-xmacacc.sh <gcc>` checks both xlistfind sub-operations and
  all six xmacacc sub-operations, all three xdotn sub-operations, the invalid-N
  fallback, and renamed-source name independence.
- `check-backend-integrity.sh` rejects symbol-name matching, pseudo-float
  support, and alternate compiler-extension paths. It also proves that the
  patch itself materializes the accelerator pass source; the GCC build invokes
  its clean-tree mode before compiling.

## History

`xbmul`, `xdfa4h`, and `xdfa4p` remain in the backend and their checkers remain
valid, but the current combined image uses xmbm and xdfascan. `xmac16` and
`xdot16` are not part of the current combined build.
