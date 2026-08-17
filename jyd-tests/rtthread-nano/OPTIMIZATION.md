# RT-Thread Nano footprint work log

This is the canonical record for RT-Thread Nano footprint work in `jyd-tests`.
Read and update it whenever changing the Nano configuration, AM port, FinSH/msh
integration, compiler optimization, or linked runtime. Record exact `.text`
measurements and validation commands so later sessions can continue from the
current baseline.

The upstream kernel is RT-Thread Nano 3.1.5, pinned to the official `v3.1.5`
tag commit recorded in `SOURCE_COMMIT`.

Only the ELF `.text` section is subject to the competition footprint limit.
`.rodata`, `.data`, `.data.extra`, and `.bss` are not counted, although their
addresses and generated images must still fit the JYD memory map.

## Functional requirements

- Target: `ARCH=riscv32-jyd`.
- Start RT-Thread Nano and reach an interactive `msh >` prompt.
- Provide working `ps`, `version`, `list_thread`, and `list_semaphore` commands.
- Embed a selectable 10,000-iteration CoreMark source directory with floating-point
  elapsed-time and throughput output.
- RTT shell output uses Nano's `rt_kprintf`; CoreMark's EEMBC formatter shares
  `rt_hw_console_output`. Neither path uses klib printf.
- Terminal input uses `finsh_getchar()` -> `rt_hw_console_getchar()` -> AM
  `try_getch()` without RT-Thread device or POSIX stdio layers.
- JYD intentionally has no CLINT, so the current port uses cooperative context
  switching and does not depend on periodic timer interrupts.

## Completed changes

### Initial AM port and scheduler validation

- Pinned upstream `RT-Thread/rtthread-nano` revision in `SOURCE_COMMIT`.
- Added AM stack creation, yield-based context switching, console output, and
  console input.
- Initially validated scheduling and IPC with two threads and semaphores.
- Renode debugging showed that the first apparent context-restore failure was a
  test-order assumption: Nano selected the consumer before the producer.

### Interactive msh

- Added the minimal `msh.c` and `shell.c` sources.
- Added a static FinSH shell thread and `FSymTab` linker section.
- Added the `nano` command.
- Did not enable RT-Thread device, filesystem, POSIX, history, descriptions, or
  the full built-in command collection. Added compact required object-list
  commands backed by the Nano object containers.

### Removed klib formatted output

- Replaced AM RISC-V NPC CTE assertions with `panic()`/`panic_on()`; these use
  `putstr()` and `halt()` rather than printf.
- Confirmed that `printf`, `sprintf`, `meta_printf`, and `parse_format` are no
  longer present in the Nano ELF.
- All shell and kernel formatted output remains on Nano's `rt_vsnprintf` path.

### Fixed the DRAM data image

- Forced `.data.extra`/`FSymTab` into DRAM.
- Without this placement, `.data.extra` landed after `.text` at `0x8000388c`;
  `objcopy` filled the gap to `.rodata` at `0x80100000`, producing a false 1 MB
  `data.bin`.
- The corrected data image was 2,980 bytes before the 8-priority/debug trim.

### Configuration trim

- Disabled `RT_DEBUG`.
- Disabled `RT_USING_OVERFLOW_CHECK`.
- Tested reducing `RT_THREAD_PRIORITY_MAX` from 32 to 8. This was reverted:
  with the normal `-O3` build, the compiler fully unrolls the eight-iteration
  scheduler table initialization loop. The 8-priority build adds 96 bytes of
  `.text` while saving only 192 bytes of `.bss`, and only `.text` is limited.
- Retained 32 priorities and FinSH priority 20.
- Because disabling `RT_DEBUG` makes `RT_ASSERT(expr)` compile out without
  evaluating `expr`, initialization calls must never be placed only inside an
  assertion. `finsh_system_init()` is called explicitly and its result is
  checked with a normal `if`.

### Tiny Nano formatter

- Added the opt-in upstream configuration `RT_USING_TINY_PRINTF` in local
  upstream commits `0eb04262c39b40d1fac53dcaf57473670ab7309a` and
  `d7f96f676053860085e35a76504a3c873e42d9d3`.
- The tiny Nano `rt_kprintf` supports the linked format subset: literal text,
  `%s`, `%c`, `%d`, `%i`, `%u`, `%x`, `%X`, and `%%`. It writes through
  `rt_hw_console_output()` and does not use klib printf.
- For the non-SMP build, the idle thread uses the constant name `tidle0`,
  removing the only live `%d` call. The full `rt_vsnprintf`, `print_number`, and
  `rt_sprintf` sections are consequently removed by `--gc-sections`.
- Before adding the required status commands and integer conversions, the
  string-only formatter changed `.text` from 13,068 to 9,540 bytes at `-O3`,
  saving 3,528 bytes.

The supported format subset is defined by the local `src/tinyprintf.c` and
must be re-audited whenever adding commands or enabling another RT-Thread
feature. Plain `%s/%c/%d/%i/%u/%x/%X` plus `-`/width for `%s` are supported;
other conversions are printed literally and their arguments are not consumed.

### Nano-only `-Os`

- `NANO_OPT` is applied only to the application object list: the Nano kernel,
  FinSH, and local port. AM and klib retain their normal build flags.
- The selected normal-build default is `NANO_OPT=-Os`. `gdb-server` defaults to
  `-O0` so the Nano-specific setting does not override its debugging build.
- On top of the tiny formatter, `-Os` changed `.text` from 9,540 to 7,724 bytes,
  saving another 1,816 bytes. The `.rodata`, `.data.extra`, `.data`, and `.bss`
  sizes remained unchanged.
- Before adding the required status commands, the resulting image loaded a
  1,508-byte DRAM data image and reached the prompt successfully.

### Required status commands

- Removed the development-only `nano` command.
- Added `ps` and `list_thread`, both of which enumerate the real
  `RT_Object_Class_Thread` container and report name, priority, state, stack
  size, remaining tick, and error.
- Added `list_semaphore`, which enumerates the real
  `RT_Object_Class_Semaphore` container and reports value and suspended-thread
  count. The command name deliberately follows the competition requirement
  rather than upstream's shorter `list_sem` name.
- Added `version`, which calls Nano's existing `rt_show_version()`.
- Extending tiny printf with the required integer conversions and adding all
  four commands changes the selected Nano-only `-Os` image from 7,724 to 8,492
  bytes. The DRAM data image is 1,904 bytes.
- Runtime validation exercised all four commands and `help`. The observed
  objects were the `tidle0` and `tshell` threads and the `shrx` semaphore.

### Full interactive line editor

- The default image uses RT-Thread's full FinSH line editor: `FINSH_USING_HISTORY`
  (5 lines) is enabled and `FINSH_USING_SIMPLE_LINE_EDITOR` is not defined.
- This provides Tab command completion (`msh_auto_complete`), arrow-key cursor
  movement, middle-of-line insertion/deletion, command history, echo, the
  `msh >` prompt, and FSymTab command dispatch.
- On the current toolchain, the full editor + history costs 1,720 bytes of
  RT-Thread-only `.text` versus the minimal editor (9,528 vs 7,808 bytes).
  Defining `FINSH_USING_SIMPLE_LINE_EDITOR` still recovers that space but
  drops completion, arrow-key editing, and history.
- Runtime validation exercised Tab completion (`ver` -> `version`), left-arrow
  + backspace editing (`pss` -> `ps`), up-arrow history recall, and the
  `version`, `ps`, `list_thread`, `list_semaphore`, and `help` commands.

### Command descriptions in help

- `help` is upstream Nano's `msh_help()` in `components/finsh/msh.c`. With both
  `FINSH_USING_SYMTAB` and `FINSH_USING_DESCRIPTION` defined it prints the
  aligned `%-16s - %s` list; without `FINSH_USING_DESCRIPTION` it falls back to
  space-separated command names only.
- `FINSH_USING_DESCRIPTION` is now enabled by default in `include/rtconfig.h`
  so `help` matches the full RT-Thread format. Remove the define to restore the
  compact list.
- The config change alone leaves `.text` unchanged: `COREMARK_ENABLE=0` adds
  159 B `.rodata` (description strings) and 20 B `.data.extra` (desc pointers
  in the FSymTab); the combined image adds 195 B `.rodata` and 24 B
  `.data.extra`.

### String width/left-justify in tiny printf

- Upstream Nano's `rt_kprintf` under `RT_USING_TINY_PRINTF` only handled plain
  conversions; `%-16s` was emitted literally, so `help` printed
  `%-16s - <name>` and dropped the description.
- The source list now selects `src/tinyprintf.c` and omits the upstream
  formatter. The local file parses the `-`
  flag and decimal width and pads `%s` accordingly; numeric widths are parsed
  but not applied, keeping the formatter small.
- Clean-build cost: +216 B `.text` in both the RT-only (9,528 -> 9,744) and
  combined (20,476 -> 20,692) images; `.rodata`/`.data.extra` unchanged.
- Runtime validation under NPC: `help` lists each command with the aligned
  `%-16s - %s` format, and `version`/`ps` output is unchanged.

### Embedded CoreMark

- Added a synchronous `coremark` msh command with defaults of 10,000 iterations
  and 2,000 bytes of static benchmark data.
- The embedded benchmark uses the same GCC backend options and AM SoftFloat
  reporting path as the standalone image.
- Separated selectable `COREMARK_BENCH_DIR` algorithm sources from the retained
  `COREMARK_PORT_DIR` RTT/AM adaptation. See `COREMARK.md` for the source
  selection procedure.
- The five benchmark objects use `COREMARK_OPT=-Os` by default. The four hot
  algorithm objects other than `core_main.o` include the checked-in `-O3`
  override. Port, EEMBC formatter, float conversion, and SoftFloat adapter
  objects use `-Os`; Berkeley SoftFloat also uses `-Os` in a separately keyed
  archive. When CRC acceleration is selected, only the five benchmark objects
  use semantic LTO, and the mixed image is linked through the patched GCC driver.
- CoreMark output uses EEMBC's formatter for `%f` but sends characters through
  `rt_hw_console_output()`. This retains required floating output without
  linking klib printf.
- Changed `RT_ALIGN_SIZE` from 4 to 16. RV32 requires 16-byte stack alignment;
  with four-byte alignment, CoreMark CRC and integer output passed but variadic
  floating arguments were corrupted. A one-iteration run now prints
  `0.008000`, `125.000000`, and the expected performance-run CRCs.
- Increased the FinSH stack to 32 KiB for the synchronous benchmark. This only
  affects `.bss`, which is outside the text-size limit.

## Measurements

Always record the current RT-Thread-only baseline first. It is reproducible by
disabling the optional workload:

```sh
make -C jyd-tests/rtthread-nano clean ARCH=riscv32-jyd COREMARK_ENABLE=0
make -C jyd-tests/rtthread-nano ARCH=riscv32-jyd COREMARK_ENABLE=0 image
riscv64-linux-gnu-size -A -d \
  jyd-tests/rtthread-nano/build/rtthread-nano-riscv32-jyd.elf
```

### RT-Thread-only `.text` (primary metric)

| RT-Thread configuration, no CoreMark | `.text` bytes | Notes |
|---|---:|---|
| Initial scheduler smoke test | 16,968 | Nano and klib formatters both linked |
| klib printf removed, before msh | 13,780 | Nano formatter retained |
| Minimal interactive msh, 32 priorities, debug checks | 14,476 | Reaches `msh >` |
| 32 priorities, no RT debug/overflow check | 13,068 | Pre-formatter baseline; reaches `msh >` |
| 8 priorities, no RT debug/overflow check | 13,164 | 96 B larger text; rejected |
| Tiny Nano formatter, `-O3` | 9,540 | Saves 3,528 B; `nano` and `help` pass |
| Tiny Nano formatter, Nano-only `-Os` | 7,724 | Pre-command baseline; saves another 1,816 B |
| Required status commands and integer formats, `-Os` | 8,492 | Historical measurement before workload integration |
| Required commands, before minimal line editor | 8,484 | Previous selected RTT-only baseline |
| Descriptions enabled, upstream tiny printf | 9,528 | Full editor + 5-line history + `FINSH_USING_DESCRIPTION` |
| Current source, `COREMARK_ENABLE=0` | 9,744 | +216 B for local `%-Ns` tiny printf; full editor + history + descriptions |

The current RT-Thread-only value is the first number to report when discussing
Nano footprint. Enabling `FINSH_USING_DESCRIPTION` (the current default) does
not change `.text` by itself (see "Command descriptions in help" for the
`.rodata`/`.data.extra` cost); the +216 B is entirely the local tiny printf
extension. Historical rows above compare RTOS configuration work only.

### Combined image with CoreMark (secondary metric)

Build the default workload separately:

```sh
make -C jyd-tests/rtthread-nano clean ARCH=riscv32-jyd
make -C jyd-tests/rtthread-nano ARCH=riscv32-jyd image
```

| Image component/accounting | `.text` bytes | Notes |
|---|---:|---|
| RT-Thread-only baseline | 7,480 | Historical earlier toolchain/configuration |
| CoreMark + EEMBC formatter + SoftFloat increment | 22,016 | Historical earlier toolchain/configuration |
| Combined 10,000-iteration image | 29,496 | Historical earlier toolchain/configuration |
| Current combined image (clean) | 20,692 | Current baseline = 20,476 + 216 B local `%-Ns` tiny printf |

CoreMark size optimizations change the increment and combined total, not the
RT-Thread baseline. Never present a reduction of the combined image as an RTOS
footprint improvement unless the `COREMARK_ENABLE=0` measurement also changes.

## Debug and priority experiment

The effects were measured independently with four clean `-O3` builds. The
overflow check was toggled together with `RT_DEBUG`, matching the intended
configuration change.

| Priorities | `RT_DEBUG` + overflow check | `.text` bytes |
|---:|:---:|---:|
| 32 | on | 14,444 |
| 8 | on | 14,540 |
| 32 | off | 13,068 |
| 8 | off | 13,164 |

Therefore:

- Disabling `RT_DEBUG` and `RT_USING_OVERFLOW_CHECK` saves exactly 1,376 bytes
  for either priority count.
- Reducing 32 priorities to 8 adds exactly 96 bytes of `.text` for either debug
  setting.
- The 8-priority configuration reduces `rt_thread_priority_table` from 256 to
  64 bytes, saving 192 bytes of `.bss`, which is not counted by the competition
  limit.
- At `-O3`, `rt_system_scheduler_init()` grows from 52 to 148 bytes because GCC
  fully unrolls the eight-iteration priority-table initialization loop. No other
  text symbol accounts for the 96-byte increase.

## Required validation

After every footprint change:

```sh
make -C jyd-tests/rtthread-nano clean ARCH=riscv32-jyd
make -C jyd-tests/rtthread-nano ARCH=riscv32-jyd image
timeout 10 make -C jyd-tests/rtthread-nano ARCH=riscv32-jyd run
```

Success means the simulator loads a small DRAM data image without a capacity
error and reaches `msh >`. The shell does not exit by itself, so stop it after
observing the prompt.

## Pending size work

The current RTOS-only baseline is 7,480 bytes. The symbol-level review
below records
the completed high-return work and ranks optional further reductions.

1. **Completed: specialize Nano's output path.** `rt_vsnprintf` was 2,068 bytes and its
   `print_number` helper is 1,704 bytes, for 3,772 bytes before counting the
   small `rt_kprintf` and `rt_sprintf` wrappers. The live shell output formats
   use only literal text, `%s`, and `%c`. The only live `%d` is
   `rt_thread_idle_init()` constructing `"tidle%d"`; on this single-core port it
   can use the constant name `"tidle0"`. A Nano-local formatter supporting the
   verified live subset is therefore the best candidate and is large enough by
   itself to reach the target. It must remain an RT-Thread/Nano output function
   using `rt_hw_console_output`, not klib printf. Recheck all linked format
   strings after every configuration change before narrowing the supported
   subset.
2. **Reverted: full interactive shell editor restored as the default.** The
   minimal append/backspace/Enter loop saves 1,720 bytes on the current
   toolchain, but the default image now uses the full editor so Tab
   completion, arrow-key editing, and command history are available. The
   minimal editor remains available by defining `FINSH_USING_SIMPLE_LINE_EDITOR`.
3. **Remove the unused shell semaphore initialization.** With
   `RT_USING_DEVICE` disabled, `finsh_getchar()` polls the AM console directly,
   so `shell->rx_sem` is never taken or released. Upstream nevertheless calls
   `rt_sem_init()` unconditionally, leaving 84 bytes of live text plus related
   object-initialization paths. Make the init conditional before disabling
   `RT_USING_SEMAPHORE`. This is a small secondary saving, not a primary route.
4. **Completed: measure Nano-only `-Os`.** It saves 1,816 bytes relative to the
   tiny-formatter `-O3` build and is now the normal-build default.
5. **Only then inspect scheduler/timer lifecycle.** The largest live kernel
   symbols are `rt_schedule` (556 bytes), `rt_object_init` (276 bytes),
   `rt_thread_init` (248 bytes), and scheduler list insertion/removal (188/136
   bytes). Most are required by the idle and FinSH threads. Timer initialization
   and detach/stop paths are also tied to normal thread teardown. Changes here
   have higher semantic risk and a lower expected return than the formatter and
   shell work.
6. **Measure formatter consolidation with CoreMark.** The combined image keeps
   Nano's 540-byte `rt_kprintf` and EEMBC's 1,068-byte `ee_printf` because only
   the latter currently supports CoreMark's widths, long integers, and `%f`.
   They already share `rt_hw_console_output` and klib printf is absent. A future
   consolidation must preserve the required report byte-for-byte and should be
   accepted only on measured total-text savings; merely routing RTT through the
   larger formatter is expected to save only the small Nano formatter.

Do not spend further `.text` effort on reducing the priority count: its measured
effect is negative at `-O3`. Stack sizes, command-buffer sizes, priority-table
storage, and most structure reductions primarily affect `.bss`, which is outside
the competition limit.
