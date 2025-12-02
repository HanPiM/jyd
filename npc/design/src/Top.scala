package top

import chisel3._

import regfile._
import memory._

import cpu._

// For NVBoard
class TopIO extends Bundle {
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

class Top(word_width: Int = 32) extends Module {
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

  val mem = Module(new MemUnit)

  val ifu = Module(new IFU)
  val idu = Module(new IDU)
  val exu = Module(new EXU)

  val INIT_PC = "h80000000".U(32.W)

  val pc = RegInit(INIT_PC)
  val wbinfo = exu.io.out.bits

  pc := Mux(exu.io.out.valid, wbinfo.nxt_pc,pc)

  ifu.io.pc.bits := pc
  ifu.io.pc.valid := exu.io.out.valid

  ifu.io.out <> idu.io.in
  idu.io.out <> exu.io.dinst

  exu.io.rvec <> gprs.io.rvec
  exu.io.csr_rvec <> csrs.io.read

  mem.io.read <> exu.io.mem_rreq

  // Write back

  gprs.io.write <> wbinfo.gpr
  csrs.io.write <> wbinfo.csr
  csrs.io.is_ecall := wbinfo.csr_ecallflag

  mem.io.write <> wbinfo.mem

  exu.io.out.ready := ifu.io.pc.ready


}
