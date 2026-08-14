# CoreMark custom-instruction builds

The current accelerated CoreMark image uses the patched GCC 16 compiler and
`COREMARK_GCC_MD=1`.  GCC recognizes the ordinary benchmark sources and emits
the selected custom instructions through internal functions and RISC-V machine
descriptions.  The production path does not load `xaccel_plugin` and does not
substitute calls through `xaccel_wrappers.c`.

`COREMARK_XEXTS` remains the image's ISA identity and the input to the final ELF
auditor.  GCC rejects project-local `x*` names in `-march`, so the actual target
selection is expressed with the patched compiler's `-m` options.  The current
combined build is:

```sh
make ARCH=riscv32-jyd ITERATIONS=10000 \
  COREMARK_GCC_MD=1 \
  COREMARK_XEXTS=_xmbm_xcrcu8_xlistrev_xmsum_xdfa4p_xlistfind_xmacacc \
  EXTRA_CFLAGS='-mxmbm -mxcrcu8 -mxlistrev -mclipped-rising-score-reduce -mxdfa4p -mxlistfind -mxmacacc' \
  CROSS_COMPILE=/path/to/patched-toolchain/bin/riscv64-linux-gnu- \
  image

make ARCH=riscv32-jyd ITERATIONS=10000 \
  COREMARK_GCC_MD=1 \
  COREMARK_XEXTS=_xmbm_xcrcu8_xlistrev_xmsum_xdfa4p_xlistfind_xmacacc \
  EXTRA_CFLAGS='-mxmbm -mxcrcu8 -mxlistrev -mclipped-rising-score-reduce -mxdfa4p -mxlistfind -mxmacacc' \
  CROSS_COMPILE=/path/to/patched-toolchain/bin/riscv64-linux-gnu- \
  audit-accel
```

`COREMARK_GCC_MD=0` remains the Makefile default so an unmodified distribution
compiler can still build the benchmark and so historical plugin comparisons
remain reproducible.  Accelerated production and performance runs must pass
`COREMARK_GCC_MD=1` explicitly.  `COREMARK_XACCEL_EXPLORE` is plugin-only and
is rejected in GCC MD mode.

Supported image identity names are `xmac16`, `xdot16`, `xbmul`, `xmbm`,
`xcrcu8`, `xlistfind`, `xlistrev`, `xmacacc`, `xmsum`, `xdfacnt`, `xdfa2`,
`xdfa4`, `xdfa4h`, and `xdfa4p`.  The active GCC patch, compiler source SHA,
selection details, checkers, and performance evidence are documented in
`gcc-md/README.md`.

`audit-accel` is mandatory for an accelerated image.  It fails if an enabled
instruction or required sub-operation is absent, if an `__xaccel_*` wrapper
survives the final link, or if the pseudo-float report image contains floating-
point helper symbols.  When xmacacc is enabled it replaces the full matrix
bit-extract loop, so the auditor reports the overlapping xmbm selection as
superseded.

With the default `PSEUDO_FLOAT=1`, GCC adds
`-mcoremark-fp12-report` and redirects CoreMark's reporting calls to the
integer-only helpers in `float_dump.c`.  It prints elapsed seconds,
iterations/second, and the final score with 12 fractional digits without
linking double-precision or SoftFloat helpers.  Use `PSEUDO_FLOAT=0` only for
an intentional comparison with CoreMark's original floating-point reporting.

`make check` verifies the unmodified upstream `coremark.md5` under `src/`.
Compiler integration, forced headers, and experimental support files remain
outside that protected directory.
