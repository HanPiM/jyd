package cpu
import chisel3._
import chisel3.util._
import common_def._

import chisel3.util.circt.dpi._
import simplebus._

import dpiwrap._
import dpiwrap.ClockedCallVoidDPIC

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
    out.dataVaild := false.B
    out.data      := DontCare
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
  val mem = SimpleBusIO.Master

  val mcycle64 = Input(UInt(64.W))

  val in  = Flipped(Decoupled(new LSUInput))
  val out = Decoupled(new WriteBackInfo)
}

class LSU(
  implicit p: CPUParameters)
    extends Module {
  val io = IO(new LSUIO)

  object State extends ChiselEnum {
    val idle, waitReq, waitResp, waitOut = Value
  }
  val state = RegInit(State.idle)
  val isIdle = state === State.idle

  val outWriteBackInfo = io.out.bits

  val in       = io.in.bits
  val memIO    = io.mem
  val respData = Reg(Types.UWord)

  memIO.dontCareReq()
  dontTouch(io.mem)

  val isLoadOp    = in.isLoad && io.in.valid
  // only compare high 8 bit since clint addr is 0x0200_0048/4c, 0x02 is unique in whole addr space
  val isCLINTAddr = in.destAddr(31, 27) === AddrSpace.CLINT._1(31, 27)
  val isMemLoad   = isLoadOp && (!isCLINTAddr)
  val isStore     = in.isStore && io.in.valid

  val isMemOp = isMemLoad || isStore
  val fireLocalBypass = isIdle && io.in.valid && (!isMemOp) && io.out.ready
  val reqActive       = io.in.valid && isMemOp && (state === State.idle || state === State.waitReq)
  val fireMemReq      = reqActive && memIO.req_ready

  when(state === State.waitResp && memIO.resp_valid) {
    respData := memIO.rdata
  }

  val activeReq      = io.in.bits
  val memAddr        = activeReq.destAddr
  val func3t         = activeReq.func3t
  val storeData      = activeReq.storeData
  val memAddrOffset  = memAddr(1, 0)
  val memRdRawData   = Mux(state === State.waitResp && memIO.resp_valid, memIO.rdata, respData)
  val memOpIsWord    = func3t(1)
  val memOpIsHalf    = (~func3t(1)) && func3t(0)
  val memOpIsByte    = (~func3t(1)) && (~func3t(0))
  val activeIsCLINT  = memAddr(31, 27) === AddrSpace.CLINT._1(31, 27)
  val clintRdData    = Mux(memAddr(2), io.mcycle64(63, 32), io.mcycle64(31, 0))

  val memWData = MuxLookup(memAddrOffset, 0.U(32.W))(
    Seq(
      0.U -> storeData,
      1.U -> Cat(storeData(23, 0), 0.U(8.W)),
      2.U -> Cat(storeData(15, 0), 0.U(16.W)),
      3.U -> Cat(storeData(7, 0), 0.U(24.W))
    )
  )
  val wByteMask = MuxLookup(memAddrOffset, 0.U(4.W))(
    Seq(
      0.U -> "b0001".U(4.W),
      1.U -> "b0010".U(4.W),
      2.U -> "b0100".U(4.W),
      3.U -> "b1000".U(4.W)
    )
  )
  val wByteMaskHalf = MuxLookup(memAddrOffset, 0.U(4.W))(
    Seq(
      0.U -> "b0011".U(4.W),
      1.U -> "b0110".U(4.W),
      2.U -> "b1100".U(4.W)
    )
  )
  val memWMask = Mux1H(
    Seq(
      memOpIsByte -> wByteMask,
      memOpIsHalf -> wByteMaskHalf,
      memOpIsWord -> "b1111".U(4.W)
    )
  )

  memIO.req_valid := reqActive
  memIO.addr      := in.destAddr
  memIO.size      := in.func3t(1, 0)
  memIO.wdata     := MuxLookup(in.destAddr(1, 0), 0.U(32.W))(
    Seq(
      0.U -> in.storeData,
      1.U -> Cat(in.storeData(23, 0), 0.U(8.W)),
      2.U -> Cat(in.storeData(15, 0), 0.U(16.W)),
      3.U -> Cat(in.storeData(7, 0), 0.U(24.W))
    )
  )
  memIO.wmask     := Mux1H(
    Seq(
      ((~in.func3t(1)) && (~in.func3t(0))) -> MuxLookup(in.destAddr(1, 0), 0.U(4.W))(
        Seq(0.U -> "b0001".U, 1.U -> "b0010".U, 2.U -> "b0100".U, 3.U -> "b1000".U)
      ),
      ((~in.func3t(1)) && in.func3t(0)) -> MuxLookup(in.destAddr(1, 0), 0.U(4.W))(
        Seq(0.U -> "b0011".U, 1.U -> "b0110".U, 2.U -> "b1100".U)
      ),
      in.func3t(1) -> "b1111".U(4.W)
    )
  )
  memIO.wen       := in.isStore

  io.in.ready := Mux(
    isMemOp,
    (((state === State.waitResp) && memIO.resp_valid) || (state === State.waitOut)) && io.out.ready,
    isIdle && io.out.ready
  )

  val lsuResult    = Mux(activeIsCLINT, clintRdData, memRdRawData)

  io.out.valid := fireLocalBypass || (state === State.waitResp && memIO.resp_valid) || (state === State.waitOut)

  StageLogger(
    clock,
    StageLogConst.Event.stage,
    StageLogConst.Stage.lsu,
    io.in.fire,
    io.in.bits.exuWriteBack.iid
  )

  val nxtStateWhenWaitOut  = Mux(io.out.ready, State.idle, State.waitOut)
  val nxtStateWhenWaitResp = Mux(memIO.resp_valid, nxtStateWhenWaitOut, State.waitResp)
  state := MuxLookup(state, State.idle)(
    Seq(
      State.idle     -> Mux(io.in.valid && isMemOp, Mux(memIO.req_ready, State.waitResp, State.waitReq), State.idle),
      State.waitReq  -> Mux(fireMemReq, State.waitResp, State.waitReq),
      State.waitResp -> nxtStateWhenWaitResp,
      State.waitOut  -> nxtStateWhenWaitOut
    )
  )

  outWriteBackInfo.csr.en        := activeReq.exuWriteBack.csr.en
  outWriteBackInfo.csr.addr      := activeReq.exuWriteBack.csr.addr
  outWriteBackInfo.csr.data      := activeReq.exuWriteBack.csr.data
  outWriteBackInfo.csr_ecallflag := activeReq.exuWriteBack.csr_ecallflag
  outWriteBackInfo.gpr.addr      := activeReq.exuWriteBack.gpr.addr
  outWriteBackInfo.gpr.en        := activeReq.exuWriteBack.gpr.en
  outWriteBackInfo.gpr.data      := activeReq.exuWriteBack.gpr.data
  outWriteBackInfo.isLoad        := activeReq.isLoad
  outWriteBackInfo.lsuResult     := lsuResult
  outWriteBackInfo.lsuFunc3t     := activeReq.func3t
  outWriteBackInfo.lsuAddrOffset := activeReq.destAddr(1, 0)
  outWriteBackInfo.iid           := activeReq.exuWriteBack.iid

  val isSRAMAddr = AddrSpace.inRng(in.destAddr, AddrSpace.SRAM)
  when(memIO.req_valid && memIO.req_ready && memIO.wen && isSRAMAddr) {
    ClockedCallVoidDPIC("sram_upd", Some(Seq("addr", "data", "mask")))(
      clock,
      isSRAMAddr,
      in.destAddr,
      memIO.wdata,
      memIO.wmask.pad(8)
    )
  }
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
