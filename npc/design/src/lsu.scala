package cpu
import chisel3._
import chisel3.util._
import common_def._
import dpiwrap.{StageLogConst, StageLogger}

class LSUInput(
  implicit p: CPUParameters)
    extends Bundle {
  val isLoad       = Bool()
  val isStore      = Bool()
  val destAddr     = Types.UWord
  val storeData    = Types.UWord
  val func3t       = UInt(3.W)
  val exuWriteBack = new WriteBackInfo
}

object ExtractFwdInfoFromLSU {
  def apply(
    info:       DecoupledIO[LSUInput]
  )(
    implicit p: CPUParameters
  ): WrBackForwardInfo = {
    val wrBack = info.bits.exuWriteBack

    val out = Wire(new WrBackForwardInfo)
    out.addr      := wrBack.gpr.addr
    out.enWr      := wrBack.gpr.en && info.valid
    out.dataVaild := !info.bits.isLoad
    out.data      := wrBack.gpr.data
    out
  }
}

// object ExtractGPRInfoFromLSU {
//   def apply(info: DecoupledIO[LSUInput]): GPRegReqIO._WriteRX = {
//     val gprInfo = info.bits.exuWriteBack.gpr
//     val valid   = info.valid
//
//     val out = Wire(GPRegReqIO.RX.Write)
//     out.en   := gprInfo.en && valid
//     out.addr := gprInfo.addr
//     out.data := gprInfo.data
//     out
//   }
// }

class LSUIO(
  implicit p: CPUParameters)
    extends Bundle {
  val memResp  = Flipped(Valid(Types.UWord))

  val in  = Flipped(Decoupled(new LSUInput))
  val out = Decoupled(new WriteBackInfo)
}

class LSU(
  implicit p: CPUParameters)
    extends Module {
  val io = IO(new LSUIO)

  // object State extends ChiselEnum {
  //   val idle, waitResp, waitOut = Value
  // }
  // val state = RegInit(State.idle)
  // val isIdle = state === State.idle
  //
  val outWriteBackInfo = io.out.bits

  val in      = io.in.bits
  val memResp = io.memResp

  val isLoadOp = in.isLoad && io.in.valid
  val isMemLoad = isLoadOp
  val isStore = in.isStore && io.in.valid

  val isMemOp = isMemLoad || isStore
  // val fireLocalBypass = isIdle && io.in.valid && (!isMemOp) && io.out.ready
  // val seesMemResp     = ((state === State.idle) && isMemOp && io.in.valid || (state === State.waitResp)) && memResp.valid
  //
  val activeReq      = io.in.bits
  val memRdRawData   = memResp.bits

  io.in.ready := io.out.ready
  io.out.valid := io.in.valid

  //
  // io.in.ready := Mux(
  //   isMemOp,
  //   (seesMemResp || (state === State.waitOut)) && io.out.ready,
  //   isIdle && io.out.ready
  // )
  //
  // io.out.valid := fireLocalBypass || seesMemResp || (state === State.waitOut)
  //
  //
  // val nxtStateWhenWaitOut  = Mux(io.out.ready, State.idle, State.waitOut)
  // val nxtStateWhenWaitResp = Mux(memResp.valid, nxtStateWhenWaitOut, State.waitResp)
  // state := MuxLookup(state, State.idle)(
  //   Seq(
  //     State.idle     -> Mux(io.in.valid && isMemOp, Mux(memResp.valid, nxtStateWhenWaitOut, State.waitResp), State.idle),
  //     State.waitResp -> nxtStateWhenWaitResp,
  //     State.waitOut  -> nxtStateWhenWaitOut
  //   )
  // )

  outWriteBackInfo.csr.en        := activeReq.exuWriteBack.csr.en
  outWriteBackInfo.csr.addr      := activeReq.exuWriteBack.csr.addr
  outWriteBackInfo.csr.data      := activeReq.exuWriteBack.csr.data
  outWriteBackInfo.csr_ecallflag := activeReq.exuWriteBack.csr_ecallflag
  outWriteBackInfo.gpr.addr      := activeReq.exuWriteBack.gpr.addr
  outWriteBackInfo.gpr.en        := activeReq.exuWriteBack.gpr.en
  outWriteBackInfo.gpr.data      := activeReq.exuWriteBack.gpr.data
  outWriteBackInfo.isLoad        := activeReq.isLoad
  outWriteBackInfo.lsuResult     := memRdRawData
  outWriteBackInfo.lsuFunc3t     := activeReq.func3t
  outWriteBackInfo.lsuAddrOffset := activeReq.destAddr(1, 0)
  outWriteBackInfo.iid           := activeReq.exuWriteBack.iid

  StageLogger(
    clock,
    StageLogConst.Event.stage,
    StageLogConst.Stage.lsu,
    io.in.fire,
    io.in.bits.exuWriteBack.iid
  )
}

class LSUInputForDifftest extends Bundle {
  val isLoad   = Bool()
  val isStore  = Bool()
  val destAddr = Types.UWord
  val pc       = Types.UWord
  val nxtPC    = Types.UWord
  val isEBreak = Bool()
}

class LSUForDifftest(
  implicit p: CPUParameters)
    extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new LSUInputForDifftest))

    val actualLSU = new Bundle {
      val inReady  = Input(Bool())
      val outValid = Input(Bool())
    }

    val out = Decoupled(new DifftestWriteBackInfo)
  })

  io.in.ready  := io.actualLSU.inReady
  io.out.valid := io.actualLSU.outValid

  val memAddr     = io.in.bits.destAddr
  // val isSerialAddr = AddrSpace.inRng(memAddr, AddrSpace.SERIAL)
  // val isSPIAddr    = AddrSpace.inRng(memAddr, AddrSpace.SPI)
  val isClintAddr = AddrSpace.inRng(memAddr, AddrSpace.CLINT)
  // val isVGAAddr    = AddrSpace.inRng(memAddr, AddrSpace.VGA)
  // val isPS2Addr    = AddrSpace.inRng(memAddr, AddrSpace.PS2)

  val isLoadOp  = io.in.bits.isLoad && io.in.valid
  val isStore   = io.in.bits.isStore && io.in.valid
  val isMemLoad = isLoadOp && (!isClintAddr)
  val isMemOp   = isMemLoad || isStore

  val inSkipRng = p.skipDifftestAddrs.map(addr => AddrSpace.inRng(memAddr, addr)).reduce(_ || _)
  val needSkipDifftest =
    (isMemOp && inSkipRng) || (isLoadOp && isClintAddr)

  // val needSkipDifftest =
  //   (isMemOp && (isSerialAddr || isSPIAddr || isClintAddr || isVGAAddr || isPS2Addr)) || (isLoadOp && isClintAddr)

  val outInfo = io.out.bits
  outInfo.pc          := io.in.bits.pc
  outInfo.needSkipRef := needSkipDifftest
  outInfo.isEBreak    := io.in.bits.isEBreak
  outInfo.nxtPC       := io.in.bits.nxtPC
}
