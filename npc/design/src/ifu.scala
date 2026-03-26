package cpu
import chisel3._
import chisel3.util._
import common_def._
import simplebus._

class IFU extends Module {
  val io = IO(new Bundle {
    val pc              = Flipped(Decoupled(Types.UWord))
    val predictedNextPC = Input(Types.UWord)
    val mem             = SimpleBusIO.Master
    val out             = Decoupled(new Inst)
  })

  object State extends ChiselEnum {
    val idle, waitResp, waitOut = Value
  }

  dontTouch(io)
  val memIO = io.mem
  memIO.dontCareReq()

  val pcReg     = RegEnable(io.pc.bits, io.pc.fire)
  val pc        = Mux(io.pc.fire, io.pc.bits, pcReg)
  val predNxtPC = RegEnableReadNew(io.predictedNextPC, io.pc.fire)
  dontTouch(pc)
  val state     = RegInit(State.idle)

  val instID = RegInit(0.U(Types.BitWidth.inst_id.W))
  when(io.pc.fire) {
    instID := instID + 1.U
  }
  dontTouch(instID)

  io.out.bits.iid := Mux(io.pc.fire, instID + 1.U, instID)

  io.pc.ready  := (state === State.idle) && memIO.req_ready
  memIO.req_valid := (state === State.idle) && io.pc.valid
  memIO.addr      := io.pc.bits
  memIO.size      := 2.U
  memIO.wen       := false.B
  memIO.wdata     := 0.U
  memIO.wmask     := 0.U

  val inst = RegEnableReadNew(memIO.rdata, memIO.resp_valid)
  io.out.bits.code            := inst
  io.out.bits.pc              := pc
  io.out.bits.predictedNextPC := predNxtPC
  io.out.valid                := (state === State.waitResp && memIO.resp_valid) || (state === State.waitOut)

  val nxtStateWhenWaitOut  = Mux(io.out.ready, State.idle, State.waitOut)
  val nxtStateWhenWaitResp = Mux(memIO.resp_valid, nxtStateWhenWaitOut, State.waitResp)
  val nxtStateWhenIdle     = Mux(io.pc.fire, nxtStateWhenWaitResp, State.idle)

  dontTouch(nxtStateWhenIdle)

  state := MuxLookup(state, State.idle)(
    Seq(
      State.idle     -> nxtStateWhenIdle,
      State.waitResp -> nxtStateWhenWaitResp,
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
