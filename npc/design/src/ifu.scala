package cpu
import chisel3._
import chisel3.util._
import common_def._
import simplebus._
import dpiwrap._

class IFU extends Module {
  val io = IO(new Bundle {
    val pc              = Flipped(Decoupled(Types.UWord))
    val predictedNextPC = Input(Types.UWord)
    val mem             = SimpleBusIO.Master
    val out             = Decoupled(new Inst)
  })

  object State extends ChiselEnum {
    val idle, waitReq, waitResp, waitOut = Value
  }

  dontTouch(io)
  val memIO = io.mem
  memIO.dontCareReq()

  val state = RegInit(State.idle)

  val pcReg          = Reg(Types.UWord)
  val predNxtPCReg   = Reg(Types.UWord)
  val reqIIDReg      = Reg(UInt(Types.BitWidth.inst_id.W))
  val pendingPCReg   = Reg(Types.UWord)
  val pendingPredReg = Reg(Types.UWord)
  val pendingIIDReg  = Reg(UInt(Types.BitWidth.inst_id.W))
  dontTouch(pcReg)

  val instID = RegInit(0.U(Types.BitWidth.inst_id.W))
  dontTouch(instID)

  val isWaitingRespMeetValid = (state === State.waitResp) && memIO.resp_valid
  val consumeResp           = isWaitingRespMeetValid && io.out.ready
  val canAcceptInputReq     = (state === State.idle) || consumeResp

  io.pc.ready := canAcceptInputReq

  val inputReqValid   = canAcceptInputReq && io.pc.valid
  val inputReqFire    = inputReqValid && memIO.req_ready
  val pendingReqValid = (state === State.waitReq)
  val pendingReqFire  = pendingReqValid && memIO.req_ready
  val acceptInputReq  = io.pc.fire
  val nextIID         = instID + 1.U

  when(acceptInputReq) {
    instID := nextIID
  }

  StageLogger(
    clock,
    StageLogConst.Event.stage,
    StageLogConst.Stage.ifu,
    acceptInputReq,
    nextIID,
    io.pc.bits
  )

  when(inputReqFire) {
    pcReg        := io.pc.bits
    predNxtPCReg := io.predictedNextPC
    reqIIDReg    := nextIID
  }.elsewhen(pendingReqFire) {
    pcReg        := pendingPCReg
    predNxtPCReg := pendingPredReg
    reqIIDReg    := pendingIIDReg
  }

  when(acceptInputReq && !inputReqFire) {
    pendingPCReg   := io.pc.bits
    pendingPredReg := io.predictedNextPC
    pendingIIDReg  := nextIID
  }

  memIO.req_valid := inputReqValid || pendingReqValid
  memIO.addr      := Mux(pendingReqValid, pendingPCReg, io.pc.bits)
  memIO.size      := 2.U
  memIO.wen       := false.B
  memIO.wdata     := 0.U
  memIO.wmask     := 0.U

  val inst = RegEnableReadNew(memIO.rdata, memIO.resp_valid)
  io.out.bits.code            := inst
  io.out.bits.pc              := pcReg
  io.out.bits.predictedNextPC := predNxtPCReg
  io.out.bits.iid             := reqIIDReg
  io.out.valid                := isWaitingRespMeetValid || (state === State.waitOut)

  val nxtStateWhenWaitOut = Mux(io.out.ready, State.idle, State.waitOut)
  val nxtStateWhenConsumeResp = Mux(
    io.pc.valid,
    Mux(memIO.req_ready, State.waitResp, State.waitReq),
    State.idle
  )
  val nxtStateWhenIdle = Mux(
    io.pc.valid,
    Mux(memIO.req_ready, State.waitResp, State.waitReq),
    State.idle
  )

  dontTouch(nxtStateWhenIdle)

  state := MuxLookup(state, State.idle)(
    Seq(
      State.idle     -> nxtStateWhenIdle,
      State.waitReq  -> Mux(pendingReqFire, State.waitResp, State.waitReq),
      State.waitResp -> Mux(memIO.resp_valid, Mux(io.out.ready, nxtStateWhenConsumeResp, State.waitOut), State.waitResp),
      State.waitOut  -> nxtStateWhenWaitOut
    )
  )
}

/*


  val state = RegInit(State.idle)
  state         := MuxLookup(state, State.idle)(
    Seq(
      State.idle   -> Mux(io.pc.fire, State.waitAR, State.idle),
      State.waitAR -> Mux(memIO.arready, Mux(memIO.rvalid,State.idle,State.waitR), State.waitAR),
      State.waitR  -> Mux(memIO.rvalid, Mux(io.pc.fire, State.waitAR, State.idle), State.waitR)
    )
  )
  val pcReg = Reg(Types.UWord)
  when(io.pc.fire) {
    pcReg := io.pc.bits
  }
  memIO.arvalid := (state === State.waitAR) || (state === State.idle && io.pc.fire)
  memIO.araddr  := Mux(io.pc.fire, io.pc.bits, pcReg)

  val instReg = Reg(Types.UWord)
  when(memIO.rvalid) {
    instReg := memIO.rdata
  }
  memIO.rready := (state === State.waitR) && io.out.ready

  io.out.valid := (state === State.waitR && memIO.rvalid) || (state === State.idle && io.pc.fire && memIO.rvalid)
  io.pc.ready

  io.out.bits.code := Mux(memIO.rvalid, memIO.rdata, instReg)
  io.out.bits.pc   := Mux(io.pc.fire, io.pc.bits, pcReg)
 * */
