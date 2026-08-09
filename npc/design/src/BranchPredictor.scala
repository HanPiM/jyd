package branchpredictor

import chisel3._
import chisel3.util._

import common_def._

class BranchPredictorIO extends Bundle {
  val historyHit = Input(Bool())

  val pc = Input(Types.UWord)

  val historyIsJAL  = Input(Bool())
  val historyIsBranch = Input(Bool())
  val historyIsReturn = Input(Bool())
  val historyDirectionTaken = Input(Bool())
  val historyTarget = Input(Types.UWord)

  val updateEn = Input(Bool())
  val updatePc = Input(Types.UWord)
  val updateIsCall = Input(Bool())
  val updateIsReturn = Input(Bool())

  val pred = Output(new PredBundle)
}

class BranchPredictor extends Module {
  val io = IO(new BranchPredictorIO)

  // 8-entry return-address stack for the workload's standard `ret` sites.
  // Calls (JAL/JALR with rd != x0) push pc+4; `ret` pops and predicts the
  // popped return address, which is stable and independent of BTB aliasing.
  val rasEntries = RegInit(VecInit(Seq.fill(2)(0.U(32.W))))
  val rasPtr     = RegInit(0.U(1.W))
  val rasCount   = RegInit(0.U(2.W))

  when(io.updateEn) {
    when(io.updateIsCall) {
      rasEntries(rasPtr) := io.updatePc + 4.U
      rasPtr := rasPtr + 1.U
      rasCount := Mux(rasCount === 2.U, rasCount, rasCount + 1.U)
    }.elsewhen(io.updateIsReturn) {
      when(rasCount =/= 0.U) {
        rasPtr := rasPtr - 1.U
        rasCount := rasCount - 1.U
      }
    }
  }

  val rasTop = rasEntries(rasPtr - 1.U)
  val useRas = io.historyIsReturn && io.historyHit && rasCount =/= 0.U

  // Per-BTB-entry dynamic direction prediction. JAL remains unconditionally taken.

  val take = Mux(useRas, true.B,
    io.historyHit && (io.historyIsJAL || (io.historyIsBranch && io.historyDirectionTaken)))

  // io.predictTarget := Mux(io.historyHit, io.historyTarget, io.pc + 4.U)
  // io.predictTarget := io.pc + 4.U

  io.pred.hit := io.historyHit

  val predictTarget = Mux(useRas, rasTop, io.historyTarget)
  io.pred.pc := "h80".U(8.W) ## 0.U(2.W) ## Mux(take, predictTarget(21,2), io.pc(21,2) + 1.U) ## 0.U(2.W)

  io.pred.take := take
}
