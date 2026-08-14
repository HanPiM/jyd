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
  val cacheableLoad = Bool()
  val dcacheHit    = Bool()
  val dcacheStoreEpoch = Bool()
  val func3t       = UInt(3.W)
  val lateBranchRedirect = Bool()
  val exuWriteBack = new WriteBackInfo
}

object ExtractFwdInfoFromLSU {
  def apply(
    info:            DecoupledIO[LSUInput],
    dcacheReadData:  UInt,
    registeredAddr:  UInt,
    registeredEnWr:  Bool,
    registeredValid: Bool
  )(
    implicit p: CPUParameters
  ): WrBackForwardInfo = {
    val wrBack = info.bits.exuWriteBack
    val loadData = ExtLoadData(dcacheReadData, info.bits.destAddr(1, 0), info.bits.func3t)
    val out = Wire(new WrBackForwardInfo)
    out.addr      := registeredAddr
    out.enWr      := registeredEnWr
    out.dataVaild := registeredValid
    out.data      := Mux(info.bits.isLoad, loadData, ResultLaneSelect.nonLoadData(wrBack))
    out.kind      := wrBack.resultKind

    out
  }
}

object ExtractFastFwdInfoFromLSU {
  def apply(info: DecoupledIO[LSUInput])(
    implicit p: CPUParameters
  ): WrBackForwardInfo = {
    val wrBack = info.bits.exuWriteBack
    val out = Wire(new WrBackForwardInfo)
    out.addr      := wrBack.fastResult.rd
    out.enWr      := wrBack.fastResult.valid && info.valid
    out.dataVaild := wrBack.fastResult.valid && info.valid
    out.data      := wrBack.fastResult.data
    out.kind      := ResultKind.fastInt
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
  val in  = Flipped(Decoupled(new LSUInput))
  val out = Decoupled(new WriteBackInfo)
  val dcacheReadData = Input(Types.UWord)
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

  val isLoadOp = in.isLoad && io.in.valid
  val isMemLoad = isLoadOp
  val isStore = in.isStore && io.in.valid

  val isMemOp = isMemLoad || isStore
  // val fireLocalBypass = isIdle && io.in.valid && (!isMemOp) && io.out.ready
  // val seesMemResp     = ((state === State.idle) && isMemOp && io.in.valid || (state === State.waitResp)) && memResp.valid
  //
  val activeReq      = io.in.bits

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

  outWriteBackInfo.resultKind    := activeReq.exuWriteBack.resultKind
  outWriteBackInfo.fastResult    := activeReq.exuWriteBack.fastResult
  outWriteBackInfo.directResult  := activeReq.exuWriteBack.directResult
  outWriteBackInfo.longResult    := activeReq.exuWriteBack.longResult
  outWriteBackInfo.acceleratorResult := activeReq.exuWriteBack.acceleratorResult
  outWriteBackInfo.loadResult    := activeReq.exuWriteBack.loadResult
  outWriteBackInfo.isLoad        := activeReq.isLoad
  outWriteBackInfo.isMemOp       := isMemOp
  outWriteBackInfo.lsuResult     := io.dcacheReadData
  outWriteBackInfo.lsuFunc3t     := activeReq.func3t
  outWriteBackInfo.lsuAddrOffset := activeReq.destAddr(1, 0)
  outWriteBackInfo.iid           := activeReq.exuWriteBack.iid
  outWriteBackInfo.memAddr       := activeReq.destAddr
  outWriteBackInfo.cacheableLoad := activeReq.cacheableLoad
  outWriteBackInfo.dcacheHit     := activeReq.dcacheHit
  outWriteBackInfo.dcacheStoreEpoch := activeReq.dcacheStoreEpoch

  when(io.in.valid) {
    assert(PopCount(ResultLaneSelect.validVec(activeReq.exuWriteBack)) <= 1.U, "LSU result lanes must be one-hot")
  }

  StageLogger(
    clock,
    StageLogConst.Event.stage,
    StageLogConst.Stage.lsu,
    io.in.fire,
    io.in.bits.exuWriteBack.iid
  )
}

class LSUInputForDifftest extends Bundle {
  val code     = Types.UWord
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
  outInfo.code        := io.in.bits.code
  outInfo.pc          := io.in.bits.pc
  outInfo.needSkipRef := needSkipDifftest
  outInfo.isEBreak    := io.in.bits.isEBreak
  outInfo.nxtPC       := io.in.bits.nxtPC
}
