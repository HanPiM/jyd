package cpu

import chisel3._
import chisel3.util.{Cat, Decoupled, DecoupledIO, Enum, Fill, MuxLookup, Valid}

import chisel3.experimental.dataview._

import regfile._
import common_def._
import dpiwrap._
import busfsm._

import chisel3.util.circt.dpi._

class WriteBackInfo(implicit p:CPUParameters) extends Bundle {
  val gpr = GPRegReqIO.WriteTX
  val isLoad        = Bool()
  val isMemOp       = Bool()
  val lsuResult     = Types.UWord
  val lsuFunc3t    = UInt(3.W)
  val lsuAddrOffset = UInt(2.W)
  val memAddr       = Types.UWord
  val memWData      = Types.UWord
  val memWMask      = UInt(4.W)
  val dcacheEn      = Bool()
  val dcacheHit     = Bool()
  val dcacheStoreEpoch = UInt(8.W)
  val isStore       = Bool()

  val csr           = CSRegReqIO.TX.Write
  val csr_ecallflag = Bool()

  // val is_ebreak = Bool()
  // val skipDifftest = Bool()

  // val pc     = Types.UWord
  // val nxt_pc = Types.UWord

  val iid = Types.InstID
}

object ExtLoadData {
  def apply(rawData: UInt, addrOffset: UInt, func3t: UInt): UInt = {
    // val respLoadDataRaw = MuxLookup(addrOffset, 0.U(32.W))(
    //   Seq(
    //     0.U -> rawData,
    //     1.U -> rawData(15, 8).pad(32),
    //     2.U -> rawData(31, 16).pad(32),
    //     3.U -> rawData(31, 24).pad(32)
    //   )
    // )
    
    val respHalfWord = Mux(addrOffset(1), rawData(31, 16), rawData(15, 0))
    val respByte = MuxLookup(addrOffset, 0.U(8.W))(
      Seq(
        0.U -> rawData(7, 0),
        1.U -> rawData(15, 8),
        2.U -> rawData(23, 16),
        3.U -> rawData(31, 24)
      )
    )

    val respExtedByte = Cat(Fill(24, respByte(7) && (~func3t(2))), respByte)
    val respExtedHalf = Cat(Fill(16, respHalfWord(15) && (~func3t(2))), respHalfWord)
    Mux(func3t(1), rawData, Mux(func3t(0), respExtedHalf, respExtedByte))
  }
}

object ExtractFwdInfoFromWrBack {
  def apply(info: DecoupledIO[WriteBackInfo], memResp: Valid[UInt])(implicit p:CPUParameters): WrBackForwardInfo = {
    val wrBack = info.bits
    // val respLoadDataRaw = MuxLookup(wrBack.lsuAddrOffset, 0.U(32.W))(
    //   Seq(
    //     0.U -> memResp.bits,
    //     1.U -> memResp.bits(31, 8).pad(32),
    //     2.U -> memResp.bits(31, 16).pad(32),
    //     3.U -> memResp.bits(31, 24).pad(32)
    //   )
    // )
    // val respLoadByte = Cat(Fill(24, respLoadDataRaw(7) && (~wrBack.lsuFunc3t(2))), respLoadDataRaw(7, 0))
    // val respLoadHalf = Cat(Fill(16, respLoadDataRaw(15) && (~wrBack.lsuFunc3t(2))), respLoadDataRaw(15, 0))
    // val loadResult   = Mux(wrBack.lsuFunc3t(1), respLoadDataRaw, Mux(wrBack.lsuFunc3t(0), respLoadHalf, respLoadByte))

    val loadResult = ExtLoadData(memResp.bits, wrBack.lsuAddrOffset, wrBack.lsuFunc3t)
    val gprData      = Mux(wrBack.isLoad, loadResult, wrBack.gpr.data)

    val out = Wire(new WrBackForwardInfo)
    out.addr      := wrBack.gpr.addr
    out.enWr      := wrBack.gpr.en && info.valid
    out.dataVaild := info.valid && (!wrBack.isLoad || memResp.valid)
    out.data      := gprData

    out.enWrCSR := wrBack.csr.en && info.valid

    out
  }
}

class WBU(implicit p:CPUParameters) extends Module {
  val io = IO(new Bundle {
    val in       = Flipped(Decoupled(new WriteBackInfo))
    val memResp  = Flipped(Valid(Types.UWord))
    val gpr      = GPRegReqIO.WriteTX
    val csr      = CSRegReqIO.TX.Write
    val is_ecall = Output(Bool())
    val done     = Output(Bool())
    val dcacheUpdate = Output(Bool())
    val dcacheAddr   = Output(Types.UWord)
    val dcacheData   = Output(Types.UWord)
    val dcacheMask   = Output(UInt(4.W))
    val dcacheStoreEpoch = Input(UInt(8.W))
  })

  val wbinfo = io.in.bits
  val valid  = io.in.valid
  // val respLoadDataRaw = MuxLookup(wbinfo.lsuAddrOffset, 0.U(32.W))(
  //   Seq(
  //     0.U -> io.memResp.bits,
  //     1.U -> io.memResp.bits(31, 8).pad(32),
  //     2.U -> io.memResp.bits(31, 16).pad(32),
  //     3.U -> io.memResp.bits(31, 24).pad(32)
  //   )
  // )
  // val respLoadByte = Cat(Fill(24, respLoadDataRaw(7) && (~wbinfo.lsuFunc3t(2))), respLoadDataRaw(7, 0))
  // val respLoadHalf = Cat(Fill(16, respLoadDataRaw(15) && (~wbinfo.lsuFunc3t(2))), respLoadDataRaw(15, 0))
  // val loadResult   = Mux(wbinfo.lsuFunc3t(1), respLoadDataRaw, Mux(wbinfo.lsuFunc3t(0), respLoadHalf, respLoadByte))
  //

  val loadResult = ExtLoadData(io.memResp.bits, wbinfo.lsuAddrOffset, wbinfo.lsuFunc3t)

  io.in.ready := true.B

  when(valid && wbinfo.isMemOp) {
    assert(io.memResp.valid, "WBU memory response must be valid for memory operations")
  }
  when(valid && wbinfo.isLoad && wbinfo.dcacheEn && wbinfo.dcacheHit && io.memResp.valid) {
    assert(
      ExtLoadData(wbinfo.lsuResult, wbinfo.lsuAddrOffset, wbinfo.lsuFunc3t) === loadResult,
      p"DCache hit data mismatch: addr=${wbinfo.memAddr} cache=${wbinfo.lsuResult} mem=${io.memResp.bits}"
    )
  }

  io.gpr.en   := wbinfo.gpr.en && valid
  io.gpr.addr := wbinfo.gpr.addr
  io.gpr.data := Mux(wbinfo.isLoad, loadResult, wbinfo.gpr.data)

  io.csr.en   := wbinfo.csr.en && valid
  io.csr.addr := wbinfo.csr.addr
  io.csr.data := wbinfo.csr.data
  io.is_ecall := wbinfo.csr_ecallflag && valid

  io.done := valid

  // Sub-word and unaligned responses do not contain a complete cache line.
  val isAlignedWordLoad = wbinfo.lsuFunc3t(1) && wbinfo.lsuAddrOffset === 0.U
  val noYoungerStore = wbinfo.dcacheStoreEpoch === io.dcacheStoreEpoch
  val fillLoad = valid && wbinfo.dcacheEn && wbinfo.isLoad && !wbinfo.dcacheHit && isAlignedWordLoad &&
    noYoungerStore && io.memResp.valid
  io.dcacheUpdate := fillLoad
  io.dcacheAddr   := wbinfo.memAddr
  io.dcacheData   := io.memResp.bits
  io.dcacheMask   := "b1111".U

  StageLogger(
    clock,
    StageLogConst.Event.stage,
    StageLogConst.Stage.wbu,
    io.in.fire,
    wbinfo.iid
  )
  StageLogger(
    clock,
    StageLogConst.Event.retire,
    StageLogConst.Stage.wbu,
    io.in.fire,
    wbinfo.iid
  )

  dontTouch(io)
}

class DifftestWriteBackInfo extends Bundle {
  val pc= Types.UWord
  val nxtPC = Types.UWord
  val isEBreak = Bool()
  val needSkipRef = Bool()
}
class WBUForDifftest extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new DifftestWriteBackInfo))
  })
  val wbinfo = io.in.bits
  val valid  = io.in.valid
  io.in.ready := true.B

  val isEBreak = WireDefault(wbinfo.isEBreak && valid)
  dontTouch(isEBreak)
  when(isEBreak) {
    RawClockedVoidFunctionCall("raise_ebreak")(clock, isEBreak)
    // stop()
  }

  when(valid && wbinfo.needSkipRef) {
    RawClockedVoidFunctionCall("skip_difftest_ref")(clock, valid && wbinfo.needSkipRef)
  }

  when(valid && (!isEBreak)) {
    RawClockedVoidFunctionCall("pc_upd")(clock, valid && !isEBreak, wbinfo.pc, wbinfo.nxtPC)
  }
}
