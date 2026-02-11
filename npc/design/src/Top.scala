package top

import chisel3._

import regfile._

import cpu._

import chisel3.util.circt.dpi._
import chisel3.util._

import axi4._
import uart._
import clint._
import xbar._

import npcMem._

import icache._
import common_def.{HasRs, InstType}
import common_def.AddrSpace

class TopIO extends Bundle {
  val interrupt = Input(Bool())
  val master    = AXI4IO.Master
  val slave     = AXI4IO.Slave
}

class ysyx_25100261(word_width: Int = 32) extends Module {
  val isBranchGuessWrong  = Wire(Bool())
  val curCorrectJmpTarget = Reg(UInt(32.W))

  val isIDUStall    = Wire(Bool())
  val isFlushIDUReg = RegInit(false.B)
  val isFlushIDU    = Wire(Bool())

  def pipelineConnect[T <: Data, T2 <: Data](
    prevOut:    DecoupledIO[T],
    thisIn:     DecoupledIO[T],
    thisOut:    DecoupledIO[T2],
    isIDUtoEXU: Boolean = false,
    isIFUtoIDU: Boolean = false,
    isEXUtoLSU: Boolean = false
  ) = {

    // prevOut <> thisIn
    val thisInReady = if (isIDUtoEXU) {
      thisIn.ready && (!isIDUStall)
    } else {
      thisIn.ready
    }

    val dataValid   = RegInit(false.B)
    val readyToPrev = (!dataValid) || thisInReady

    if (isIDUtoEXU) {

      val clearDataValid = isFlushIDU || isIDUStall

      when(readyToPrev) {
        dataValid := prevOut.valid && (!clearDataValid)
      }.elsewhen(thisOut.fire) {
        dataValid := false.B
      }
      prevOut.ready := readyToPrev && (!isIDUStall)
    } else {

      when(readyToPrev) {
        dataValid := prevOut.valid
      }
      if (isIFUtoIDU) {
        prevOut.ready := readyToPrev || isFlushIDU
      } else {
        prevOut.ready := readyToPrev
      }
    }

    thisIn.bits  := RegEnable(prevOut.bits, prevOut.fire)
    thisIn.valid := dataValid
  }
  def conflict(rs: UInt, rd: UInt, en: Bool) = (rs === rd) && (rd =/= 0.U) && en
  def conflictWithStage(rs1: UInt, rs2: UInt, gprWr: GPRegReqIO._WriteRX, valid: Bool) = {
    WireDefault(valid && (conflict(rs1, gprWr.addr, gprWr.en) || conflict(rs2, gprWr.addr, gprWr.en)))
  }
  def conflictWithStage[T <: HasRs](info: T, gprWr: GPRegReqIO._WriteRX, valid: Bool): Bool = {
    conflictWithStage(info.rs1, info.rs2, gprWr, valid)
  }

  val io = IO(new TopIO)
  dontTouch(io)
  io := DontCare

  val gprs = Module(new RegisterFile(READ_PORTS = 2))
  val csrs = Module(new ControlStatusRegisterFile())

  val ifu = Module(new IFU)
  val idu = Module(new IDU)
  val exu = Module(new EXU)
  val lsu = Module(new LSU)
  val wbu = Module(new WBU)

  val isSoC = sys.env.getOrElse("ARCH", "") == "riscv32e-ysyxsoc"

  if (isSoC) {
    println("ARCH is SoC npc : INIT_PC = 0x30000000\n")
  } else {
    println("ARCH is normal npc : INIT_PC = 0x80000000\n")
  }

  val INIT_PC = if (isSoC) "h30000000".U(32.W) else "h80000000".U(32.W)

  val pc = RegInit(INIT_PC)

  val isBranchGuessWrongReg     = RegInit(false.B)
  val isIFUMeetCorrectJmpTarget = Wire(Bool())
  isBranchGuessWrong := isBranchGuessWrongReg || (exu.io.out.valid && exu.io.jmpHappen)
  when(exu.io.out.valid) {
    isBranchGuessWrongReg := exu.io.jmpHappen // && exu.io.out.bits.exuWriteBack.nxt_pc =/= exu.io.out.bits.exuWriteBack.pc + 4.U
  }.elsewhen(isIFUMeetCorrectJmpTarget) {
    isBranchGuessWrongReg := false.B
  }

  dontTouch(isBranchGuessWrong)
  when(exu.io.out.valid) {
    curCorrectJmpTarget := exu.io.out.bits.exuWriteBack.nxt_pc
  }

  isIFUMeetCorrectJmpTarget := ifu.io.pc.valid && (ifu.io.pc.bits === curCorrectJmpTarget)

  val isIDUMeetCorrectJmpTarget = Wire(Bool())
  isIDUMeetCorrectJmpTarget := ifu.io.out.valid && (ifu.io.out.bits.pc === curCorrectJmpTarget)
  dontTouch(isIFUMeetCorrectJmpTarget)
  dontTouch(isIDUMeetCorrectJmpTarget)
  dontTouch(curCorrectJmpTarget)

  when(isBranchGuessWrong && (!isIFUMeetCorrectJmpTarget)) {
    isFlushIDUReg := true.B
  }.elsewhen(isIDUMeetCorrectJmpTarget) {
    isFlushIDUReg := false.B
  }
  // & (!isIDUMeetCorrectJmpTarget)
  isFlushIDU := (isFlushIDUReg) || isBranchGuessWrong
  dontTouch(isFlushIDU)

  // pc := Mux(wbu.io.done, nxt_pc, pc)
  pc := Mux(
    ifu.io.pc.ready,
    // Sometimes although jump target is near current pc and IFU just meets it
    Mux(isBranchGuessWrong && (!isIFUMeetCorrectJmpTarget), curCorrectJmpTarget, pc + 4.U),
    pc
  )

  dontTouch(exu.io)

  val memArbiter = Module(new EXUIFU_MemVisitArbiter)
  AXI4IO.connectMasterSlave(lsu.io.mem, memArbiter.io.exu)

  val icache = Module(new ICache)
  icache.io.flush := idu.io.out.valid && idu.io.out.bits.info.typ === InstType.fencei
  AXI4IO.connectMasterSlave(ifu.io.mem, icache.io.cpu)
  AXI4IO.connectMasterSlave(icache.io.mem, memArbiter.io.ifu)

  // AXI4IO.connectMasterSlave(ifu.io.mem, memArbiter.io.ifu)

  val clint = Module(new CLINTUnit)

  val otherReqSlave = Wire(AXI4IO.Slave)
  val memXBar       = if (isSoC) {
    AXI4IO.transformSlaveToMasterValidIf(!reset.asBool)(io.master, otherReqSlave)
    Module(
      new AXI4LiteXBar(
        Seq(
          AddrSpace.CLINT -> clint.io,
          AddrSpace.SOC   -> otherReqSlave
        )
      )
    )
  } else {
    val uart = Module(new UARTUnit)
    val mem  = Module(new AXI4MemUnit)

    otherReqSlave := DontCare
    Module(
      new AXI4LiteXBar(
        Seq(
          AddrSpace.CLINT  -> clint.io,
          AddrSpace.NPCMEM -> mem.io,
          AddrSpace.SERIAL -> uart.io
        )
      )
    )
  }

  when(io.master.bvalid && io.master.bresp === AXI4IO.BResp.DECERR) {
    printf("AXI4 DECERR on write address 0x%x\n", io.master.awaddr)
    stop()
    stop()
  }
  when(io.master.rvalid && io.master.rresp === AXI4IO.RResp.DECERR) {
    printf("AXI4 DECERR on read address 0x%x\n", io.master.araddr)
    stop()
    stop()
  }

  AXI4IO.connectMasterSlave(memArbiter.io.out, memXBar.io.in)
  memXBar.connect()

  ifu.io.pc.bits  := pc
  ifu.io.pc.valid := true.B

  // val isConflictWithEXU    = conflictWithStage(
  //   idu.io.out.bits.info,
  //   exu.io.out.bits.exuWriteBack.gpr,
  //   exu.io.out.valid
  // )
  val isConflictWithEXU    = Wire(Bool())
  val isRs1ConflictWithEXU = conflict(
    idu.io.out.bits.info.rs1,
    exu.io.out.bits.exuWriteBack.gpr.addr,
    exu.io.out.bits.exuWriteBack.gpr.en
  ) && exu.io.out.valid
  val isRs2ConflictWithEXU = conflict(
    idu.io.out.bits.info.rs2,
    exu.io.out.bits.exuWriteBack.gpr.addr,
    exu.io.out.bits.exuWriteBack.gpr.en
  ) && exu.io.out.valid
  isConflictWithEXU := (isRs1ConflictWithEXU || isRs2ConflictWithEXU)

  val isConflictWithLSUIn  = conflictWithStage(
    idu.io.out.bits.info,
    lsu.io.in.bits.exuWriteBack.gpr,
    lsu.io.in.valid
  )
  val isConflictWithLSUOut = conflictWithStage(
    idu.io.out.bits.info,
    lsu.io.out.bits.gpr,
    lsu.io.out.valid
  )
  val isConflictWithLSU    = isConflictWithLSUIn || isConflictWithLSUOut
  val isConflictWithWBU    = Wire(Bool())
  val isRs1ConflictWithWBU = conflict(
    idu.io.out.bits.info.rs1,
    wbu.io.in.bits.gpr.addr,
    wbu.io.in.bits.gpr.en
  ) && wbu.io.in.valid
  val isRs2ConflictWithWBU = conflict(
    idu.io.out.bits.info.rs2,
    wbu.io.in.bits.gpr.addr,
    wbu.io.in.bits.gpr.en
  ) && wbu.io.in.valid
  isConflictWithWBU := (isRs1ConflictWithWBU || isRs2ConflictWithWBU)
  // val isConflictWithWBU    = conflictWithStage(
  //   idu.io.out.bits.info,
  //   wbu.io.in.bits.gpr,
  //   wbu.io.in.valid
  // )

  dontTouch(isConflictWithEXU)
  dontTouch(isConflictWithLSUIn)
  dontTouch(isConflictWithLSUOut)
  dontTouch(isConflictWithLSU)
  dontTouch(isConflictWithWBU)

  val isRdAfterWr = Wire(Bool())
  isRdAfterWr := isConflictWithEXU || isConflictWithLSU || isConflictWithWBU
  dontTouch(isRdAfterWr)

  val exuWrBackDataVaild = !(exu.io.out.bits.isLoad || exu.io.out.bits.isStore)

  val canRs1Bypass =
    Mux(isRs1ConflictWithEXU, exuWrBackDataVaild, isRs1ConflictWithWBU)
  val canRs2Bypass =
    Mux(isRs2ConflictWithEXU, exuWrBackDataVaild, isRs2ConflictWithWBU)

  val needStallForRs1 = (isRs1ConflictWithEXU || isRs1ConflictWithWBU) && (!canRs1Bypass)
  val needStallForRs2 = (isRs2ConflictWithEXU || isRs2ConflictWithWBU) && (!canRs2Bypass)

  val needStall = needStallForRs1 || needStallForRs2 || isConflictWithLSU

  // isIDUStall := isRdAfterWr
  isIDUStall          := needStall
  idu.io.r1UseBypass  := canRs1Bypass
  idu.io.r2UseBypass  := canRs2Bypass
  idu.io.r1BypassData := Mux(isRs1ConflictWithEXU, exu.io.out.bits.exuWriteBack.gpr.data, wbu.io.in.bits.gpr.data)
  idu.io.r2BypassData := Mux(isRs2ConflictWithEXU, exu.io.out.bits.exuWriteBack.gpr.data, wbu.io.in.bits.gpr.data)

  dontTouch(isIDUStall)

  pipelineConnect(ifu.io.out, idu.io.in, idu.io.out, isIFUtoIDU = true)
  pipelineConnect(idu.io.out, exu.io.in, exu.io.out, isIDUtoEXU = true)
  pipelineConnect(exu.io.out, lsu.io.in, lsu.io.out)

  idu.io.rvec <> gprs.io.read
  exu.io.csr_rvec <> csrs.io.read

  // Write back

  val foo = Wire(Decoupled(Bool()))
  foo       := DontCare
  foo.ready := true.B
  foo.valid := true.B
  pipelineConnect(lsu.io.out, wbu.io.in, foo)

  // wbu.io.in <> exu.io.out
  gprs.io.write <> wbu.io.gpr
  csrs.io.write <> wbu.io.csr
  csrs.io.is_ecall := wbu.io.is_ecall

}
