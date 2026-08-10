# CoreMark custom-instruction experiments

`COREMARK_ACCELS` enables source-transparent custom-instruction experiments
without editing the official files under `src/`.  A forced include provides
always-inline instruction wrappers and loop alternatives.  A GCC GIMPLE pass
redirects the selected CoreMark calls before early inlining.  The pass also
updates GCC's call graph; changing only the GIMPLE call can let later IPA
passes inline the old callee and silently remove the intended instruction.

Supported names are `xmac16`, `xdot16`, `xbmul`, `xlrev`, `xstate`, `xstatec`,
`xstate2`, `xstate4`, and `xmsum`. The word-fed state instructions evaluate two
or four characters and commit one transition mask per token. For example:

```sh
make ARCH=riscv32-nemu ITERATIONS=10000 COREMARK_CRC_ACCEL=u8 \
  COREMARK_ACCELS=xbmul,xlrev,xstate,xmsum image
make ARCH=riscv32-nemu ITERATIONS=10000 COREMARK_CRC_ACCEL=u8 \
  COREMARK_ACCELS=xbmul,xlrev,xstate,xmsum audit-accel
```

The GCC plugin headers require `gmp.h`.  Override
`GCC_PLUGIN_GMP_INCLUDE=/path/to/gmp/include` when it is not installed in the
host compiler's normal include path.

`audit-accel` is mandatory for an accelerated image.  It fails if an enabled
instruction is absent or if an `__cm_*` wrapper survives the final link.  This
ensures the wrapper introduces no function call, stack frame, or associated
save/restore loads and stores.  Inspect the reported instruction sites when
adding a wrapper with enough register operands to cause compiler spills.
