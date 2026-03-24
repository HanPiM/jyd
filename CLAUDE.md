# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This repo is a fork of the "一生一芯" (YSYx) project, refactored for the **JYD competition**. The ysyx-specific academic tracking (tracer branch, `STUID`/`STUNAME`) is being removed. Do not add or restore any ysyx tracing/tracking code.

**Core subprojects:**
- **npc/**: RISC-V CPU hardware design in Chisel/Scala, simulated via Verilator
- **nemu/**: NJU Emulator — multi-ISA (x86, MIPS32, RISC-V 32/64) interpreter in C
- **sdb/**: System debugger library (C++) with diff-testing against QEMU
- **abstract-machine/**: Hardware abstraction layer (AM) for ISA portability
- **navy-apps/**: Application framework running on top of AM
- **cachesim/** / **branchsim/**: Performance analysis simulators

## Initialization

```bash
bash init.sh nemu              # Initialize NEMU
bash init.sh npc               # Initialize NPC (sets env vars in ~/.bashrc)
bash init.sh abstract-machine
bash init.sh navy-apps
```

Required environment variables: `JYD_HOME`, `JYD_NEMU_HOME`, `JYD_AM_HOME`, `JYD_NPC_HOME`, `NAVY_HOME`, `NVBOARD_HOME`.

> **Note:** The original ysyx names (`NEMU_HOME`, `AM_HOME`, `NPC_HOME`) are being renamed to `JYD_*` throughout the codebase to avoid conflicts with an ysyx installation on the same machine. When touching Makefiles or scripts, use the `JYD_*` names.

## Build Commands

### NPC (Chisel/Verilator RTL simulation)
```bash
cd npc
make sim                          # Build + run RTL simulator (default ARCH=riscv32e-npc)
make sim IMG=path/to/image.bin    # Simulate with a binary image
make test                         # Run Chisel/ScalaTest unit tests via Mill
make verilog                      # Emit Verilog from Chisel
make verilog-lint                 # Lint emitted Verilog with Verilator
make checkformat                  # Check Scala formatting (scalafmt)
make reformat                     # Auto-format Scala source files
make clean
```

### NEMU (C emulator)
```bash
cd nemu
make menuconfig    # Configure ISA/features before first build
make               # Build emulator
make run           # Build and run
```

### SDB (debugger library)
```bash
cd sdb
make               # Build libsdb.a
make clean
```

### Abstract Machine
```bash
cd abstract-machine
make image          # Default build (requires AM_HOME set)
```

### Performance/analysis tools
```bash
cd cachesim && make run
cd branchsim && make run
```

## Architecture

### NPC pipeline (`npc/design/src/`)

Five-stage RISC-V pipeline written in Chisel with Decoupled handshaking:

| File | Stage/Role |
|------|-----------|
| `Top.scala` | CPU top module; wires IFU→IDU→EXU→LSU→WBU, bus interfaces |
| `ifu.scala` | Instruction Fetch — PC logic |
| `idu.scala` | Instruction Decode — control signal generation |
| `exu.scala` | Execute — ALU, CSR access, branch resolution |
| `lsu.scala` | Load/Store — memory access |
| `wbu.scala` | Write-Back — commits to register file |
| `RegFile.scala` | GPR (32 regs) + CSR registers |
| `icache.scala` | Instruction cache |
| `BranchPredictor.scala` / `BranchTargetBuffer.scala` | Branch prediction |
| `alu.scala` | Arithmetic/logic unit |
| `axi4.scala` | **TODO: replace AXI4 with JYD simple bus** — JYD competition provides a simpler storage peripheral and bus; AXI4 interfaces in `Top.scala`, `ifu.scala`, `lsu.scala`, `icache.scala`, and `axi4.scala` are all candidates for replacement once the JYD bus spec is available |

Build toolchain: Mill → Chisel → Verilog → Verilator → C++ simulation binary.
Mill config: `npc/build.mill`. Scala formatting: `npc/.scalafmt.conf` (max 120 cols, Scala 3 dialect).

### NEMU emulator (`nemu/src/`)

Interpreter-based CPU emulator:
- `cpu/cpu-exec.c` — main execution loop
- `isa/{riscv32,x86,mips32,loongarch32r}/` — ISA-specific decode/execute
- `memory/` — physical/virtual memory, paging, TLB
- `device/` — UART, Timer, VGA, Audio MMIO emulation
- `monitor/sdb/` — interactive debugger, expression evaluator
- Configured via Kconfig (`make menuconfig`)

### SDB debugger (`sdb/src/`)

Standalone C++ library linked into NPC simulation:
- `sdb.cpp` — CLI command loop, breakpoints, watchpoints
- `difftest.cpp` — compares NPC state against NEMU reference at each step
- `ftrace.cpp` / `etrace.cpp` — function and exception tracing
- `expr.cpp` — expression parsing and evaluation
- `disasm_trace.cpp` — Capstone-based disassembly
- `iringbuf.cpp` — instruction history ring buffer
- `elf_tool.cpp` — ELF symbol parsing

### Layered system diagram

```
Application (Navy-Apps)
        │
Abstract Machine (AM)  ←  hardware-independent API
        │
  ┌─────┴──────┐
 NEMU        NPC (RTL)
  └─────┬──────┘
        │
   SDB Debugger (diff-testing, tracing)
```

## CI/CD

- `.github/workflows/autotest.yml` — runs on push to `main` and PRs
- `.github/monitor.py` — monitors simulation output for `HIT GOOD TRAP` / `HIT BAD TRAP` / benchmark PASS markers
- Tests AM kernel execution and RT-Thread benchmarks

## Off-limits directories

- **`patch/`** — ysyx-specific git-am patches for CI testing of student submissions. Not used in JYD (rt-thread-am and other submodules live outside this repo). Do not modify files here.

## Pending Refactors

- **Env var rename** (in progress): `NEMU_HOME` → `JYD_NEMU_HOME`, `AM_HOME` → `JYD_AM_HOME`, `NPC_HOME` → `JYD_NPC_HOME` across all Makefiles and scripts.
- **Remove ysyx tracing**: `STUID`, `STUNAME`, `tracer-ysyx` branch logic in root `Makefile` and `init.sh` should be deleted.
- **Bus replacement** (waiting on spec): AXI4 (`axi4.scala` and all consumers) to be replaced with JYD simple bus once the competition spec is provided.
