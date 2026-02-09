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

class TopIO extends Bundle {
  val interrupt = Input(Bool())
  val master    = AXI4IO.Master
  val slave     = AXI4IO.Slave
}

class ysyx_25100261(word_width: Int = 32) extends Module {
  // val isRdAfterWr = Wire(Bool())
  val isBranchGuessWrong  = Wire(Bool())
  val curCorrectJmpTarget = Reg(UInt(32.W))

  val isIDUWaitEXUReg = RegInit(false.B)
  val isIDUWaitEXU    = Wire(Bool())
  val isFlushIDUReg   = RegInit(false.B)
  val isFlushIDU      = Wire(Bool())

  def pipelineConnect[T <: Data, T2 <: Data](
    prevOut:    DecoupledIO[T],
    thisIn:     DecoupledIO[T],
    thisOut:    DecoupledIO[T2],
    isIDUtoEXU: Boolean = false,
    isIFUtoIDU: Boolean = false
  ) = {
    // prevOut <> thisIn
    val thisInReady = if (isIDUtoEXU) {
      thisIn.ready && (!isIDUWaitEXU)
    } else {
      thisIn.ready
    }
    prevOut.ready := thisInReady

    thisIn.bits := RegEnable(prevOut.bits, prevOut.fire)

    val isThisBusy   = RegInit(false.B)
    val normalNxtBsy = Mux(isThisBusy, (!thisOut.fire) || (prevOut.fire), prevOut.fire)

    if (isIDUtoEXU) {
      isThisBusy := normalNxtBsy && (!isFlushIDU)
    } else if (isIFUtoIDU) {
      isThisBusy := normalNxtBsy && (!isFlushIDU)
    } else {
      isThisBusy := normalNxtBsy
    }

    if (isIDUtoEXU) {
      thisIn.valid := isThisBusy && (!isIDUWaitEXU) && (!isFlushIDU)
    } else if (isIFUtoIDU) {
      thisIn.valid := isThisBusy && (!isFlushIDU)
    } else {
      thisIn.valid := isThisBusy
    }
  }
  def conflict(rs: UInt, rd: GPRegReqIO._WriteRX) = (rs === rd.addr) && (rd.addr =/= 0.U) && rd.en
  def conflictWithStage[T <: HasRs](info: T, gprWr: GPRegReqIO._WriteRX, valid: Bool) = {
    WireDefault(valid && (conflict(info.rs1, gprWr) || conflict(info.rs2, gprWr)))
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
  isBranchGuessWrong := isBranchGuessWrongReg
  when(exu.io.out.valid) {
    isBranchGuessWrongReg := exu.io.jmpHappen// && exu.io.out.bits.exuWriteBack.nxt_pc =/= exu.io.out.bits.exuWriteBack.pc + 4.U
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

  when(isBranchGuessWrong) {
    isFlushIDUReg := true.B
  }.elsewhen(isIDUMeetCorrectJmpTarget) {
    isFlushIDUReg := false.B
  }
  isFlushIDU := (isFlushIDUReg & (!isIDUMeetCorrectJmpTarget)) || isBranchGuessWrong
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
          ("h02000000".U(32.W), "h0200ffff".U(32.W)) -> clint.io,
          ("h0f000000".U(32.W), "hffffffff".U(32.W)) -> otherReqSlave
        )
      )
    )
  } else {
    val uart = Module(new UARTUnit)
    val mem  = Module(new AXI4MemUnit)

    val MEM_BASE = "h80000000".U(32.W)
    val MEM_END  = "h8FFFFFFF".U(32.W)

    val SERIAL_BASE = "h10000000".U(32.W)
    val SERIAL_END  = "h10001000".U(32.W)
    otherReqSlave := DontCare
    Module(
      new AXI4LiteXBar(
        Seq(
          ("h02000000".U(32.W), "h0200ffff".U(32.W)) -> clint.io,
          (MEM_BASE, MEM_END)                        -> mem.io,
          (SERIAL_BASE, SERIAL_END)                  -> uart.io
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
  val SERIAL_ADDR_BASE = "h10000000".U(32.W)
  val SERIAL_ADDR_END  = "h10001000".U(32.W)
  val SPI_ADDR_BASE    = "h10001000".U(32.W)
  val SPI_ADDR_END     = "h10002000".U(32.W)

  def inRng(beg: UInt, end: UInt, addr: UInt): Bool = {
    (addr >= beg) && (addr < end)
  }

  val wNeedSkip = inRng(SERIAL_ADDR_BASE, SERIAL_ADDR_END, io.master.awaddr) ||
    inRng(SPI_ADDR_BASE, SPI_ADDR_END, io.master.awaddr)
  val rNeedSkip = inRng(SERIAL_ADDR_BASE, SERIAL_ADDR_END, io.master.araddr) ||
    inRng(SPI_ADDR_BASE, SPI_ADDR_END, io.master.araddr)

  when(io.master.awvalid && io.master.awready && wNeedSkip) {
    RawClockedVoidFunctionCall("skip_difftest_ref")(
      clock,
      true.B
    )
  }
  when(io.master.arvalid && io.master.arready && rNeedSkip) {
    RawClockedVoidFunctionCall("skip_difftest_ref")(
      clock,
      true.B
    )
  }

  AXI4IO.connectMasterSlave(memArbiter.io.out, memXBar.io.in)
  memXBar.connect()

  ifu.io.pc.bits  := pc
  ifu.io.pc.valid := true.B

  val isConflictWithEXU = conflictWithStage(
    idu.io.out.bits.info,
    // exu.io.out.bits.exuWriteBack.gpr,
    // exu.io.out.valid
    lsu.io.in.bits.exuWriteBack.gpr,
    lsu.io.in.valid
  )
  val isConflictWithLSU = conflictWithStage(
    idu.io.out.bits.info,
    lsu.io.out.bits.gpr,
    lsu.io.out.valid
  )
  val isConflictWithWBU = conflictWithStage(
    idu.io.out.bits.info,
    wbu.io.in.bits.gpr,
    wbu.io.in.valid
  )
  dontTouch(isConflictWithEXU)
  dontTouch(isConflictWithLSU)
  dontTouch(isConflictWithWBU)

  val isRdAfterWr = Wire(Bool())
  isRdAfterWr := isConflictWithEXU || isConflictWithLSU || isConflictWithWBU
  dontTouch(isRdAfterWr)

  when(isRdAfterWr) {
    isIDUWaitEXUReg := true.B
  }.elsewhen(wbu.io.done) {
    isIDUWaitEXUReg := false.B
  }
  isIDUWaitEXU := isRdAfterWr
  dontTouch(isIDUWaitEXU)

  pipelineConnect(ifu.io.out, idu.io.in, idu.io.out, isIFUtoIDU = true)
  pipelineConnect(idu.io.out, exu.io.in, exu.io.out, isIDUtoEXU = true)
  pipelineConnect(exu.io.out, lsu.io.in, lsu.io.out)

  exu.io.rvec <> gprs.io.read
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
