package branchpredictor

import chisel3._
import chisel3.util._

import common_def._

class BranchPredictorIO extends Bundle {
  val historyHit = Input(Bool())

  val pc = Input(Types.UWord)

  val historyIsJAL  = Input(Bool())
  val historyIsBackward = Input(Bool())

  val historyTarget = Input(Types.UWord)

  val pred = Output(new PredBundle)
}

class BranchPredictor extends Module {
  val io = IO(new BranchPredictorIO)

  // Simple static predictor: BTFN (Backward Taken, Forward Not Taken)

  val isBackward = io.historyIsBackward

  val take = io.historyHit && (io.historyIsJAL || isBackward)

  // io.predictTarget := Mux(io.historyHit, io.historyTarget, io.pc + 4.U)
  // io.predictTarget := Mux(io.historyHit && isBackward, io.historyTarget, io.pc + 4.U)
  // io.predictTarget := io.pc + 4.U

  io.pred.hit := io.historyHit

  io.pred.pc := "h800".U(12.W) ## 0.U(3.W) ## Mux(take, io.historyTarget(16,2), io.pc(16,2) + 1.U) ## 0.U(2.W)

  io.pred.take := take
}
