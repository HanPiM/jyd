# Repository Guidelines

## Project Structure & Module Organization
This repository is a multi-project hardware/software workbench. The main active modules are:

- `npc/`: Chisel CPU design, generated Verilog, and simulator sources (`design/src`, `sim/`, `scripts/`).
- `nemu/`: reference emulator in C/C++ (`src/`, `include/`, `configs/`); its RV32 target supports the F extension through Berkeley SoftFloat.
- `abstract-machine/`: runtime, libraries, and platform build rules for bare-metal programs.
- `jyd-vivado-proj/`: Vivado digital twin project, constraints, IP configurations, and FPGA flow scripts.
- `sdb/`, `cachesim/`, `branchsim/`: shared support libraries used by NEMU/NPC. The shared sdb difftest path carries and compares RISC-V FPRs and `fcsr`, and its register dump and instruction ring buffer understand floating-point state.
- `patch/`: patch series applied in CI, especially for `ysyxSoC`.

Treat `build/`, `out/`, generated Verilog, and cache directories as disposable outputs.

## Build, Test, and Development Commands
- `make -C npc verilog`: emit merged Verilog into `npc/build/`.
- `make -C npc ARCH=riscv32e-npc`: build the Verilator sim binary.
- `make -C npc pack-fpga`: refresh `npc/build/pack-fpga/` and `npc/build/pack-fpga.zip` for the digital twin FPGA project.
- `make -C am-kernels/tests/cpu-tests run ARCH=riscv32-jyd ALL=add`: build and run a CPU test on the selected platform. Prefer `ARCH=riscv32-jyd` over `riscv32e-npc` when validating changes.
- `make -C npc reformat` / `make -C npc checkformat`: apply or verify Scala formatting.
- `make -C nemu menuconfig` then `make -C nemu`: configure and build NEMU.
- `make -C nemu/tools/gen-inst`: regenerate instruction semantics used by NEMU. F instruction patterns and semantics are generated here; do not hand-write generated instruction patterns in NEMU.
- `make -C abstract-machine ARCH=riscv32-jyd`: build an AM image for a target architecture.
- `./npc/scripts/run_digital_twin_vivado.sh [impl|write_bitstream|bitstream] [--jobs N]`: run `make -C npc pack-fpga`, replace `jyd-vivado-proj/digital_twin.srcs/sources_1/imports/pack-fpga`, then run the in-tree Vivado `digital_twin` project to implementation or bitstream. `JOBS`/`--jobs` controls Vivado `launch_runs -jobs` and `general.maxThreads`.

Vivado IP simulation models such as `mult_gen_0.sv`, `div_gen_uradix2.sv`, and `blk_mem_gen_2KB.sv` are emitted inline by their Chisel `BlackBox` definitions. Add new IP models to `CHISEL_UNSYNTH_KEYWORDS` in `npc/scripts/chisel.mk`: RTL simulation must compile the emitted model, while synthesis and `pack-fpga` must omit it and bind the module name to the Vivado IP output product instead. `blk_mem_gen_2KB` is a 512 x 32-bit simple dual-port memory: port A performs rising-edge synchronous byte writes using `wea[3:0]`, while port B has one-cycle synchronous read latency.

`dist_mem_gen_512x8` follows the same inline-model and packaging rules. It is a 512 x 8-bit simple dual-port distributed memory: `a` is the rising-edge synchronous write address and `dpra` is the asynchronous read address.

`dist_mem_gen_32x32` is a 32 x 32-bit simple dual-port distributed memory with rising-edge synchronous writes through address `a` and asynchronous zero-latency reads through address `dpra`; its inline simulation model follows the same packaging exclusion rule.

## Coding Style & Naming Conventions
Follow existing local style instead of reformatting unrelated code. In `npc/`, Scala formatting is enforced by `scalafmt` (`npc/.scalafmt.conf`): 2-space indentation, 120-column limit, and import sorting. Scala/Chisel source files generally use `UpperCamelCase.scala` for modules (for example, `BranchPredictor.scala`) and lower-case filenames for some pipeline blocks already present (for example, `ifu.scala`); preserve the surrounding convention in each area.

For C/C++ in `nemu/`, `sdb/`, and AM libraries, match the current file’s brace and naming style and keep warnings clean under `make`.

## Testing Guidelines
Run the narrowest relevant check before opening a PR. For CPU behavior changes, use `make -C am-kernels/tests/cpu-tests run ARCH=<target> ALL=<case>`. Supported targets in this repo include `riscv32-jyd`, `riscv32e-npc`, and `riscv32e-ysyxsoc`; prefer `ARCH=riscv32-jyd` unless you specifically need another target. `ALL` should match a test name in that directory such as `add`; if `ALL=<case>` is omitted, the command runs all cases by default.

After a change, start with `ALL=add` as the most basic smoke test, then decide whether broader coverage is needed. Common follow-up cases are `load-store` for memory access, `switch` and `if-else` for branch behavior, and `recursion` for function-call handling.

If you modify floating-point execution, FPR/`fcsr` state, floating-point CSRs, generated F instruction semantics, SoftFloat integration, or floating-point difftest/debug support, run the complete RV32 F architecture suite:
- `make -C ../riscv-arch-test-am-jyd ARCH=riscv32-nemu run TEST_ISA=F`

The suite currently contains 78 tests. Also rebuild the NEMU shared reference and run an NPC difftest smoke test when changing the floating-point state ABI shared by NEMU, NPC, and sdb.

For `npc`, pair CPU tests with `make -C npc verilog`, then use `make -C npc sim IMG=<image>` when runtime confirmation is needed. Do not run `make -C npc verilog-lint` in future work; it is not part of the maintained validation path. The checked-in `npc` `test` target is not the maintained validation path.

If you modify RISC-V M-extension/Zmmul multiply behavior in `npc` (for example ALU, EXU multi-cycle handshaking, forwarding, or decode paths for `mul`, `mulh`, `mulhu`, or `mulhsu`), run the directed architecture tests from the sibling test repo:
- `make -C ../riscv-arch-test-am-jyd ARCH=riscv32-jyd run TEST_ISA=M ALL=mul-01`
- `make -C ../riscv-arch-test-am-jyd ARCH=riscv32-jyd run TEST_ISA=M ALL=mulh-01`
- `make -C ../riscv-arch-test-am-jyd ARCH=riscv32-jyd run TEST_ISA=M ALL=mulhu-01`
- `make -C ../riscv-arch-test-am-jyd ARCH=riscv32-jyd run TEST_ISA=M ALL=mulhsu-01`

For multi-cycle EXU units, also keep an eye on RAW forwarding/stall behavior: the producer should advertise the destination register while its data is not yet valid so IDU stalls dependent consumers, then mark the data valid once the EXU result can be forwarded.

If you modify CSR-related code, you must also run the `rt-thread` (`rtt`) test because it is needed to cover CSR paths. For `rt-thread`, use `make -C rt-thread-am/bsp/abstract-machine run ARCH=<target>`; because it does not exit on its own, treat reaching the `msh />` prompt as success and stop the run manually. `Exception ETRACE` lines during that run are expected tracing output for `ecall`/`mret`, not failures.

If you modify `JYDDevices` or other JYD-specific code, you must run tests with `ARCH=riscv32-jyd` so the JYD-only paths are covered.

For digital twin FPGA packaging or XDC changes, use this validation flow:
- From the repository root, run `./npc/scripts/run_digital_twin_vivado.sh bitstream` when a bitstream is required. The script rebuilds `pack-fpga`, refreshes the imported files under `jyd-vivado-proj/`, and runs Vivado through synthesis, implementation, and bitstream generation. Use `impl` when bitstream generation is not needed; `JOBS` or `--jobs N` controls Vivado parallelism.
- Vivado produces substantial logs. For workflow logs, retain case-insensitive lines containing `finished` or `error`, but ignore lines beginning with `#` because they are echoed Tcl commands.
- After the run, use `python3 jyd-vivado-proj/scripts/extract-wns-violations.py -n 1` to inspect the worst setup/WNS path. Any `VIOLATED` result means timing is not met; `No WNS/setup timing violations found.` means setup timing is met. The routed report can also be inspected directly with `python3 jyd-vivado-proj/scripts/extract-timing-summary.py ./jyd-vivado-proj/digital_twin.runs/impl_1/top_timing_summary_routed.rpt`.
- If Vivado reports `Designutils 20-1307` for `jyd_cdc.xdc`, treat it as a hard constraint-parse failure and fix the XDC before doing further timing analysis.
- If Vivado reports `Constraints 18-513` for `jyd_cdc.xdc`, treat it as a hard constraint-targeting failure; the XDC parsed, but the selected `-from` objects are not valid startpoints.

Platform device differences matter here:
- `riscv32e-npc` expects a CLINT mapping at `AddrSpace.CLINT` (`0x02000000` range); AM/RT-Thread timer code reads `0x02000048/0x0200004c`, so removing CLINT from the `npc` SoC will hang `rt-thread`.
- `riscv32-jyd` intentionally does not implement CLINT. Its JYD-specific peripherals only decode low address bits for some devices, so CLINT behavior must not be inferred from the JYD platform.

## Temporary Data, Archives, and Isolated Worktrees

Use `/srv/data/jyd` as the persistent host-local storage root for JYD temporary
files, experiment records, caches, and isolated runs. Do not use `/tmp` for new
JYD data unless a system or CI environment explicitly requires it.

- `/srv/data/jyd/tmp/`: short-lived scratch files and disposable build copies.
- `/srv/data/jyd/archive/`: retained logs, reports, checkpoints, and experiment artifacts.
- `/srv/data/jyd/worktrees/`: Git worktrees used for isolated JYD work.
- `/srv/data/jyd/cache/`: ccache, coursier, and other reusable build caches.
- `/srv/data/jyd/cache/ccache/`: the active ccache store for Codex builds.
- `/srv/data/jyd/tmp/ccache/`: ccache's temporary-file directory.

Create and prepare new optimization worktrees with
`./npc/scripts/create-opt-worktree.sh` (from the main repo or any worktree)
instead of issuing the setup commands one by one. It creates the worktree
under `/srv/data/jyd/worktrees/`, symlinks the local ignored dependencies
(`am-kernels`, `npc/deps`, CoreMark build outputs, `rt-thread-am`), makes
`../riscv-arch-test-am-jyd` resolve from the worktree's parent, installs the
NEMU difftest reference (`.config` plus
`nemu/build/riscv32-nemu-interpreter-so`) from a proven source, and imports
the formal CoreMark COE pair into `cur_coe/` with SHA-256 verification.
Example: `./npc/scripts/create-opt-worktree.sh --commit ced5558
--name my-exp --branch opt-my-exp`. Run it with `--help` for all options.

Local scripts use `JYD_DATA_ROOT` with a default of `/srv/data/jyd`; set it in
CI or another host to relocate the whole layout. The project Codex config
sets `JYD_DATA_ROOT`, `CCACHE_DIR`, `CCACHE_TEMPDIR`, and `TMPDIR` for every
spawned build subprocess. `TMPDIR` is inherited by make, compilers, ccache,
Mill, Vivado, and their descendants, so do not set `TMPDIR=/tmp` for JYD
work. Use `/srv/data/jyd/tmp` instead; only system or CI environments that
explicitly require `/tmp` may override it. Existing script-specific variables
such as `BENCH_ROOT` remain explicit overrides when needed. Historical `/tmp`
paths in old optimization notes are records of past runs and should not be
rewritten.

For emulator/runtime changes, rebuild the affected module and run the local workload you changed. NEMU fetches a fixed Berkeley SoftFloat revision through `nemu/tools/softfloat`; keep that revision pinned and build it with the RISC-V specialization. Changes under `nemu/tools/gen-inst/repo` belong to the separate gen-inst repository and must be committed and pushed there. CI validates `npc/**`, `patch/**`, and `.github/**`, so changes there should be kept green.

## Optimization Experiment Documentation

Optimization experiment documentation has a single canonical home: branch `opt-notes`, checked out at
`/home/hanpi/gitclone/jyd-opt-notes`. Before starting or continuing an optimization experiment, read
`npc/opt-iter-flow.md` and the relevant files under `npc/opt-try/` from that worktree. Copies of those files on RTL
optimization branches are historical snapshots and must not be edited.

Current active optimization goal (2026-08-04): continue timing/performance iteration until the 280 MHz CoreMark run
time is below 12.05 s and the post-route setup WNS is no worse than -0.05 ns (`WNS >= -0.05 ns`). Read
`npc/opt-try/280mhz-12.05s-wns-0.05.md` (canonical at
`/home/hanpi/gitclone/jyd-opt-notes/npc/opt-try/280mhz-12.05s-wns-0.05.md`) before continuing any optimization turn,
and keep both copies in sync. Do not substitute older cycle/time gates such as the previous 1.70 s / 476M-cycle
targets. Every candidate promoted to a new baseline must be board-tested and recorded; every Vivado impl attempt must
also be recorded (status, commands, results, archiving, and keep/revert decision). This iteration does not limit
board-run count. It is acceptable to generate a bitstream after impl for a real board measurement. Unattended
functional and performance measurements must use the CLI `capture` command to record raw UART output; use the
interactive CLI `serial` command only when bidirectional interaction is required, such as an RT-Thread shell. LED/SEG
packet completion checks no longer apply to the new program. A generic fast implementation path may calibrate
Quick at 75 MHz against Default at 200 MHz, falling back to Default at 150 MHz when 200 MHz is not timing-closed and
board-valid. Record the actual frequency, strategy, input identity, implementation time, runtime, and bitstream identity.

Keep RTL, Vivado project, IP, constraint, and script changes on the relevant code branch. Commit the frozen candidate
there first, then record the experiment in the `opt-notes` worktree with the code branch name and full commit SHA. Do
not cherry-pick ordinary experiment-record commits back to code branches, and do not cherry-pick RTL commits into
`opt-notes`.

Changes that all active optimization branches must share, such as this `AGENTS.md` contract, must be isolated in their
own commit and cherry-picked to `opt-loop`, `opt-280mhz-arch-baseline`, `opt-280mhz-next-arch`, and
`opt-270mhz-1.704s`. The `opt-archive-*` branches are frozen and must not be updated. If documentation is accidentally
committed on a code branch, transfer only the documentation diff to `opt-notes`, then restore the code branch's
documentation in a follow-up commit. Never cherry-pick a mixed RTL/documentation commit into `opt-notes`.

## Commit & Pull Request Guidelines
Recent history favors short, imperative commit subjects such as `fix using old CPU_DESIGN_NAME` and scoped prefixes like `ci:` or `jyd:`. Keep subjects under roughly 72 characters and make each commit a single logical change.

PRs should include a concise summary, affected modules, exact commands run, and any required artifacts or screenshots when behavior changes are visible in simulation output or generated RTL.
