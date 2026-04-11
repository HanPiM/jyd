# Repository Guidelines

## Project Structure & Module Organization
This repository is a multi-project hardware/software workbench. The main active modules are:

- `npc/`: Chisel CPU design, generated Verilog, and simulator sources (`design/src`, `sim/`, `scripts/`).
- `nemu/`: reference emulator in C/C++ (`src/`, `include/`, `configs/`).
- `abstract-machine/`: runtime, libraries, and platform build rules for bare-metal programs.
- `sdb/`, `cachesim/`, `branchsim/`: shared support libraries used by NEMU/NPC.
- `patch/`: patch series applied in CI, especially for `ysyxSoC`.

Treat `build/`, `out/`, generated Verilog, and cache directories as disposable outputs.

## Build, Test, and Development Commands
- `make -C npc verilog`: emit merged Verilog into `npc/build/`.
- `make -C npc ARCH=riscv32e-npc`: build the Verilator sim binary.
- `make -C npc verilog-lint`: lint generated RTL with Verilator. `-PINCONNECTEMPTY` warnings may appear and can be ignored.
- `make -C am-kernels/tests/cpu-tests run ARCH=riscv32-jyd ALL=add`: build and run a CPU test on the selected platform. Prefer `ARCH=riscv32-jyd` over `riscv32e-npc` when validating changes.
- `make -C npc reformat` / `make -C npc checkformat`: apply or verify Scala formatting.
- `make -C nemu menuconfig` then `make -C nemu`: configure and build NEMU.
- `make -C abstract-machine ARCH=riscv32-jyd`: build an AM image for a target architecture.

## Coding Style & Naming Conventions
Follow existing local style instead of reformatting unrelated code. In `npc/`, Scala formatting is enforced by `scalafmt` (`npc/.scalafmt.conf`): 2-space indentation, 120-column limit, and import sorting. Scala/Chisel source files generally use `UpperCamelCase.scala` for modules (for example, `BranchPredictor.scala`) and lower-case filenames for some pipeline blocks already present (for example, `ifu.scala`); preserve the surrounding convention in each area.

For C/C++ in `nemu/`, `sdb/`, and AM libraries, match the current file’s brace and naming style and keep warnings clean under `make`.

## Testing Guidelines
Run the narrowest relevant check before opening a PR. For CPU behavior changes, use `make -C am-kernels/tests/cpu-tests run ARCH=<target> ALL=<case>`. Supported targets in this repo include `riscv32-jyd`, `riscv32e-npc`, and `riscv32e-ysyxsoc`; prefer `ARCH=riscv32-jyd` unless you specifically need another target. `ALL` should match a test name in that directory such as `add`; if `ALL=<case>` is omitted, the command runs all cases by default.

After a change, start with `ALL=add` as the most basic smoke test, then decide whether broader coverage is needed. Common follow-up cases are `load-store` for memory access, `switch` and `if-else` for branch behavior, and `recursion` for function-call handling.

For `npc`, pair CPU tests with `make -C npc verilog` and `make -C npc verilog-lint`, then use `make -C npc sim IMG=<image>` when runtime confirmation is needed. `make -C npc verilog-lint` may report `-PINCONNECTEMPTY` warnings; these can be ignored. The checked-in `npc` `test` target is not the maintained validation path.

If you modify CSR-related code, you must also run the `rt-thread` (`rtt`) test because it is needed to cover CSR paths. For `rt-thread`, use `make -C rt-thread-am/bsp/abstract-machine run ARCH=<target>`; because it does not exit on its own, treat reaching the `msh />` prompt as success and stop the run manually. `Exception ETRACE` lines during that run are expected tracing output for `ecall`/`mret`, not failures.

If you modify `JYDDevices` or other JYD-specific code, you must run tests with `ARCH=riscv32-jyd` so the JYD-only paths are covered.

Platform device differences matter here:
- `riscv32e-npc` expects a CLINT mapping at `AddrSpace.CLINT` (`0x02000000` range); AM/RT-Thread timer code reads `0x02000048/0x0200004c`, so removing CLINT from the `npc` SoC will hang `rt-thread`.
- `riscv32-jyd` intentionally does not implement CLINT. Its JYD-specific peripherals only decode low address bits for some devices, so CLINT behavior must not be inferred from the JYD platform.

For emulator/runtime changes, rebuild the affected module and run the local workload you changed. CI validates `npc/**`, `patch/**`, and `.github/**`, so changes there should be kept green.

## Commit & Pull Request Guidelines
Recent history favors short, imperative commit subjects such as `fix using old CPU_DESIGN_NAME` and scoped prefixes like `ci:` or `jyd:`. Keep subjects under roughly 72 characters and make each commit a single logical change.

PRs should include a concise summary, affected modules, exact commands run, and any required artifacts or screenshots when behavior changes are visible in simulation output or generated RTL.
