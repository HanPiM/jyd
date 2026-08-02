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
    val inst = Input(UInt(32.W))
    val instValid = Input(Bool())
    val actualNeedStall = Input(Bool())
    val bypassNeedStall = Input(Bool())
    val reg1AddImmEXUStall = Input(Bool())
    val reg1AddImmWBUStall = Input(Bool())
    val needReg1AddImm = Input(Bool())
    val lateLoadProducer = Input(new LateLoadProducerInfo)
    val dcacheFwd = Input(new DCacheForwardInfo)

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

    val actualStall = Output(Bool())
    val actualBypassStall = Output(Bool())
    val actualReg1AddImmEXUStall = Output(Bool())
    val actualReg1AddImmWBUStall = Output(Bool())
    val stalledInst = Output(UInt(32.W))
    val lateLoadAddrCandidate = Output(Bool())
    val lateLoadAddrHit = Output(Bool())
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

  io.actualStall := io.instValid && io.actualNeedStall
  io.actualBypassStall := io.instValid && io.bypassNeedStall
  io.actualReg1AddImmEXUStall := io.instValid && io.reg1AddImmEXUStall
  io.actualReg1AddImmWBUStall := io.instValid && io.reg1AddImmWBUStall
  io.stalledInst := io.inst
  val addrExuConflict = hasConflict(io.rs1, io.wrBackInfo.exu)
  io.lateLoadAddrCandidate :=
    io.instValid && io.needReg1AddImm && addrExuConflict && io.lateLoadProducer.valid
  io.lateLoadAddrHit :=
    io.instValid && io.needReg1AddImm && !addrExuConflict &&
      SingleByPassMux.conflict(io.rs1, io.dcacheFwd.addr, io.dcacheFwd.valid)

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
    io.actualStall,
    io.actualBypassStall,
    io.actualReg1AddImmEXUStall,
    io.actualReg1AddImmWBUStall,
    io.stalledInst,
    io.lateLoadAddrCandidate,
    io.lateLoadAddrHit
  ).foreach(dontTouch(_))
}
