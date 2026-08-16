# GCC machine-description accelerator selection

This directory contains the GCC 16 implementation of the custom accelerators.
The build compiles ordinary C sources. GCC recognizes the supported source idioms and
emits the custom instructions through internal functions and RISC-V machine
descriptions.

Check out public GCC base commit `ff20c357b3f`, then apply
`active-accel-gcc16.patch` with `git apply --index --unidiff-zero`. Configure
and build an RV32-capable RISC-V cross compiler in separate source, build, and
install directories. The patch SHA-256 is
`05c3db37685d83f28f3575b0e175ddffcb6b01c95faa9858d87e43a85d0db20c`.
The patch includes the loop-bound analysis prerequisite that was previously a
local-only commit on top of that public base.

## Selection paths

| Accelerator | GCC selection | Flag | Custom encoding |
|---|---|---|---|
| xmbm | matrix bit-extraction expression | `-mxmbm` | custom-0, funct3 5, funct7 1 |
| xcrcu8 | GCC reversed-CRC builtin/optab | `-mxcrcu8` | custom-0, funct3 0, funct7 0 |
| xdup8lo | exact byte-copy bit-field idiom | `-mxdup8lo` | custom-0, funct3 1, funct7 1, rs2 x0 |
| xmsum | clipped rising-score loop recognizer | `-mclipped-rising-score-reduce` | custom-0, funct3 7, funct7 2 |
| xlistrev | in-place list-reversal recognizer | `-mxlistrev` | custom-0, funct3 6, funct7 0/2 |
| xdfa4p | numeric-token state-scan recognizer | `-mxdfa4p` | custom-2, funct3 5, funct7 2 |
| xlistfind | linked-list search recognizer | `-mxlistfind` | custom-0, funct3 6, funct7 1/3 |
| xmacacc | matrix multiply recognizer and target loop expansion | `-mxmacacc` | custom-0, funct3 3, funct7 4-9 |
| xdotn | runtime-N matrix dot walker selected by the matrix recognizer | `-mxdotn` | custom-0, funct3 4, funct7 3/4/5 |

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
- `make -C npc checkformat` and `make -C npc verilog`.
- `riscv32-jyd` add and directed xdup8lo tests with active difftest. The
  directed test covers same-register and distinct-register forms, fixed corner
  cases, and 4,096 deterministic 32-bit inputs.
- Isolated NPC ITERATIONS=10/100 measurements for both the enabled and control
  images, with CRCs `e714/1fd7/8e3a/fcaf` and `e714/1fd7/8e3a/988c`.
- The control affine ITERATIONS=10000 estimate is 1,306,730,398 cycles, or
  `4.355767993s` at 300 MHz. Enabling xdup8lo reduces that estimate to
  1,294,217,749 cycles, or `4.314059163s`: a measured reduction of 12,512,649
  cycles (`41.708830ms`). The retained results are
  `/srv/data/jyd/archive/coremark-cycle-estimate-xdup8lo-552b525-control-20260816T164220Z/`
  and
  `/srv/data/jyd/archive/coremark-cycle-estimate-xdup8lo-552b525-enabled-20260816T164057Z/`.
- The exact committed candidate completed 300 MHz post-route physical
  optimization with WNS `-0.694ns`, TNS `-1097.469ns`, and WHS `+0.085ns`.
  The selected DCP, timing report, provenance, and verified checksums are in
  `/srv/data/jyd/archive/vivado-xdup8lo-552b525-300mhz-20260817/`. This is
  implementation evidence only; the candidate has not yet been board tested.
- The final compiler executable SHA-256 is
  `fb203c1c269608dde38601f12df122be56407b6c5f95245744588187144aacca`.
  Its final ITERATIONS=10000 text image SHA-256 is
  `448172ee5c866932d9268a49cbfce83205b0e8cb41638ea7fec838755d5a83f8`.

The final ELF has 38 static xcrcu8 sites, two xdup8lo sites, one xlistrev site,
five xmsum sites, two xdfa4p step sites, four xlistfind sites, six xmacacc sites,
four xdotn sites (two configuration, one signed, and one bit-extract), and one
xdfa final-counter read. The generated program image was exercised by NPC
difftest; the protected CoreMark source MD5 check also passes.

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
- `check-xlistfind-xmacacc.sh <gcc>` checks both xlistfind sub-operations and
  all six xmacacc sub-operations, all three xdotn sub-operations, the invalid-N
  fallback, and renamed-source name independence.
- `check-backend-integrity.sh` rejects symbol-name matching, pseudo-float
  support, and alternate compiler-extension paths. It also proves that the
  patch itself materializes the accelerator pass source; the GCC build invokes
  its clean-tree mode before compiling.

## History

`xbmul` and `xdfa4h` remain in the backend and their checkers remain valid, but
the current combined image uses xmbm and xdfa4p.  `xmac16` and `xdot16` are not
part of the current combined build.
