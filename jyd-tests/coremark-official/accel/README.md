# CoreMark custom-instruction builds

The current image uses the patched GCC 16 compiler. GCC recognizes ordinary C
data-flow shapes and emits the selected custom instructions through internal
functions and RISC-V machine descriptions.

`COREMARK_XEXTS` remains the image's ISA identity and the input to the final ELF
auditor.  GCC rejects project-local `x*` names in `-march`, so the actual target
selection is expressed with the patched compiler's `-m` options.  The current
combined build is:

```sh
make ARCH=riscv32-jyd ITERATIONS=10000 \
  COREMARK_XEXTS=_xmbm_xcrcu8_xlistrev_xmsum_xdfa4p_xlistfind_xmacacc_xdotn \
  EXTRA_CFLAGS='-mxmbm -mxcrcu8 -mxlistrev -mclipped-rising-score-reduce -mxdfa4p -mxlistfind -mxmacacc -mxdotn' \
  CROSS_COMPILE=/path/to/patched-toolchain/bin/riscv64-unknown-linux-gnu- \
  image

make ARCH=riscv32-jyd ITERATIONS=10000 \
  COREMARK_XEXTS=_xmbm_xcrcu8_xlistrev_xmsum_xdfa4p_xlistfind_xmacacc_xdotn \
  EXTRA_CFLAGS='-mxmbm -mxcrcu8 -mxlistrev -mclipped-rising-score-reduce -mxdfa4p -mxlistfind -mxmacacc -mxdotn' \
  CROSS_COMPILE=/path/to/patched-toolchain/bin/riscv64-unknown-linux-gnu- \
  audit-accel
```

Supported image identity names are `xmac16`, `xdot16`, `xdotn`, `xbmul`, `xmbm`,
`xcrcu8`, `xlistfind`, `xlistrev`, `xmacacc`, `xmsum`, `xdfacnt`, `xdfa2`,
`xdfa4`, `xdfa4h`, and `xdfa4p`.  The active GCC patch, compiler source SHA,
selection details, checkers, and performance evidence are documented in
`gcc-md/README.md`.

`audit-accel` is mandatory for an accelerated image.  It fails if an enabled
instruction or required sub-operation is absent. When xmacacc is enabled it lowers the full matrix
bit-extract loop, so the auditor reports the overlapping xmbm selection as
superseded.  Reporting always follows the benchmark's normal floating-point
path through the EEMBC formatter and AM SoftFloat; compiler passes do not
alter report calls or format strings.

`make check` verifies the unmodified upstream `coremark.md5` under `src/`.
Compiler integration, forced headers, and experimental support files remain
outside that protected directory.
