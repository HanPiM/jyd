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
  val isStatus = io.addr(3, 2).orR

  val uartToStdOut = Module(new UARTToStdOut)
  uartToStdOut.io.clock  := clock
  uartToStdOut.io.enable := doWrite
  uartToStdOut.io.chData := io.wdata(7, 0)

  val uartTryGetCh = Module(new UARTTryGetCh)
  uartTryGetCh.io.clock  := clock
  uartTryGetCh.io.enable := doRead && !isStatus

  val respValidReg = RegNext(RegNext(doReq, false.B), false.B)
  val readRespPipe0 = RegNext(doRead, false.B)
  val readRespReg  = RegNext(readRespPipe0, false.B)
  val statusReadPipe0 = RegNext(doRead && isStatus, false.B)
  // Simulation has no TX queue.  Keep bit0 asserted only for status reads;
  // the data register must return the raw received byte, otherwise characters
  // with bit0 clear (e.g. 'x' 0x78) are corrupted to their +1 value.
  val respDataReg = RegEnable(Mux(statusReadPipe0, 1.U(32.W), uartTryGetCh.io.chData), readRespPipe0)
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
  * The CPU-facing side deliberately has no AXI or CDC backpressure.  Every
  * request is accepted locally and answered two cycles later, just like the
  * simulation UART.  The FPGA wrapper owns the dual-clock FIFOs and drains
  * TX at 50 MHz, keeping the UART-Lite state machine out of the 280 MHz CPU
  * clock domain.
  */
class SimpleBusFPGAUART extends Module {
  val io = IO(new Bundle {
    val bus     = SimpleBusIO.Slave
    val txPush  = Output(Bool())
    val txData  = Output(UInt(8.W))
    val txFull  = Input(Bool())
    val rxData  = Input(UInt(8.W))
    val rxEmpty = Input(Bool())
    val rxPop   = Output(Bool())
  })

  io.bus.dontCareResp()
  io.bus.req_ready := true.B

  val doReq    = io.bus.req_valid && io.bus.req_ready
  val isStatus = io.bus.addr(3, 2).orR
  val doWrite  = doReq && io.bus.wen && !isStatus
  val doRead   = doReq && !io.bus.wen && !isStatus
  val doStatus = doReq && !io.bus.wen && isStatus

  // Register the TX push/data at the UART boundary so the long CPU-to-FIFO
  // store path ends at a flop instead of the TX-FIFO BRAM write pins.  The
  // CPU polls TX-ready before each byte and the FIFO keeps a four-slot
  // in-flight margin, so the extra cycle is absorbed without backpressure.
  io.txPush := RegNext(doWrite, false.B)
  io.txData := RegNext(io.bus.wdata(7, 0))
  io.rxPop  := doRead && !io.rxEmpty

  // Sample a local asynchronous-FIFO word at request time.  The FIFO has
  // already synchronized the producer pointer into this domain, so the
  // response never waits for an AXI or CDC transaction.
  val readDataPipe0 = RegEnable(Mux(io.rxEmpty, "hff".U(8.W), io.rxData), doRead)
  val readDataReg   = RegNext(readDataPipe0)
  val readRespPipe0 = RegNext(doRead, false.B)
  val readRespReg   = RegNext(readRespPipe0, false.B)
  // UART+4..+15 are status aliases: bit0 is TX-ready, bit1 is RX-ready.
  // The CPU's MMIO path aligns byte requests, so status uses a word offset.
  val statusPipe0 = RegEnable(Cat(0.U(30.W), !io.rxEmpty, !io.txFull), doStatus)
  val statusReg   = RegNext(statusPipe0)
  val statusResp  = RegNext(RegNext(doStatus, false.B), false.B)

  io.bus.resp_valid := RegNext(RegNext(doReq, false.B), false.B)
  io.bus.rdata      := Mux(statusResp, statusReg, Mux(readRespReg, readDataReg, 0.U(32.W)))
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
