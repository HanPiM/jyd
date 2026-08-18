package cpu

import chisel3._
import chisel3.util.{Cat, Decoupled, DecoupledIO, Enum, Fill, Mux1H, MuxLookup, PopCount, Valid}

import chisel3.experimental.dataview._

import regfile._
import common_def._
import dpiwrap._
import busfsm._

import chisel3.util.circt.dpi._

class ResultLane(implicit p: CPUParameters) extends Bundle {
  val valid = Bool()
  val rd    = p.GPRAddr
  val data  = Types.UWord
}

class FastResultLane(implicit p: CPUParameters) extends ResultLane
class DirectResultLane(implicit p: CPUParameters) extends ResultLane
class LongResultLane(implicit p: CPUParameters) extends ResultLane
class AcceleratorResultLane(implicit p: CPUParameters) extends ResultLane

class LoadResultLane(implicit p: CPUParameters) extends Bundle {
  val valid = Bool()
  val rd    = p.GPRAddr
}

class WriteBackInfo(implicit p:CPUParameters) extends Bundle {
  val resultKind    = ResultKind()
  val fastResult    = new FastResultLane
  val directResult  = new DirectResultLane
  val longResult    = new LongResultLane
  val acceleratorResult = new AcceleratorResultLane
  val loadResult    = new LoadResultLane
  val isLoad        = Bool()
  val isMemOp       = Bool()
  val lsuResult     = Types.UWord
  val lsuFunc3t    = UInt(3.W)
  val lsuAddrOffset = UInt(2.W)
  val memAddr       = Types.UWord
  val cacheableLoad = Bool()
  val dcacheHit     = Bool()
  val dcacheStoreEpoch = Bool()

  // val is_ebreak = Bool()
  // val skipDifftest = Bool()

  // val pc     = Types.UWord
  // val nxt_pc = Types.UWord

  val iid = Types.InstID
}

object ResultLaneSelect {
  def validVec(wrBack: WriteBackInfo): Seq[Bool] = Seq(
    wrBack.fastResult.valid,
    wrBack.directResult.valid,
    wrBack.longResult.valid,
    wrBack.acceleratorResult.valid,
    wrBack.loadResult.valid
  )

  def anyValid(wrBack: WriteBackInfo): Bool = validVec(wrBack).reduce(_ || _)

  // Every lane carries the instruction's same destination register. Keep lane
  // validity out of the address path; it is used only for the final write enable.
  def rd(wrBack: WriteBackInfo): UInt = wrBack.fastResult.rd

  def nonLoadData(wrBack: WriteBackInfo): UInt = Mux1H(
    Seq(
      wrBack.fastResult.valid -> wrBack.fastResult.data,
      wrBack.directResult.valid -> wrBack.directResult.data,
      wrBack.longResult.valid -> wrBack.longResult.data,
      wrBack.acceleratorResult.valid -> wrBack.acceleratorResult.data
    )
  )
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

    val out = Wire(new WrBackForwardInfo)
    out.addr      := ResultLaneSelect.rd(wrBack)
    out.enWr      := ResultLaneSelect.anyValid(wrBack) && info.valid
    val registeredLoadData = ExtLoadData(wrBack.lsuResult, wrBack.lsuAddrOffset, wrBack.lsuFunc3t)
    out.dataVaild := info.valid && (!wrBack.loadResult.valid || (wrBack.cacheableLoad && wrBack.dcacheHit))
    out.data      := Mux(wrBack.loadResult.valid, registeredLoadData, ResultLaneSelect.nonLoadData(wrBack))
    out.kind      := wrBack.resultKind

    out
  }
}

class WBU(implicit p:CPUParameters) extends Module {
  val io = IO(new Bundle {
    val in       = Flipped(Decoupled(new WriteBackInfo))
    val memResp  = Flipped(Valid(Types.UWord))
    val gpr      = GPRegReqIO.WriteTX
    val done     = Output(Bool())
    val dcacheUpdate = Output(Bool())
    val dcacheAddr   = Output(Types.UWord)
    val dcacheData   = Output(Types.UWord)
    val dcacheMask   = Output(UInt(4.W))
    val dcacheStoreEpoch = Input(Bool())
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
  val resultValid = ResultLaneSelect.anyValid(wbinfo)
  val selectedRd = ResultLaneSelect.rd(wbinfo)
  val selectedData = Mux(wbinfo.loadResult.valid, loadResult, ResultLaneSelect.nonLoadData(wbinfo))

  io.in.ready := true.B

  when(valid && wbinfo.isMemOp) {
    assert(io.memResp.valid, "WBU memory response must be valid for memory operations")
  }
  when(valid && wbinfo.cacheableLoad && wbinfo.dcacheHit && io.memResp.valid) {
    assert(
      wbinfo.lsuResult === io.memResp.bits,
      p"DCache hit data mismatch: addr=${wbinfo.memAddr} cache=${wbinfo.lsuResult} mem=${io.memResp.bits}"
    )
  }
  when(valid) {
    assert(PopCount(ResultLaneSelect.validVec(wbinfo)) <= 1.U, "writeback result lanes must be one-hot")
  }

  io.gpr.en   := resultValid && valid
  io.gpr.addr := selectedRd
  io.gpr.data := selectedData

  io.done := valid

  val noYoungerStore = wbinfo.dcacheStoreEpoch === io.dcacheStoreEpoch
  val fillLoad = valid && wbinfo.cacheableLoad && !wbinfo.dcacheHit && noYoungerStore
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
  val code = Types.UWord
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
  when(valid) {
    RawClockedVoidFunctionCall("retire_inst")(clock, valid, wbinfo.code)
  }
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
