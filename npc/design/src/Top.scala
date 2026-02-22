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
import common_def._

import btb._
import branchpredictor._

class TopIO extends Bundle {
  val interrupt = Input(Bool())
  val master    = AXI4IO.Master
  val slave     = AXI4IO.Slave
}

class ysyx_25100261(word_width: Int = 32) extends Module {
  val isBranchGuessWrong = Wire(Bool())

  // val isIDUStall    = Wire(Bool())
  val isFlushIDUReg = RegInit(false.B)
  val isFlushIDU    = Wire(Bool())

  def pipelineConnect[T <: Data, T2 <: Data](
    prevOut: DecoupledIO[T],
    thisIn:  DecoupledIO[T],
    thisOut: DecoupledIO[T2]
  ) = {

    val thisInReady = thisIn.ready

    val dataValid   = RegInit(false.B)
    val readyToPrev = (!dataValid) || thisInReady

    when(readyToPrev) {
      dataValid := prevOut.valid
    }
    prevOut.ready := readyToPrev

    thisIn.bits  := RegEnable(prevOut.bits, prevOut.fire)
    thisIn.valid := dataValid
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

  // val btb = Module(new BranchTargetBuffer)
  // val bp  = Module(new BranchPredictor)

  val isSoC = sys.env.getOrElse("ARCH", "") == "riscv32e-ysyxsoc"

  if (isSoC) {
    println("ARCH is SoC npc : INIT_PC = 0x30000000\n")
  } else {
    println("ARCH is normal npc : INIT_PC = 0x80000000\n")
  }

  val INIT_PC = if (isSoC) "h30000000".U(32.W) else "h80000000".U(32.W)

  val pc = RegInit(INIT_PC)

  val nxtPredictedPC = Wire(Types.UWord)
  dontTouch(nxtPredictedPC)
  nxtPredictedPC         := ifu.io.pc.bits + 8.U
  ifu.io.predictedNextPC := nxtPredictedPC

  val isBranchGuessWrongReg = RegInit(false.B)
  val isIFUAckCorrectTarget = Wire(Bool())
  isBranchGuessWrong := isBranchGuessWrongReg || (exu.io.out.valid && exu.io.predWrong)
  when(exu.io.out.valid) {
    isBranchGuessWrongReg := exu.io.predWrong
  }.elsewhen(isIFUAckCorrectTarget) {
    isBranchGuessWrongReg := false.B
  }

  dontTouch(isBranchGuessWrong)
  val curCorrectJmpTarget = RegEnableReadNew(
    exu.io.out.bits.exuWriteBack.nxt_pc,
    exu.io.out.valid
  )

  // NOTICE: for IFU
  // must wait until IFU accepts the jump target (pc fire) can not
  // just check the valid, sometimes IFU still fetching old wrong
  // target, if think it meets the correct target, then the wrong
  // target will be passed to IDU since that time isWrongPred is unset.
  isIFUAckCorrectTarget := ifu.io.pc.fire && (ifu.io.pc.bits === curCorrectJmpTarget)

  val isIDUMeetCorrectJmpTarget = Wire(Bool())
  isIDUMeetCorrectJmpTarget := ifu.io.out.valid && (ifu.io.out.bits.pc === curCorrectJmpTarget)
  dontTouch(isIFUAckCorrectTarget)
  dontTouch(isIDUMeetCorrectJmpTarget)
  dontTouch(curCorrectJmpTarget)

  when(isBranchGuessWrong && (!isIFUAckCorrectTarget)) {
    isFlushIDUReg := true.B
  }.elsewhen(isIDUMeetCorrectJmpTarget) {
    isFlushIDUReg := false.B
  }

  isFlushIDU := (isFlushIDUReg) || isBranchGuessWrong
  dontTouch(isFlushIDU)

  pc := Mux(
    ifu.io.pc.ready,
    // Sometimes although jump,
    // target is near current pc and IFU just meets it
    Mux(isBranchGuessWrong && (!isIFUAckCorrectTarget), curCorrectJmpTarget, nxtPredictedPC),
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

  pipelineConnect(ifu.io.out, idu.io.in, idu.io.out)
  pipelineConnect(idu.io.out, exu.io.in, exu.io.out)
  pipelineConnect(exu.io.out, lsu.io.in, lsu.io.out)

  idu.io.rvec <> gprs.io.read
  exu.io.csr_rvec <> csrs.io.read

  idu.io.exuWrBack := ExtractGPRInfoFromLSU(exu.io.out)
  idu.io.lsuWrBack := ExtractGPRInfoFromLSU(lsu.io.in)
  idu.io.wbuWrBack := ExtractGPRInfoFromWrBack(wbu.io.in)
  val exuWrBackDataVaild = !(exu.io.out.bits.isLoad || exu.io.out.bits.isStore)
  idu.io.exuWrBackDataVaild := exuWrBackDataVaild && exu.io.out.valid
  idu.io.flush              := isFlushIDU

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
