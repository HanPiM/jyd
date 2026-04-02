package uart
import chisel3._
import chisel3.util._

import common_def._
import axi4._
import simplebus._

import chisel3.util.circt.dpi._

import dpiwrap._

class UARTToStdOut extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock   = Input(Clock())
    val enable  = Input(Bool())
    val chData  = Input(UInt(8.W))
  })
  setInline("UARTToStdOut.v",
  s"""
     |module UARTToStdOut(
     |  input clock,
     |  input enable,
     |  input [7:0] chData
     |);
     |  always @(posedge clock) begin
     |    if (enable) begin
     |      $$write("%c", chData);
     |      $$fflush();
     |    end
     |  end
     |endmodule
  """.stripMargin)
}

class SimpleBusUART extends Module {
  val io = IO(SimpleBusIO.Slave)
  io.dontCareResp()
  io.req_ready := true.B

  val doReq   = io.req_valid && io.req_ready
  val doWrite = doReq && io.wen
  val doRead  = doReq && !io.wen

  val uartToStdOut = Module(new UARTToStdOut)
  uartToStdOut.io.clock  := clock
  uartToStdOut.io.enable := doWrite
  uartToStdOut.io.chData := io.wdata(7, 0)

  val stdinData = RawClockedNonVoidFunctionCall("uart_try_getch", UInt(32.W))(clock, doRead)

  io.resp_valid := RegNext(doReq, false.B)
  io.rdata      := Mux(RegNext(doRead, false.B), stdinData, 0.U(32.W))
}

class UARTUnit extends Module {
  val io = IO(AXI4IO.Slave)

  val sio = io
  val uartBus = Module(new SimpleBusUART)

  val waitingResp  = RegInit(false.B)
  val pendingRead  = RegInit(false.B)
  val pendingId    = RegInit(0.U(4.W))
  val writeReqFire = !waitingResp && sio.awvalid && sio.wvalid
  val readReqFire  = !waitingResp && !writeReqFire && sio.arvalid
  val reqFire      = writeReqFire || readReqFire

  uartBus.io.req_valid := reqFire
  uartBus.io.addr      := Mux(writeReqFire, sio.awaddr, sio.araddr)
  uartBus.io.size      := Mux(writeReqFire, sio.awsize, sio.arsize)
  uartBus.io.wdata     := sio.wdata
  uartBus.io.wmask     := sio.wstrb
  uartBus.io.wen       := writeReqFire

  sio.awready := !waitingResp && sio.wvalid && uartBus.io.req_ready
  sio.wready  := !waitingResp && sio.awvalid && uartBus.io.req_ready
  sio.arready := !waitingResp && !writeReqFire && uartBus.io.req_ready

  sio.bvalid := waitingResp && !pendingRead && uartBus.io.resp_valid
  sio.bresp  := AXI4IO.BResp.OKAY
  sio.bid    := pendingId

  sio.rvalid := waitingResp && pendingRead && uartBus.io.resp_valid
  sio.rdata  := uartBus.io.rdata
  sio.rresp  := AXI4IO.RResp.OKAY
  sio.rlast  := true.B
  sio.rid    := pendingId

  io.dontCareNonLiteB()
  io.dontCareNonLiteR()

  when(reqFire && uartBus.io.req_ready) {
    waitingResp := true.B
    pendingRead := readReqFire
    pendingId   := Mux(writeReqFire, sio.awid, sio.arid)
  }

  when(waitingResp && uartBus.io.resp_valid) {
    waitingResp := false.B
  }
}
