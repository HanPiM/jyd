package branchpredictor

import chisel3._
import chisel3.util._

import common_def._

class BranchPredictorIO extends Bundle {
  val historyHit = Input(Bool())

  val pc = Input(Types.UWord)

  val historyIsJAL  = Input(Bool())
  val historyIsBranch = Input(Bool())
  val historyDirectionTaken = Input(Bool())
  val historyIsBackward = Input(Bool())

  val historyTarget = Input(Types.UWord)

  val pred = Output(new PredBundle)
}

class BranchPredictor extends Module {
  val io = IO(new BranchPredictorIO)

  // Per-BTB-entry dynamic direction prediction. JAL remains unconditionally taken.

  val take = io.historyHit && (io.historyIsJAL || (io.historyIsBranch && io.historyDirectionTaken))

  // io.predictTarget := Mux(io.historyHit, io.historyTarget, io.pc + 4.U)
  // io.predictTarget := Mux(io.historyHit && isBackward, io.historyTarget, io.pc + 4.U)
  // io.predictTarget := io.pc + 4.U

  io.pred.hit := io.historyHit

  io.pred.pc := "h80".U(8.W) ## 0.U(2.W) ## Mux(take, io.historyTarget(21,2), io.pc(21,2) + 1.U) ## 0.U(2.W)

  io.pred.take := take
}
