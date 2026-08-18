# GCC machine-description accelerator selection

This directory contains the GCC 16 implementation of the custom accelerators.
The build compiles ordinary C sources. GCC recognizes the supported source idioms and
emits the custom instructions through internal functions and RISC-V machine
descriptions. CRC lowering is implemented in GCC's middle end and is enabled for
the LTO build with the explicit `-fcrc-semantic-lto` option; there is no forced
CRC header or source macro substitution.

Check out public GCC base commit `ff20c357b3f`, then apply
`active-accel-gcc16.patch` with `git apply --index --unidiff-zero`. Configure
and build an RV32-capable RISC-V cross compiler in separate source, build, and
install directories. The patch SHA-256 is
`b666bc17ec70e7c75e0773b22e320613e8cbf00bb51ed4bf74de8b2008cf9b43`.
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
| xdotn | runtime-N matrix dot and row walkers selected by the matrix recognizer | `-mxdotn` | custom-0, funct3 4, funct7 3/4/5/6/7 |
| xpaddh2 | aligned 16-bit matrix-add loop recognizer | `-mxpaddh2` | custom-0, funct3 1, funct7 2 |

The combined build enables `-mxmbm`, `-mxmacacc`, and `-mxdotn`. For
non-overlapping runtime dimensions from 4 through 16, GCC checks that the
complete A, B, and C ranges do not wrap, then emits a configuration operation
carrying N and the C pointer followed by one signed or bit-extract row
instruction per A row. Non-overlapping dimensions from 1 through 3 and 17
through 32767 use the scalar-result xdotn walker, one instruction per dot
product. Overlapping or wrapping ranges in that interval use an exact scalar
fallback that preserves every original C initialization and accumulation
write. N=0 and dimensions above 32767 retain the generic xmacacc target-loop
path. Consequently no xmbm site remains in the combined ELF; the ELF auditor
reports xmbm as superseded. Building with `-mxmbm` without `-mxmacacc` still
selects the two expected xmbm sites from unmodified `core_matrix.c`.

The xcrcu8 integration recognizes the verified byte-at-a-time CRC data flow in
the early GIMPLE loop pass and lowers it to GCC's existing `CRC_REV` internal
function. The RISC-V CRC optab selects xcrcu8 for the 8-bit data, 16-bit CRC,
`0x8005` polynomial. The LTO propagation pass makes small, side-effect-free
wrappers containing a verified CRC internal operation available for cross-TU
inlining, while keeping unrelated accelerator functions from being cloned. The
old forced-include macro header has been removed from the tree.

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
its embedded CoreMark objects. When `xcrcu8` is selected, the Makefiles enable
LTO and pass `-fcrc-semantic-lto` to the five CoreMark benchmark translation
units and the final GCC-driver link; RT-Thread's kernel and AM objects remain
non-LTO.

Audit the selected configuration's ELF with:

```sh
make ARCH=riscv32-jyd \
  CROSS_COMPILE=/path/to/md-gcc/bin/riscv64-unknown-linux-gnu- \
  audit-accel
```

The auditor requires all enabled instruction families, xdotn configuration,
dot and row operations in both data modes, every xlistfind and xmacacc
sub-operation, and the xdfa final-counter read. Soft-float helper symbols are
expected in the normal report path and are not accelerator-audit failures.

## Validation

The frozen compiler passed:

- GCC `all-gcc` and `install-gcc` with 16 jobs.
- Every checker listed below.
- Clean-tree patch application, backend-integrity, no-plugin, and
  name-independence audits.
- `make -C npc checkformat`, `make -C npc verilog`, the xdfascan directed
  comparison, and the `riscv32-jyd` add and load-store tests.
- NPC ITERATIONS=10 with difftest: 867,924 cycles, 336,464 retired
  instructions, CRCs `e714/1fd7/8e3a/fcaf`, and GOOD TRAP.
- NPC ITERATIONS=100 without difftest: 8,082,019 cycles, 2,950,946 retired
  instructions, CRCs `e714/1fd7/8e3a/988c`, and GOOD TRAP.
- The affine 10/100 estimate for ITERATIONS=10000 at 300 MHz:
  801,632,469 cycles and 290,543,966 instructions, or `2.672108230s`.
  This is an unboarded fitted result, not a complete 10,000-iteration NPC run
  or a reportable 10-second CoreMark score.
- On the same RTL, the previous forced-header image fitted to 832,823,843
  cycles (`2.776079477s`). Semantic-LTO therefore does not trade away NPC
  performance; the fitted cycle count is 31,191,374 lower. The raw comparison
  archives are `coremark-cycle-estimate-reuse-pipeline-bca677c-20260818T010000Z`
  and `coremark-cycle-estimate-crc-semantic-formal-bca-20260818T072000Z`.
- The archived 300 MHz implementation of the unchanged `bca677c` RTL, made
  before the CRC build-chain replacement, has WNS `-0.717ns`, TNS
  `-1288.362ns`, and WHS `+0.084ns`. It passes the experiment's strict
  `WNS > -0.8ns` boundary, but remains a setup-violating physical baseline;
  it is not presented as implementation evidence for the new program image.
  The retained implementation archive is
  `vivado-bitstream-bca677c-300mhz-20260818T004000Z`.
- Exact NEMU ITERATIONS=10000 through the owning AM `make run` target:
  CRCs `e714/1fd7/8e3a/988c`, GOOD TRAP, and 290,465,859 guest instructions.
  Its host-timer duration is `8.654791s`, below CoreMark's 10-second reporting minimum, so
  it is retained as semantic and instruction-count evidence only in
  `coremark-nemu-crc-semantic-formal-bca-20260818T073000Z`.

Board service was unavailable for this compiler candidate. The NPC estimate is
explicitly unboarded, and board validation remains recorded debt.

The final ELF has 44 static xcrcu8 sites, one xlistrev site, five xmsum sites,
two xdup8lo sites, two xdfascan sites, four xlistfind sites, six xmacacc sites,
eight xdotn sites covering configuration plus signed and bit-extract dot and row
operations, two xpaddh2 sites, and one xdfa final-counter read. The generated program image was
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
  all six xmacacc sub-operations, all five xdotn sub-operations, option-off
  fallback, and renamed-source name independence.
- `check-backend-integrity.sh` rejects symbol-name matching, pseudo-float
  support, and alternate compiler-extension paths. It also proves that the
  patch itself materializes the accelerator pass source, checks the explicit
  CRC semantic-LTO option, and rejects forced CRC includes; the GCC build
  invokes its clean-tree mode before compiling.
- `check-crc-semantic-lto.sh <gcc>` compiles renamed multi-translation-unit
  CRC sources, checks option-on/option-off behavior, and rejects wrong-polynomial,
  wrong-trip-count, volatile, and side-effect near misses.

## History

`xbmul`, `xdfa4h`, and `xdfa4p` remain in the backend and their checkers remain
valid, but the current combined image uses xmbm and xdfascan. `xmac16` and
`xdot16` are not part of the current combined build.
