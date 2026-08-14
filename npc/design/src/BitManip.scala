package cpu.alu

import chisel3._
import chisel3.util._

import common_def._

object BExtensionOp extends ChiselEnum {
  val clz, ctz = Value
}

class BExtensionInput extends Bundle {
  val isImm = Bool()
  val func3t = UInt(3.W)
  val func7t = UInt(7.W)
  val src1 = Types.UWord
  val src2 = Types.UWord
}

/** Low-throughput iterative implementation of the selected scalar B instructions.
  *
  * Short B operations are decoded in ALU and stay on its single-cycle path.
  * This unit is reserved for operations whose current implementation is
  * iterative; their result is registered before it becomes visible on the
  * output, keeping the iterative datapath out of the EXU-to-LSU path.
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
  val workA     = Reg(UInt(64.W))
  val count     = Reg(UInt(6.W))
  val found     = Reg(Bool())
  val iteration = Reg(UInt(5.W))
  val resultReg = Reg(Types.UWord)

  io.in.ready  := state === State.idle
  io.out.valid := state === State.done
  io.out.bits  := resultReg

  val nextWorkA = WireDefault(workA)
  val nextCount = WireDefault(count)
  val nextFound = WireDefault(found)

  // Exact B decoding remains local to this low-throughput unit. Decode the
  // accepted request directly and register only the selected operation, so no
  // extra decode state enters the global EXU pipeline-enable path.
  val immLow5 = io.in.bits.src2(4, 0)
  val isClz =
    io.in.bits.isImm && io.in.bits.func3t === "b001".U && io.in.bits.func7t === "b0110000".U && immLow5 === 0.U
  val isCtz =
    io.in.bits.isImm && io.in.bits.func3t === "b001".U && io.in.bits.func7t === "b0110000".U && immLow5 === 1.U
  val decodedValid = isClz || isCtz
  val decodedOp = Mux(isCtz, BExtensionOp.ctz, BExtensionOp.clz)

  switch(opReg) {
    is(BExtensionOp.clz) {
      nextWorkA := workA << 1
      when(!found) {
        when(workA(31)) {
          nextFound := true.B
        }.otherwise {
          nextCount := count + 1.U
        }
      }
    }
    is(BExtensionOp.ctz) {
      nextWorkA := workA >> 1
      when(!found) {
        when(workA(0)) {
          nextFound := true.B
        }.otherwise {
          nextCount := count + 1.U
        }
      }
    }
  }

  switch(state) {
    is(State.idle) {
      when(io.in.fire) {
        assert(decodedValid, "unsupported arithmetic encoding entered BExtensionUnit")
        opReg     := decodedOp
        workA     := io.in.bits.src1
        count     := 0.U
        found     := false.B
        iteration := 0.U
        state     := State.busy
      }
    }
    is(State.busy) {
      workA := nextWorkA
      count := nextCount
      found := nextFound
      when(iteration === 31.U) {
        resultReg := Cat(0.U(26.W), nextCount)
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
