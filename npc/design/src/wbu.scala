package cpu

import chisel3._
import chisel3.util.{Cat, Decoupled, DecoupledIO, Enum, Fill, MuxLookup}

import chisel3.experimental.dataview._

import regfile._
import common_def._
import dpiwrap._
import busfsm._

import chisel3.util.circt.dpi._

class WriteBackInfo(implicit p:CPUParameters) extends Bundle {
  val gpr = GPRegReqIO.WriteTX
  val isLoad       = Bool()
  val lsuResult    = Types.UWord
  val lsuFunc3t    = UInt(3.W)
  val lsuAddrOffset = UInt(2.W)

  val csr           = CSRegReqIO.TX.Write
  val csr_ecallflag = Bool()

  // val is_ebreak = Bool()
  // val skipDifftest = Bool()

  // val pc     = Types.UWord
  // val nxt_pc = Types.UWord

  val iid = Types.InstID
}

object ExtractFwdInfoFromWrBack {
  def apply(info: DecoupledIO[WriteBackInfo])(implicit p:CPUParameters): WrBackForwardInfo = {
    val wrBack = info.bits
    val respLoadDataRaw = MuxLookup(wrBack.lsuAddrOffset, 0.U(32.W))(
      Seq(
        0.U -> wrBack.lsuResult,
        1.U -> wrBack.lsuResult(31, 8).pad(32),
        2.U -> wrBack.lsuResult(31, 16).pad(32),
        3.U -> wrBack.lsuResult(31, 24).pad(32)
      )
    )
    val respLoadByte = Cat(Fill(24, respLoadDataRaw(7) && (~wrBack.lsuFunc3t(2))), respLoadDataRaw(7, 0))
    val respLoadHalf = Cat(Fill(16, respLoadDataRaw(15) && (~wrBack.lsuFunc3t(2))), respLoadDataRaw(15, 0))
    val loadResult   = Mux(wrBack.lsuFunc3t(1), respLoadDataRaw, Mux(wrBack.lsuFunc3t(0), respLoadHalf, respLoadByte))
    val gprData      = Mux(wrBack.isLoad, loadResult, wrBack.gpr.data)

    val out = Wire(new WrBackForwardInfo)
    out.addr      := wrBack.gpr.addr
    out.enWr      := wrBack.gpr.en && info.valid
    out.dataVaild := info.valid
    out.data      := gprData
    out
  }
}

class WBU(implicit p:CPUParameters) extends Module {
  val io = IO(new Bundle {
    val in       = Flipped(Decoupled(new WriteBackInfo))
    val gpr      = GPRegReqIO.WriteTX
    val csr      = CSRegReqIO.TX.Write
    val is_ecall = Output(Bool())
    val done     = Output(Bool())
  })

  val wbinfo = io.in.bits
  val valid  = io.in.valid
  val respLoadDataRaw = MuxLookup(wbinfo.lsuAddrOffset, 0.U(32.W))(
    Seq(
      0.U -> wbinfo.lsuResult,
      1.U -> wbinfo.lsuResult(31, 8).pad(32),
      2.U -> wbinfo.lsuResult(31, 16).pad(32),
      3.U -> wbinfo.lsuResult(31, 24).pad(32)
    )
  )
  val respLoadByte = Cat(Fill(24, respLoadDataRaw(7) && (~wbinfo.lsuFunc3t(2))), respLoadDataRaw(7, 0))
  val respLoadHalf = Cat(Fill(16, respLoadDataRaw(15) && (~wbinfo.lsuFunc3t(2))), respLoadDataRaw(15, 0))
  val loadResult   = Mux(wbinfo.lsuFunc3t(1), respLoadDataRaw, Mux(wbinfo.lsuFunc3t(0), respLoadHalf, respLoadByte))

  io.in.ready := true.B

  io.gpr.en   := wbinfo.gpr.en && valid
  io.gpr.addr := wbinfo.gpr.addr
  io.gpr.data := Mux(wbinfo.isLoad, loadResult, wbinfo.gpr.data)

  io.csr.en   := wbinfo.csr.en && valid
  io.csr.addr := wbinfo.csr.addr
  io.csr.data := wbinfo.csr.data
  io.is_ecall := wbinfo.csr_ecallflag && valid

  io.done := valid

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
