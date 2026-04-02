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
- `make -C npc verilog-lint`: lint generated RTL with Verilator.
- `make -C am-kernels/tests/cpu-tests run ARCH=riscv32e-npc ALL=add`: build and run a CPU test on the selected platform.
- `make -C npc reformat` / `make -C npc checkformat`: apply or verify Scala formatting.
- `make -C nemu menuconfig` then `make -C nemu`: configure and build NEMU.
- `make -C abstract-machine ARCH=riscv32e-npc`: build an AM image for a target architecture.

## Coding Style & Naming Conventions
Follow existing local style instead of reformatting unrelated code. In `npc/`, Scala formatting is enforced by `scalafmt` (`npc/.scalafmt.conf`): 2-space indentation, 120-column limit, and import sorting. Scala/Chisel source files generally use `UpperCamelCase.scala` for modules (for example, `BranchPredictor.scala`) and lower-case filenames for some pipeline blocks already present (for example, `ifu.scala`); preserve the surrounding convention in each area.

For C/C++ in `nemu/`, `sdb/`, and AM libraries, match the current file’s brace and naming style and keep warnings clean under `make`.

## Testing Guidelines
Run the narrowest relevant check before opening a PR. For CPU behavior changes, use `make -C am-kernels/tests/cpu-tests run ARCH=<target> ALL=<case>`. Supported targets in this repo include `riscv32e-npc`, `riscv32e-ysyxsoc`, and `riscv32-jyd`; `ALL` should match a test name in that directory such as `add`. For `npc`, pair that with `make -C npc verilog` and `make -C npc verilog-lint`, then use `make -C npc sim IMG=<image>` when runtime confirmation is needed. The checked-in `npc` `test` target is not the maintained validation path. For emulator/runtime changes, rebuild the affected module and run the local workload you changed. CI validates `npc/**`, `patch/**`, and `.github/**`, so changes there should be kept green.

## Commit & Pull Request Guidelines
Recent history favors short, imperative commit subjects such as `fix using old CPU_DESIGN_NAME` and scoped prefixes like `ci:` or `jyd:`. Keep subjects under roughly 72 characters and make each commit a single logical change.

PRs should include a concise summary, affected modules, exact commands run, and any required artifacts or screenshots when behavior changes are visible in simulation output or generated RTL.
