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
    io.isNeedStallOnlyWBU
  ).foreach(dontTouch(_))
}
