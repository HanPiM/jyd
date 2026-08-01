# NEMU

NEMU(NJU Emulator) is a simple but complete full-system emulator designed for teaching purpose.
Currently it supports x86, mips32, riscv32 and riscv64.
To build programs run above NEMU, refer to the [AM project](https://github.com/NJU-ProjectN/abstract-machine).

The main features of NEMU include
* a small monitor with a simple debugger
  * single step
  * register/memory examination
  * expression evaluation without the support of symbols
  * watch point
  * differential testing with reference designs, including RISC-V floating-point registers and `fcsr`
  * snapshot
* CPU core with support of most common used instructions
  * x86
    * real mode is not supported
    * x87 floating point instructions are not supported
  * mips32
    * CP1 floating point instructions are not supported
  * riscv32
    * RV32IMF; F arithmetic uses Berkeley SoftFloat
    * B bit-manipulation support is generated through `tools/gen-inst` and
      currently covers `Zba`, `Zbb`, `Zbc`, `Zbs`, `Zbkb`, `Zbkc`, and `Zbkx`.
      The supported RV32 instructions are:
      * `Zba`: `sh1add`, `sh2add`, `sh3add`
      * `Zbb`: `andn`, `clz`, `cpop`, `ctz`, `max`, `maxu`, `min`, `minu`,
        `orc.b`, `orn`, `rev8`, `rol`, `ror`, `rori`, `sext.b`, `sext.h`,
        `xnor`, `zext.h`
      * `Zbc`: `clmul`, `clmulh`, `clmulr`
      * `Zbs`: `bclr`, `bclri`, `bext`, `bexti`, `binv`, `binvi`, `bset`,
        `bseti`
      * `Zbkb`: `andn`, `brev8`, `orn`, `pack`, `packh`, `rev8`, `rol`, `ror`,
        `rori`, `unzip`, `xnor`, `zip`
      * `Zbkc`: `clmul`, `clmulh`
      * `Zbkx`: `xperm4`, `xperm8`
    * These 48 instructions have passed the RV32 NEMU compatibility tests in
      `../riscv-arch-test-am-jyd`. This describes the NEMU reference model;
      it does not imply that the NPC RTL implements every listed extension.
  * riscv64
    * only RV64IM
* memory
* paging
  * TLB is optional (but necessary for mips32)
  * protection is not supported
* interrupt and exception
  * protection is not supported
* 5 devices
  * serial, timer, keyboard, VGA, audio
  * most of them are simplified and unprogrammable
* 2 types of I/O
  * port-mapped I/O and memory-mapped I/O
