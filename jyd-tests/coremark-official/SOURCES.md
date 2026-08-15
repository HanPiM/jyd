# Source boundaries

This directory intentionally separates the official benchmark core from the
AM port. Check this file before modifying a source file.

## Official CoreMark benchmark files

The following files come from EEMBC CoreMark commit recorded in
`SOURCE_COMMIT` and must remain byte-for-byte identical to upstream:

- `src/core_list_join.c`
- `src/core_main.c`
- `src/core_matrix.c`
- `src/core_state.c`
- `src/core_util.c`
- `src/coremark.h`

Do not modify these files for AM integration, output formatting, compiler
options, or performance experiments. Put all port changes in the files listed
below. `coremark.md5` is retained as the upstream checksum file. At the pinned
commit its `coremark.h` digest is stale; the vendored `src/coremark.h` was
separately compared byte-for-byte with that exact upstream revision.

## EEMBC formatter files adapted for AM

- `src/ee_printf.c`: EEMBC `barebones/ee_printf.c`; its character output hook
  is connected to AM `putch()`. The local reduction retains only the formats
  used by CoreMark, including `%f`, and keeps EEMBC's `fcvt` conversion path.
- `src/cvt.c`: EEMBC `barebones/cvt.c`; its `modf` dependency is redirected to
  the AM SoftFloat adapter.

These are open-source EEMBC support files, not benchmark algorithms. Avoid
adding formatter features locally; replace the formatter with an established
open-source implementation if future CoreMark output needs unsupported forms.
The benchmark always uses this floating-point reporting path; there is no
integer-only report substitution mode.

## AM port files

- `src/core_portme.h`: CoreMark port configuration, data types, `HAS_FLOAT`,
  compiler metadata, memory method, run-mode selection, and the optional
  `COREMARK_EMBEDDED_RTT` entry-point rename.
- `src/core_portme.c`: AM timer/device initialization and portable hooks.
- `src/coremark_softfloat.c`: name adapter from EEMBC `cvt.c` to the AM
  SoftFloat library.
- `Makefile`: AM integration, configuration-keyed application builds, and
  effective compiler flag reporting.

When `COREMARK_EMBEDDED_RTT` is defined, the port leaves device initialization
to RT-Thread, renames CoreMark's `main` to `coremark_main`, and routes the EEMBC
formatter sink through `rt_hw_console_output`. Standalone behavior is unchanged.

The benchmark reuses AM startup, linker scripts, timer, UART, and device code.
Do not add local `_trm_init`, `ioe_init`, UART drivers, or floating-point
algorithms to this test.

## Provenance and documentation

- `SOURCE_COMMIT`: exact EEMBC CoreMark source revision.
- `LICENSE.md`: upstream CoreMark license.
- `README.md`: build and run instructions.
- `SOURCES.md`: this modification boundary.
