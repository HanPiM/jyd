package uart
import chisel3._
import chisel3.util._

import common_def._
import axi4._
import simplebus._

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

class UARTTryGetCh extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val clock  = Input(Clock())
    val enable = Input(Bool())
    val chData = Output(UInt(32.W))
  })
  setInline("UARTTryGetCh.v",
  s"""
     |`ifndef __ICARUS__
     |import "DPI-C" function void uart_try_getch(output int ch);
     |`endif
     |
     |module UARTTryGetCh(
     |  input clock,
     |  input enable,
     |  output reg [31:0] chData
     |);
     |  initial chData = 32'hff;
     |
     |  always @(posedge clock) begin
     |    if (enable) begin
     |`ifdef __ICARUS__
     |      chData <= 32'hff;
     |`else
     |      uart_try_getch(chData);
     |`endif
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

  val uartTryGetCh = Module(new UARTTryGetCh)
  uartTryGetCh.io.clock  := clock
  uartTryGetCh.io.enable := doRead

  val respValidReg = RegNext(RegNext(doReq, false.B), false.B)
  val readRespPipe0 = RegNext(doRead, false.B)
  val readRespReg  = RegNext(readRespPipe0, false.B)
  val respDataReg  = RegEnable(uartTryGetCh.io.chData, readRespPipe0)
  io.resp_valid := respValidReg
  io.rdata      := Mux(readRespReg, respDataReg, 0.U(32.W))
}

class AXI4LiteUARTMasterIO extends Bundle {
  val awaddr  = Output(UInt(4.W))
  val awvalid = Output(Bool())
  val awready = Input(Bool())
  val wdata   = Output(UInt(32.W))
  val wstrb   = Output(UInt(4.W))
  val wvalid  = Output(Bool())
  val wready  = Input(Bool())
  val bresp   = Input(UInt(2.W))
  val bvalid  = Input(Bool())
  val bready  = Output(Bool())
  val araddr  = Output(UInt(4.W))
  val arvalid = Output(Bool())
  val arready = Input(Bool())
  val rdata   = Input(UInt(32.W))
  val rresp   = Input(UInt(2.W))
  val rvalid  = Input(Bool())
  val rready  = Output(Bool())
}

/** FPGA implementation of the legacy JYD byte-wide UART register.
  *
  * Software still reads and writes one byte at 0x802000a0. The backing AXI
  * UART Lite uses separate RX/TX/status registers, so this adapter translates
  * the accesses and drains a deep transmit queue in the background. Keeping
  * writes at the same two-cycle latency as the simulation UART prevents the
  * 9600-baud line rate from perturbing the CoreMark counter unless the queue
  * actually fills.
  */
class SimpleBusFPGAUART(txDepth: Int = 2048) extends Module {
  val io = IO(new Bundle {
    val bus = SimpleBusIO.Slave
    val axi = new AXI4LiteUARTMasterIO
  })

  io.bus.dontCareResp()

  val txQueue = Module(new Queue(UInt(8.W), txDepth))
  val isWrite = io.bus.req_valid && io.bus.wen
  val isRead  = io.bus.req_valid && !io.bus.wen

  object State extends ChiselEnum {
    val idle, txStatusAR, txStatusR, txWriteAWW, txWriteB, rxStatusAR, rxStatusR, rxDataAR, rxDataR = Value
  }
  val state  = RegInit(State.idle)
  val awSent = RegInit(false.B)
  val wSent  = RegInit(false.B)

  val canTakeRead = state === State.idle
  io.bus.req_ready := Mux(io.bus.wen, txQueue.io.enq.ready, canTakeRead)

  val writeFire = isWrite && io.bus.req_ready
  val readFire  = isRead && io.bus.req_ready
  txQueue.io.enq.valid := writeFire
  txQueue.io.enq.bits  := io.bus.wdata(7, 0)
  txQueue.io.deq.ready := false.B

  val writeRespPipe0 = RegNext(writeFire, false.B)
  val writeResp       = RegNext(writeRespPipe0, false.B)
  val readResp        = WireDefault(false.B)
  val readData        = WireDefault(0.U(32.W))
  io.bus.resp_valid := writeResp || readResp
  io.bus.rdata      := readData

  io.axi.awaddr  := 4.U
  io.axi.awvalid := false.B
  io.axi.wdata   := txQueue.io.deq.bits
  io.axi.wstrb   := "b0001".U
  io.axi.wvalid  := false.B
  io.axi.bready  := false.B
  io.axi.araddr  := 8.U
  io.axi.arvalid := false.B
  io.axi.rready  := false.B

  switch(state) {
    is(State.idle) {
      when(readFire) {
        state := State.rxStatusAR
      }.elsewhen(txQueue.io.deq.valid) {
        state := State.txStatusAR
      }
    }
    is(State.txStatusAR) {
      io.axi.araddr  := 8.U
      io.axi.arvalid := true.B
      when(io.axi.arready) { state := State.txStatusR }
    }
    is(State.txStatusR) {
      io.axi.rready := true.B
      when(io.axi.rvalid) {
        when(io.axi.rdata(3)) {
          state := State.txStatusAR
        }.otherwise {
          awSent := false.B
          wSent  := false.B
          state  := State.txWriteAWW
        }
      }
    }
    is(State.txWriteAWW) {
      io.axi.awaddr  := 4.U
      io.axi.awvalid := !awSent
      io.axi.wdata   := txQueue.io.deq.bits
      io.axi.wstrb   := "b0001".U
      io.axi.wvalid  := !wSent
      when(io.axi.awvalid && io.axi.awready) { awSent := true.B }
      when(io.axi.wvalid && io.axi.wready) { wSent := true.B }
      when((awSent || io.axi.awready) && (wSent || io.axi.wready)) { state := State.txWriteB }
    }
    is(State.txWriteB) {
      io.axi.bready := true.B
      when(io.axi.bvalid) {
        txQueue.io.deq.ready := true.B
        state                := State.idle
      }
    }
    is(State.rxStatusAR) {
      io.axi.araddr  := 8.U
      io.axi.arvalid := true.B
      when(io.axi.arready) { state := State.rxStatusR }
    }
    is(State.rxStatusR) {
      io.axi.rready := true.B
      when(io.axi.rvalid) {
        when(io.axi.rdata(0)) {
          state := State.rxDataAR
        }.otherwise {
          readResp := true.B
          readData := "hff".U
          state    := State.idle
        }
      }
    }
    is(State.rxDataAR) {
      io.axi.araddr  := 0.U
      io.axi.arvalid := true.B
      when(io.axi.arready) { state := State.rxDataR }
    }
    is(State.rxDataR) {
      io.axi.rready := true.B
      when(io.axi.rvalid) {
        readResp := true.B
        readData := io.axi.rdata(7, 0)
        state    := State.idle
      }
    }
  }

  when(io.axi.rvalid && io.axi.rready) {
    assert(io.axi.rresp === 0.U, "AXI UART Lite read failed")
  }
  when(io.axi.bvalid && io.axi.bready) {
    assert(io.axi.bresp === 0.U, "AXI UART Lite write failed")
  }
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
