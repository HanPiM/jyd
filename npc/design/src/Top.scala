package top

import chisel3._

class TopIO extends Bundle{
  val btn = Input(UInt(5.W))
  val sw = Input(UInt(16.W))
  val ps2_clk = Input(Bool())
  val ps2_data = Input(Bool())
  val uart_rx = Input(Bool())
  val uart_tx = Output(Bool())
  val ledr = Output(UInt(16.W))
  
  val VGA_CLK = Output(Bool())
  val VGA_HSYNC = Output(Bool())
  val VGA_VSYNC = Output(Bool())
  val VGA_BLANK_N = Output(Bool())
  val VGA_R = Output(UInt(8.W))
  val VGA_G = Output(UInt(8.W))
  val VGA_B = Output(UInt(8.W))
  
  val seg0 = Output(UInt(8.W))
  val seg1 = Output(UInt(8.W))
  val seg2 = Output(UInt(8.W))
  val seg3 = Output(UInt(8.W))
  val seg4 = Output(UInt(8.W))
  val seg5 = Output(UInt(8.W))
  val seg6 = Output(UInt(8.W))
  val seg7 = Output(UInt(8.W))
}


class Top(word_width:Int=32) extends Module{
  val io = IO(new TopIO)
  dontTouch(io)
  io:=DontCare
 
  val pc = Output(UInt(word_width.W))
  val nxt_pc = Output(UInt(word_width.W))

}
