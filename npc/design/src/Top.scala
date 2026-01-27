package top

import chisel3._

import regfile._
import memory._

import cpu._

import chisel3.util.circt.dpi._
import chisel3.util._

import axi4._
import uart._
import clint._
import xbar._

class NVBoardIO extends Bundle {
  val btn      = Input(UInt(5.W))
  val sw       = Input(UInt(16.W))
  val ps2_clk  = Input(Bool())
  val ps2_data = Input(Bool())
  val uart_rx  = Input(Bool())
  val uart_tx  = Output(Bool())
  val ledr     = Output(UInt(16.W))

  val VGA_CLK     = Output(Bool())
  val VGA_HSYNC   = Output(Bool())
  val VGA_VSYNC   = Output(Bool())
  val VGA_BLANK_N = Output(Bool())
  val VGA_R       = Output(UInt(8.W))
  val VGA_G       = Output(UInt(8.W))
  val VGA_B       = Output(UInt(8.W))

  val seg0 = Output(UInt(8.W))
  val seg1 = Output(UInt(8.W))
  val seg2 = Output(UInt(8.W))
  val seg3 = Output(UInt(8.W))
  val seg4 = Output(UInt(8.W))
  val seg5 = Output(UInt(8.W))
  val seg6 = Output(UInt(8.W))
  val seg7 = Output(UInt(8.W))
}

class TopIO extends Bundle {
  val interrupt = Input(Bool())
  val master = AXI4IO.Master
  val slave  = AXI4IO.Slave
}

// make exu and ifu access memory
class EXUIFU_MemVisitArbiter extends Module {
  val io = IO(new Bundle {
    val exu = AXI4IO.Slave
    val ifu = AXI4IO.Slave

    val out = AXI4IO.Master
  })

  // Simple arbiter, since IFU and EXU won't access memory at the same time

  val isExuReg = Reg(Bool())
  val isIfuReg = Reg(Bool())

  val ifuIO = io.ifu
  val exuIO = io.exu
  val outIO = io.out

  val isExu = (isExuReg && (!ifuIO.arvalid)) || (exuIO.arvalid)
  val isIfu = (isIfuReg && (!exuIO.arvalid)) || (ifuIO.arvalid)

  when(exuIO.arvalid) {
    isExuReg := true.B
    isIfuReg := false.B
  }.elsewhen(ifuIO.arvalid) {
    isExuReg := false.B
    isIfuReg := true.B
  }

  // AR channel
  outIO.arvalid := exuIO.arvalid || ifuIO.arvalid
  outIO.araddr  := Mux(isExu, exuIO.araddr, ifuIO.araddr)

  outIO.arid    := Mux(isExu, exuIO.arid, ifuIO.arid)
  outIO.arlen   := Mux(isExu, exuIO.arlen, ifuIO.arlen)
  outIO.arsize  := Mux(isExu, exuIO.arsize, ifuIO.arsize)
  outIO.arburst := Mux(isExu, exuIO.arburst, ifuIO.arburst)

  exuIO.arready := isExu && outIO.arready
  ifuIO.arready := isIfu && outIO.arready

  // R channel
  AXI4IO.noShakeConnectR(exuIO, outIO)
  AXI4IO.noShakeConnectR(ifuIO, outIO)

  exuIO.rvalid := isExu && outIO.rvalid
  ifuIO.rvalid := isIfu && outIO.rvalid

  outIO.rready := Mux(isExu, exuIO.rready, ifuIO.rready)

  // AW, W, B channel
  //   since only exu need write
  AXI4IO.connectAW(exuIO, outIO)
  AXI4IO.connectW(exuIO, outIO)
  AXI4IO.connectB(exuIO, outIO)

  io.ifu.dontCareAW()
  io.ifu.dontCareW()
  io.ifu.dontCareB()
}

class ysyx_25100261(word_width: Int = 32) extends Module {
  type HasIO = {
    val io: Data
  }
  def instantiateForTest[M <: Module with HasIO](gen: => M): M = {
    val m = Module(gen)
    m.io := DontCare
    dontTouch(m.io)
    m
  }

  val io = IO(new TopIO)
  dontTouch(io)
  io := DontCare

  val gprs = Module(new RegisterFile(READ_PORTS = 2))
  val csrs = Module(new ControlStatusRegisterFile())

  // val mem = Module(new AXI4LiteMemUnit)

  val ifu = Module(new IFU)
  val idu = Module(new IDU)
  val exu = Module(new EXU)
  val wbu = Module(new WBU)

  val INIT_PC = "h30000000".U(32.W)
  // val MEM_BASE = "h80000000".U(32.W)
  // val MEM_END  = "h8FFFFFFF".U(32.W)
  //
  // val SERIAL_BASE = "h10000000".U(32.W)
  // val SERIAL_END  = "h10000001".U(32.W)

  val pc = RegInit(INIT_PC)

  val is_ebreak = (ifu.io.out.valid) && (ifu.io.out.bits.code === "h00100073".U)

  val nxt_pc       = exu.io.out.bits.nxt_pc
  val nxt_pc_valid = wbu.io.done

  val halted = RegInit(false.B)

  when(is_ebreak && !halted) {
    printf(p"EBREAK at PC = 0x${Hexadecimal(ifu.io.out.bits.pc)} a0 = 0x${Hexadecimal(gprs.io.a0)}\n")
    RawClockedVoidFunctionCall("raise_ebreak")(clock, is_ebreak, gprs.io.a0)
    halted := true.B
  }

  pc := Mux(wbu.io.done, nxt_pc, pc)

  when(nxt_pc_valid) {
    RawClockedVoidFunctionCall("pc_upd")(
      clock,
      nxt_pc_valid,
      pc,
      nxt_pc
    )
  }

  dontTouch(exu.io)

  val memArbiter = Module(new EXUIFU_MemVisitArbiter)
  AXI4IO.connectMasterSlave(exu.io.mem, memArbiter.io.exu)
  AXI4IO.connectMasterSlave(ifu.io.mem, memArbiter.io.ifu)

  // val uart = Module(new UARTUnit)
  val clint = Module(new CLINTUnit)

  val otherReqSlave = Wire(AXI4IO.Slave)
  AXI4IO.transformSlaveToMasterValidIf(!reset.asBool)(io.master, otherReqSlave)

  val memXBar = Module(new AXI4LiteXBar(Seq(
    // (MEM_BASE,MEM_END) -> mem.io,
    // (SERIAL_BASE,SERIAL_END) -> uart.io,
    ("h02000000".U(32.W),"h0200ffff".U(32.W)) -> clint.io,
    ("h0f000000".U(32.W),"hffffffff".U(32.W)) -> otherReqSlave
  )))

  when(io.master.bvalid && io.master.bresp === AXI4IO.BResp.DECERR){
    printf("AXI4 DECERR on write address 0x%x\n", io.master.awaddr)
    stop()
    stop()
  }
  when(io.master.rvalid && io.master.rresp === AXI4IO.RResp.DECERR){
    printf("AXI4 DECERR on read address 0x%x\n", io.master.araddr)
    stop()
    stop()
  }
  val SERIAL_ADDR_BASE = "h10000000".U(32.W)
  val SERIAL_ADDR_END  = "h10001000".U(32.W)
  val SPI_ADDR_BASE = "h10001000".U(32.W)
  val SPI_ADDR_END  = "h10002000".U(32.W)

  def inRng(beg: UInt, end: UInt, addr: UInt): Bool = {
    (addr >= beg) && (addr < end)
  }

  val wNeedSkip = inRng(SERIAL_ADDR_BASE, SERIAL_ADDR_END, io.master.awaddr) ||
                  inRng(SPI_ADDR_BASE, SPI_ADDR_END, io.master.awaddr)
  val rNeedSkip = inRng(SERIAL_ADDR_BASE, SERIAL_ADDR_END, io.master.araddr) ||
                  inRng(SPI_ADDR_BASE, SPI_ADDR_END, io.master.araddr)


  when(io.master.awvalid && io.master.awready && wNeedSkip){
    RawClockedVoidFunctionCall("skip_difftest_ref")(
      clock,
      true.B
    )
  }
  when(io.master.arvalid && io.master.arready && rNeedSkip){
    RawClockedVoidFunctionCall("skip_difftest_ref")(
      clock,
      true.B
    )
  }

  val MinAccessAddr = "h02000000".U(32.W)
  when(io.master.awvalid && io.master.awaddr < MinAccessAddr){
    printf("AXI4 Invalid Write Address 0x%x\n", io.master.awaddr)
    stop()
    stop()
  }
  when(io.master.arvalid && io.master.araddr < MinAccessAddr){
    printf("AXI4 Invalid Read Address 0x%x\n", io.master.araddr)
    stop()
    stop()
  }

  AXI4IO.connectMasterSlave(memArbiter.io.out, memXBar.io.in)
  memXBar.connect()

  ifu.io.pc.bits  := pc
  ifu.io.pc.valid := true.B

  ifu.io.out <> idu.io.in
  idu.io.out <> exu.io.dinst

  exu.io.rvec <> gprs.io.read
  exu.io.csr_rvec <> csrs.io.read

  // Write back

  wbu.io.data <> exu.io.out
  gprs.io.write <> wbu.io.gpr
  csrs.io.write <> wbu.io.csr
  csrs.io.is_ecall := wbu.io.is_ecall

}
