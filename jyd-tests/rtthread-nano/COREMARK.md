# Embedded CoreMark integration

RT-Thread Nano embeds CoreMark as the `coremark` msh command. The default uses
10,000 iterations and 2,000 bytes of benchmark data. Run `coremark` at `msh >`;
the synchronous command returns to the prompt after printing its report.
Its CoreMark objects import the standalone benchmark's GCC MD, standard ISA,
and accelerator defaults from `coremark-defaults.mk`; floating-point output
uses EEMBC `cvt.c` and the AM SoftFloat library.

## Selecting the benchmark source directory

`COREMARK_BENCH_DIR` identifies the directory containing
`core_list_join.c`, `core_main.c`, `core_matrix.c`, `core_state.c`,
`core_util.c`, and `coremark.h`. It defaults to `../coremark-official/src`.

```sh
make -C jyd-tests/rtthread-nano ARCH=riscv32-jyd \
  COREMARK_BENCH_DIR=/absolute/path/to/competition/coremark image
```

`COREMARK_PORT_DIR` independently identifies the retained local support files:
`core_portme.*`, `ee_printf.c`, `cvt.c`, and `coremark_softfloat.c`. Keep this
directory unchanged when selecting a different benchmark source directory.

For a short diagnostic run:

```sh
make -C jyd-tests/rtthread-nano ARCH=riscv32-jyd \
  CROSS_COMPILE=/path/to/md-gcc/bin/riscv64-unknown-linux-gnu- \
  COREMARK_ITERATIONS=1 COREMARK_DATA_SIZE=2000 run
```

The normal default remains `COREMARK_ITERATIONS=10000`.

For footprint accounting, first build the RT-Thread-only baseline:

```sh
make -C jyd-tests/rtthread-nano clean ARCH=riscv32-jyd COREMARK_ENABLE=0
make -C jyd-tests/rtthread-nano ARCH=riscv32-jyd COREMARK_ENABLE=0 image
```

Then build the default combined image separately. Report RT-Thread-only text,
the CoreMark/formatter/SoftFloat increment, and the combined text as distinct
values. Optimizing CoreMark must not be reported as reducing the RTOS baseline.

## Optimization and output boundaries

- Timed benchmark translation units use `COREMARK_OPT=-O3`.
- Port, formatter, conversion adapter, and SoftFloat use
  `COREMARK_SUPPORT_OPT=-Os`. The SoftFloat archive path includes this setting
  so incompatible cached objects are not reused.
- EEMBC's `%f`-capable formatter outputs through `rt_hw_console_output()`, the
  same RTT console backend used by `rt_kprintf`; klib printf is not linked. It
  executes after the timed region.
- RTT's embedded port writes `0xffffffff` to the JYD LED register immediately
  before taking the start timestamp, then writes `0x00000000` immediately after
  taking the stop timestamp. LED MMIO latency and result formatting are not
  included in the reported CoreMark time.
- `COREMARK_XEXTS`, `COREMARK_ZEXTS`, and their GCC MD flags default to the
  values used by `coremark-official`. They apply only to CoreMark benchmark
  objects; the RT-Thread kernel, FinSH, and AM port stay on `rv32im_zicsr`.
- The default CoreMark build requires the patched GCC described under
  `../coremark-official/accel/gcc-md/`.
- `RT_ALIGN_SIZE` is 16 because RV32 GCC requires 16-byte stack alignment.
  Four-byte alignment corrupted variadic `double` arguments while leaving
  integer output and CRCs apparently correct.
- The FinSH stack is 32 KiB. This is `.bss`, outside the text-size limit, and
  provides headroom for CoreMark and floating formatting.

When the competition workload arrives, first compare a one-iteration embedded
run against its standalone CRC and floating output, then build the unchanged
10,000-iteration default.
