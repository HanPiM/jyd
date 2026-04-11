package cpu
import chisel3._
import chisel3.util._
import branchpredictor._
import btb._
import common_def._
import dpiwrap._
import simplebus._

class IFU extends Module {
  val io = IO(new Bundle {
    val startPC = Input(Types.UWord)
    val redirect = Input(new Bundle {
      val valid    = Bool()
      val targetPC = Types.UWord
    })
    val btbUpdate = Input(new Bundle {
      val en     = Bool()
      val addr   = Types.UWord
      val target = Types.UWord
      val isJAL  = Bool()
    })
    val acceptedCorrectTarget = Output(Bool())
    val outputCorrectTarget   = Output(Bool())
    val mem                   = SimpleBusIO.Master
    val out                   = Decoupled(new FetchedInst)
  })

  object State extends ChiselEnum {
    val idle, waitReq, waitResp, waitOut = Value
  }

  dontTouch(io)
  val memIO = io.mem
  memIO.dontCareReq()

  val btb = if (config.Config.useBTBAndBP) {
    Some(Module(new BranchTargetBuffer))
  } else {
    None
  }
  val bp  = if (config.Config.useBTBAndBP) {
    Some(Module(new BranchPredictor))
  } else {
    None
  }

  btb.foreach { btbMod =>
    btbMod.io.update.en     := io.btbUpdate.en
    btbMod.io.update.addr   := io.btbUpdate.addr
    btbMod.io.update.target := io.btbUpdate.target
    btbMod.io.update.isJAL  := io.btbUpdate.isJAL
  }

  val state = RegInit(State.idle)

  val pcReg             = Reg(Types.UWord)
  val predNxtPCReg      = Reg(Types.UWord)
  val reqIIDReg         = Reg(UInt(Types.BitWidth.inst_id.W))
  val nextPCReg         = RegInit(io.startPC)
  val correctTargetReg  = RegInit(io.startPC)
  val redirectPendingReg = RegInit(false.B)
  val waitCorrectOutReg  = RegInit(false.B)
  dontTouch(pcReg)

  val instID = RegInit(0.U(Types.BitWidth.inst_id.W))
  dontTouch(instID)

  val redirectActive    = redirectPendingReg || io.redirect.valid
  val currentCorrectPC  = Mux(io.redirect.valid, io.redirect.targetPC, correctTargetReg)
  val requestPC         = Mux(redirectActive, currentCorrectPC, nextPCReg)

  btb.foreach { btbMod =>
    btbMod.io.query.addr := requestPC
  }
  bp.foreach { bpMod =>
    bpMod.io.pc            := requestPC
    bpMod.io.historyHit    := btb.get.io.query.hit
    bpMod.io.historyTarget := btb.get.io.query.target
    bpMod.io.historyIsJAL  := btb.get.io.query.isJAL
  }

  val predictedNextPC = bp.map(_.io.predictTarget).getOrElse(requestPC + 4.U)

  val isWaitingRespMeetValid = (state === State.waitResp) && memIO.resp_valid
  val consumeResp            = isWaitingRespMeetValid && io.out.ready
  val canStartReq            = (state === State.idle) || consumeResp
  val reqValid               = canStartReq || (state === State.waitReq)
  val reqFire                = reqValid && memIO.req_ready
  val nextIID                = instID + 1.U

  io.acceptedCorrectTarget := reqFire && redirectActive

  when(reqFire) {
    instID       := nextIID
    pcReg        := requestPC
    predNxtPCReg := predictedNextPC
    reqIIDReg    := nextIID
    nextPCReg    := predictedNextPC
  }

  when(io.redirect.valid) {
    correctTargetReg := io.redirect.targetPC
  }

  when(io.redirect.valid) {
    redirectPendingReg := true.B
    waitCorrectOutReg  := true.B
  }
  when(io.acceptedCorrectTarget) {
    redirectPendingReg := false.B
  }

  val inst = RegEnableReadNew(memIO.rdata, memIO.resp_valid)

  io.out.bits.code            := inst
  io.out.bits.pc              := pcReg
  io.out.bits.predictedNextPC := predNxtPCReg
  io.out.bits.iid             := reqIIDReg
  io.out.valid                := isWaitingRespMeetValid || (state === State.waitOut)

  io.outputCorrectTarget := io.out.valid && waitCorrectOutReg && (io.out.bits.pc === correctTargetReg)
  when(io.outputCorrectTarget) {
    waitCorrectOutReg := false.B
  }

  StageLogger(
    clock,
    StageLogConst.Event.stage,
    StageLogConst.Stage.ifu,
    reqFire,
    nextIID,
    requestPC(31, 0)
  )

  memIO.req_valid := reqValid
  memIO.addr      := requestPC
  memIO.size      := 2.U
  memIO.wen       := false.B
  memIO.wdata     := 0.U
  memIO.wmask     := 0.U

  state := MuxLookup(state, State.idle)(
    Seq(
      State.idle     -> Mux(reqFire, State.waitResp, State.waitReq),
      State.waitReq  -> Mux(reqFire, State.waitResp, State.waitReq),
      State.waitResp -> Mux(memIO.resp_valid, Mux(io.out.ready, Mux(memIO.req_ready, State.waitResp, State.waitReq), State.waitOut), State.waitResp),
      State.waitOut  -> Mux(io.out.ready, State.idle, State.waitOut)
    )
  )
}
