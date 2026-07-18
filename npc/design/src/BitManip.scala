package cpu.alu

import chisel3._
import chisel3.util._

import common_def._

object BExtensionOp extends ChiselEnum {
  val clz, ctz, cpop, clmul, orcB, xperm4, ror = Value
}

object BExtensionDecode {
  private def matches(inst: UInt, value: Long, mask: Long): Bool =
    (inst & mask.U(32.W)) === value.U(32.W)

  def apply(inst: UInt): (Bool, BExtensionOp.Type) = {
    val isClz    = matches(inst, 0x60001013L, 0xfff0707fL)
    val isCtz    = matches(inst, 0x60101013L, 0xfff0707fL)
    val isCpop   = matches(inst, 0x60201013L, 0xfff0707fL)
    val isClmul  = matches(inst, 0x0a001033L, 0xfe00707fL)
    val isOrcB   = matches(inst, 0x28705013L, 0xfff0707fL)
    val isXperm4 = matches(inst, 0x28002033L, 0xfe00707fL)
    val isRor    = matches(inst, 0x60005033L, 0xfe00707fL)

    val op = WireDefault(BExtensionOp.clz)
    when(isCtz) {
      op := BExtensionOp.ctz
    }.elsewhen(isCpop) {
      op := BExtensionOp.cpop
    }.elsewhen(isClmul) {
      op := BExtensionOp.clmul
    }.elsewhen(isOrcB) {
      op := BExtensionOp.orcB
    }.elsewhen(isXperm4) {
      op := BExtensionOp.xperm4
    }.elsewhen(isRor) {
      op := BExtensionOp.ror
    }

    (isClz || isCtz || isCpop || isClmul || isOrcB || isXperm4 || isRor, op)
  }
}

class BExtensionInput extends Bundle {
  val op   = BExtensionOp()
  val src1 = Types.UWord
  val src2 = Types.UWord
}

/** Low-throughput iterative implementation of the selected scalar B instructions.
  *
  * Every operation spends 32 cycles in the busy state.  The result is always
  * registered before it becomes visible on the output, keeping the iterative
  * datapath out of the EXU-to-LSU combinational result path.
  */
class BExtensionUnit extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new BExtensionInput))
    val out = Decoupled(Types.UWord)
  })

  object State extends ChiselEnum {
    val idle, busy, done = Value
  }

  val state     = RegInit(State.idle)
  val opReg     = Reg(BExtensionOp())
  val sourceA   = Reg(Types.UWord)
  val workA     = Reg(Types.UWord)
  val workB     = Reg(Types.UWord)
  val accum     = Reg(Types.UWord)
  val found     = Reg(Bool())
  val iteration = Reg(UInt(5.W))
  val resultReg = Reg(Types.UWord)

  io.in.ready  := state === State.idle
  io.out.valid := state === State.done
  io.out.bits  := resultReg

  val nextWorkA = WireDefault(workA)
  val nextWorkB = WireDefault(workB)
  val nextAccum = WireDefault(accum)
  val nextFound = WireDefault(found)

  switch(opReg) {
    is(BExtensionOp.clz) {
      nextWorkA := workA << 1
      when(!found) {
        when(workA(31)) {
          nextFound := true.B
        }.otherwise {
          nextAccum := accum + 1.U
        }
      }
    }
    is(BExtensionOp.ctz) {
      nextWorkA := workA >> 1
      when(!found) {
        when(workA(0)) {
          nextFound := true.B
        }.otherwise {
          nextAccum := accum + 1.U
        }
      }
    }
    is(BExtensionOp.cpop) {
      nextWorkA := workA >> 1
      nextAccum := accum + workA(0)
    }
    is(BExtensionOp.clmul) {
      nextWorkA := workA << 1
      nextWorkB := workB >> 1
      when(workB(0)) {
        nextAccum := accum ^ workA
      }
    }
    is(BExtensionOp.orcB) {
      when(iteration < 4.U) {
        nextWorkA := workA >> 8
        nextAccum := Cat(Fill(8, workA(7, 0).orR), accum(31, 8))
      }
    }
    is(BExtensionOp.xperm4) {
      when(iteration < 8.U) {
        val index = workB(3, 0)
        val selectedNibble = MuxLookup(index, 0.U(4.W))(
          (0 until 8).map(i => i.U -> sourceA(4 * i + 3, 4 * i))
        )
        nextWorkB := workB >> 4
        nextAccum := Cat(selectedNibble, accum(31, 4))
      }
    }
    is(BExtensionOp.ror) {
      when(workB(4, 0) =/= 0.U) {
        nextWorkA := Cat(workA(0), workA(31, 1))
        nextWorkB := workB - 1.U
      }
    }
  }

  switch(state) {
    is(State.idle) {
      when(io.in.fire) {
        opReg     := io.in.bits.op
        sourceA   := io.in.bits.src1
        workA     := io.in.bits.src1
        workB     := io.in.bits.src2
        accum     := 0.U
        found     := false.B
        iteration := 0.U
        state     := State.busy
      }
    }
    is(State.busy) {
      workA := nextWorkA
      workB := nextWorkB
      accum := nextAccum
      found := nextFound
      when(iteration === 31.U) {
        resultReg := Mux(opReg === BExtensionOp.ror, nextWorkA, nextAccum)
        state     := State.done
      }.otherwise {
        iteration := iteration + 1.U
      }
    }
    is(State.done) {
      when(io.out.fire) {
        state := State.idle
      }
    }
  }
}
