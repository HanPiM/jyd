package cpu

import chisel3._
import chisel3.layer.{Layer, LayerConfig}

import common_def._

object PerfCounterLayer extends Layer(LayerConfig.Extract())

class RAWStallPerfTap(
  implicit p: CPUParameters)
    extends Module {
  override def desiredName: String = "RAWStallPerfTap"

  val io = IO(new Bundle {
    val rs1 = Input(p.GPRAddr)
    val rs2 = Input(p.GPRAddr)

    val wrBackInfo = Input(new WrBackInfoGroup)
    val instValid   = Input(Bool())
    val instFire    = Input(Bool())
    val pipelineFlush = Input(Bool())
    val finalNeedStall = Input(Bool())
    val stallEXU = Input(Bool())
    val stallLSU = Input(Bool())
    val stallWBU = Input(Bool())
    val stallAGENEXU = Input(Bool())
    val stallAGENWBU = Input(Bool())
    val stallCSR = Input(Bool())

    val dcacheLoadValid = Input(Bool())
    val dcacheLoadHit   = Input(Bool())
    val dcacheImmediateRs1 = Input(Bool())
    val dcacheImmediateRs2 = Input(Bool())
    val dcacheAddressConsumer = Input(Bool())
    val isMul    = Input(Bool())
    val isMulh   = Input(Bool())
    val isMulhu  = Input(Bool())
    val isMulhsu = Input(Bool())

    val isConflictEXU = Output(Bool())
    val isConflictLSU = Output(Bool())
    val isConflictWBU = Output(Bool())
    val isAnyConflict = Output(Bool())

    val isConflictOnlyEXU = Output(Bool())
    val isConflictOnlyLSU = Output(Bool())
    val isConflictOnlyWBU = Output(Bool())

    val isNeedStallEXU = Output(Bool())
    val isNeedStallLSU = Output(Bool())
    val isNeedStallWBU = Output(Bool())
    val isAnyStall     = Output(Bool())

    val isNeedStallOnlyEXU = Output(Bool())
    val isNeedStallOnlyLSU = Output(Bool())
    val isNeedStallOnlyWBU = Output(Bool())

    val countEnable = Output(Bool())
    val finalStallEXU = Output(Bool())
    val finalStallAny = Output(Bool())
    val finalStallLSU = Output(Bool())
    val finalStallWBU = Output(Bool())
    val finalStallAGENEXU = Output(Bool())
    val finalStallAGENWBU = Output(Bool())
    val finalStallCSR = Output(Bool())
    val finalStallOther = Output(Bool())

    val cacheableLoad = Output(Bool())
    val cacheableLoadHit = Output(Bool())
    val cacheableLoadMiss = Output(Bool())
    val immediateRs1 = Output(Bool())
    val immediateRs2 = Output(Bool())
    val addressConsumer = Output(Bool())
    val mul = Output(Bool())
    val mulh = Output(Bool())
    val mulhu = Output(Bool())
    val mulhsu = Output(Bool())
    val mulIssued = Output(Bool())
    val mulhIssued = Output(Bool())
    val mulhuIssued = Output(Bool())
    val mulhsuIssued = Output(Bool())
  })

  private def hasConflict(rs: UInt, wrBack: WrBackForwardInfo): Bool =
    SingleByPassMux.conflict(rs, wrBack.addr, wrBack.enWr)

  private def hasAnyConflict(wrBack: WrBackForwardInfo): Bool =
    hasConflict(io.rs1, wrBack) || hasConflict(io.rs2, wrBack)

  private def needStallFrom(wrBack: WrBackForwardInfo): Bool =
    hasAnyConflict(wrBack) && !wrBack.dataVaild

  io.isConflictEXU := hasAnyConflict(io.wrBackInfo.exu)
  io.isConflictLSU := hasAnyConflict(io.wrBackInfo.lsu)
  io.isConflictWBU := hasAnyConflict(io.wrBackInfo.wbu)
  io.isAnyConflict := io.isConflictEXU || io.isConflictLSU || io.isConflictWBU

  io.isConflictOnlyEXU := io.isConflictEXU && !io.isConflictLSU && !io.isConflictWBU
  io.isConflictOnlyLSU := io.isConflictLSU && !io.isConflictEXU && !io.isConflictWBU
  io.isConflictOnlyWBU := io.isConflictWBU && !io.isConflictEXU && !io.isConflictLSU

  io.isNeedStallEXU := needStallFrom(io.wrBackInfo.exu)
  io.isNeedStallLSU := needStallFrom(io.wrBackInfo.lsu)
  io.isNeedStallWBU := needStallFrom(io.wrBackInfo.wbu)
  io.isAnyStall     := io.isNeedStallEXU || io.isNeedStallLSU || io.isNeedStallWBU

  io.isNeedStallOnlyEXU := io.isNeedStallEXU && !io.isNeedStallLSU && !io.isNeedStallWBU
  io.isNeedStallOnlyLSU := io.isNeedStallLSU && !io.isNeedStallEXU && !io.isNeedStallWBU
  io.isNeedStallOnlyWBU := io.isNeedStallWBU && !io.isNeedStallEXU && !io.isNeedStallLSU

  io.countEnable := io.instValid && !io.pipelineFlush
  val qualifiedStall = io.countEnable && io.finalNeedStall
  io.finalStallAny := qualifiedStall
  // A fixed priority makes these final-stall reason counters mutually exclusive.
  io.finalStallCSR     := qualifiedStall && io.stallCSR
  io.finalStallAGENEXU := qualifiedStall && !io.stallCSR && io.stallAGENEXU
  io.finalStallAGENWBU := qualifiedStall && !io.stallCSR && !io.stallAGENEXU && io.stallAGENWBU
  io.finalStallEXU := qualifiedStall && !io.stallCSR && !io.stallAGENEXU && !io.stallAGENWBU && io.stallEXU
  io.finalStallLSU := qualifiedStall && !io.stallCSR && !io.stallAGENEXU && !io.stallAGENWBU && !io.stallEXU && io.stallLSU
  io.finalStallWBU := qualifiedStall && !io.stallCSR && !io.stallAGENEXU && !io.stallAGENWBU && !io.stallEXU &&
    !io.stallLSU && io.stallWBU
  io.finalStallOther := qualifiedStall && !(io.finalStallCSR || io.finalStallAGENEXU || io.finalStallAGENWBU ||
    io.finalStallEXU || io.finalStallLSU || io.finalStallWBU)

  io.cacheableLoad     := io.dcacheLoadValid
  io.cacheableLoadHit  := io.dcacheLoadValid && io.dcacheLoadHit
  io.cacheableLoadMiss := io.dcacheLoadValid && !io.dcacheLoadHit
  io.immediateRs1      := io.countEnable && io.dcacheImmediateRs1
  io.immediateRs2      := io.countEnable && io.dcacheImmediateRs2
  io.addressConsumer   := io.countEnable && io.dcacheAddressConsumer
  io.mul               := io.countEnable && io.isMul
  io.mulh              := io.countEnable && io.isMulh
  io.mulhu             := io.countEnable && io.isMulhu
  io.mulhsu            := io.countEnable && io.isMulhsu
  val issueEnable = io.instFire && !io.pipelineFlush
  io.mulIssued   := issueEnable && io.isMul
  io.mulhIssued  := issueEnable && io.isMulh
  io.mulhuIssued := issueEnable && io.isMulhu
  io.mulhsuIssued := issueEnable && io.isMulhsu

  Seq(
    io.isConflictEXU,
    io.isConflictLSU,
    io.isConflictWBU,
    io.isAnyConflict,
    io.isConflictOnlyEXU,
    io.isConflictOnlyLSU,
    io.isConflictOnlyWBU,
    io.isNeedStallEXU,
    io.isNeedStallLSU,
    io.isNeedStallWBU,
    io.isAnyStall,
    io.isNeedStallOnlyEXU,
    io.isNeedStallOnlyLSU,
    io.isNeedStallOnlyWBU,
    io.countEnable,
    io.finalStallEXU,
    io.finalStallAny,
    io.finalStallLSU,
    io.finalStallWBU,
    io.finalStallAGENEXU,
    io.finalStallAGENWBU,
    io.finalStallCSR,
    io.finalStallOther,
    io.cacheableLoad,
    io.cacheableLoadHit,
    io.cacheableLoadMiss,
    io.immediateRs1,
    io.immediateRs2,
    io.addressConsumer,
    io.mul,
    io.mulh,
    io.mulhu,
    io.mulhsu,
    io.mulIssued,
    io.mulhIssued,
    io.mulhuIssued,
    io.mulhsuIssued
  ).foreach(dontTouch(_))
}
