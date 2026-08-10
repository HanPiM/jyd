# CoreMark custom-instruction experiments

`COREMARK_XEXTS` enables source-transparent custom-instruction experiments
without editing the official files under `src/`.  A forced include provides
always-inline instruction wrappers and loop alternatives.  A GCC GIMPLE pass
redirects the selected CoreMark calls before early inlining.  The pass also
updates GCC's call graph; changing only the GIMPLE call can let later IPA
passes inline the old callee and silently remove the intended instruction.

The value uses an ISA-style suffix, for example
`_xbmul_xcrcu8_xlrev_xmsum_xstate`.  GCC rejects unknown `x*` extensions in
`-march`, so the standard extensions remain in the real `-march` option and
the custom suffix is passed as the real compiler definition
`-D__COREMARK_XEXTS=...`.  The automatically generated `COMPILER_FLAGS`
string reports the effective optimization and ISA options followed by the
plugin and forced-include options used by the build.

Supported names are `xmac16`, `xdot16`, `xbmul`, `xcrcu8`, `xlrev1`, `xlrev`,
`xstate`, `xstatec`, `xstate2`, `xstate4`, and `xmsum`. The word-fed state
instructions evaluate two or four characters and commit one transition mask
per token. For example:

```sh
make ARCH=riscv32-nemu ITERATIONS=10000 \
  COREMARK_XEXTS=_xbmul_xcrcu8_xlrev_xmsum_xstate image
make ARCH=riscv32-nemu ITERATIONS=10000 \
  COREMARK_XEXTS=_xbmul_xcrcu8_xlrev_xmsum_xstate audit-accel
```

The GCC plugin headers require `gmp.h`.  Override
`GCC_PLUGIN_GMP_INCLUDE=/path/to/gmp/include` when it is not installed in the
host compiler's normal include path.

`audit-accel` is mandatory for an accelerated image.  It fails if an enabled
instruction is absent or if an `__cm_*` wrapper survives the final link.  This
ensures the wrapper introduces no function call, stack frame, or associated
save/restore loads and stores.  Inspect the reported instruction sites when
adding a wrapper with enough register operands to cause compiler spills.

`make check` uses the unmodified upstream `coremark.md5` from the official
repository and checks it from `src/`.  Optimization headers and plugins must
remain outside that protected directory.

With the default `PSEUDO_FLOAT=1`, the same plugin redirects only CoreMark's
reporting calls to `float_dump.c` in `fp12` mode.  It prints elapsed seconds,
iterations/second, and the final CoreMark score with 12 fractional digits by
using integer ratios; the benchmark remains compiled with `HAS_FLOAT=0` and
does not link double-precision or SoftFloat helpers.  The helper buffers the
short-run warning so the official output order is preserved.  Use
`PSEUDO_FLOAT=0` only when intentionally testing CoreMark's original
floating-point reporting path.  `audit-accel` checks the `fp12` report image
for floating-point helper symbols in addition to auditing custom instructions.
