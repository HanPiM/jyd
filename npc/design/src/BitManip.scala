package cpu.alu

import chisel3._
import chisel3.util._

import common_def._

object BExtensionOp extends ChiselEnum {
  val clz, ctz, cpop, clmul, orcB, xperm4, ror = Value
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
  val count     = Reg(UInt(6.W))
  val rorRemain = Reg(UInt(5.W))
  val found     = Reg(Bool())
  val iteration = Reg(UInt(5.W))
  val resultReg = Reg(Types.UWord)

  io.in.ready  := state === State.idle
  io.out.valid := state === State.done
  io.out.bits  := resultReg

  val nextWorkA = WireDefault(workA)
  val nextWorkB = WireDefault(workB)
  val nextAccum = WireDefault(accum)
  val nextCount = WireDefault(count)
  val nextRorRemain = WireDefault(rorRemain)
  val nextFound = WireDefault(found)

  // Exact B decoding remains local to this low-throughput unit. Decode the
  // accepted request directly and register only the selected operation, so no
  // extra decode state enters the global EXU pipeline-enable path.
  val immLow5 = io.in.bits.src2(4, 0)
  val isClz =
    io.in.bits.isImm && io.in.bits.func3t === "b001".U && io.in.bits.func7t === "b0110000".U && immLow5 === 0.U
  val isCtz =
    io.in.bits.isImm && io.in.bits.func3t === "b001".U && io.in.bits.func7t === "b0110000".U && immLow5 === 1.U
  val isCpop =
    io.in.bits.isImm && io.in.bits.func3t === "b001".U && io.in.bits.func7t === "b0110000".U && immLow5 === 2.U
  val isClmul =
    !io.in.bits.isImm && io.in.bits.func3t === "b001".U && io.in.bits.func7t === "b0000101".U
  val isOrcB =
    io.in.bits.isImm && io.in.bits.func3t === "b101".U && io.in.bits.func7t === "b0010100".U && immLow5 === 7.U
  val isXperm4 =
    !io.in.bits.isImm && io.in.bits.func3t === "b010".U && io.in.bits.func7t === "b0010100".U
  val isRor =
    !io.in.bits.isImm && io.in.bits.func3t === "b101".U && io.in.bits.func7t === "b0110000".U
  val decodedValid = isClz || isCtz || isCpop || isClmul || isOrcB || isXperm4 || isRor
  val decodedOp = MuxCase(
    BExtensionOp.clz,
    Seq(
      isCtz    -> BExtensionOp.ctz,
      isCpop   -> BExtensionOp.cpop,
      isClmul  -> BExtensionOp.clmul,
      isOrcB   -> BExtensionOp.orcB,
      isXperm4 -> BExtensionOp.xperm4,
      isRor    -> BExtensionOp.ror
    )
  )

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
    is(BExtensionOp.cpop) {
      nextWorkA := workA >> 1
      nextCount := count + workA(0)
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
      when(rorRemain =/= 0.U) {
        nextWorkA := Cat(workA(0), workA(31, 1))
        nextRorRemain := rorRemain - 1.U
      }
    }
  }

  switch(state) {
    is(State.idle) {
      when(io.in.fire) {
        assert(decodedValid, "unsupported arithmetic encoding entered BExtensionUnit")
        opReg     := decodedOp
        sourceA   := io.in.bits.src1
        workA     := io.in.bits.src1
        workB     := io.in.bits.src2
        accum     := 0.U
        count     := 0.U
        rorRemain := io.in.bits.src2(4, 0)
        found     := false.B
        iteration := 0.U
        state     := State.busy
      }
    }
    is(State.busy) {
      workA := nextWorkA
      workB := nextWorkB
      accum := nextAccum
      count := nextCount
      rorRemain := nextRorRemain
      found := nextFound
      when(iteration === 31.U) {
        val countResult = Cat(0.U(26.W), nextCount)
        val isCountOp = opReg === BExtensionOp.clz || opReg === BExtensionOp.ctz || opReg === BExtensionOp.cpop
        resultReg := Mux(opReg === BExtensionOp.ror, nextWorkA, Mux(isCountOp, countResult, nextAccum))
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
