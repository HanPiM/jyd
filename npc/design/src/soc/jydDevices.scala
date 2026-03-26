package jyd

import chisel3._
import chisel3.util._
import simplebus._
import uart._
import top.{CPUCoreAsBlackBox, PCProviderAsBlackBox}
import testSoC.MaskedRdWrMem

object AddrSpace {
  val IROM = ("h80000000".U, "h80040000".U)
  val DRAM = ("h80100000".U, "h80180000".U)
  val MMIO = ("h80200000".U, "h80200100".U)

  val LED = ("h80200040".U, "h80200044".U)
  val SEG = ("h80200020".U, "h80200024".U)

  val CNT = ("h80200050".U, "h80200054".U)

  object SelfExtSpace {
    val UART = ("h10000000".U, "h10001000".U)
  }

  def needSkipDifftestGroup = Seq(
    MMIO,
    SelfExtSpace.UART
  )

  def inRng(addr: UInt, rng: (UInt, UInt)): Bool = {
    (addr >= rng._1) && (addr < rng._2)
  }
}

class SimpleBusMem(sizeInByte: Int, baseAddr: BigInt, readOnly: Boolean = false) extends Module {
  val io = IO(SimpleBusIO.Slave)
  io.dontCareResp()
  io.req_ready := true.B

  val mem = Module(new MaskedRdWrMem(sizeInByte))
  mem.io.write  := false.B
  mem.io.rdAddr := 0.U
  mem.io.wrAddr := 0.U
  mem.io.mask   := 0.U.asTypeOf(Vec(4, Bool()))
  mem.io.dataIn := 0.U.asTypeOf(mem.dataType)

  val localAddr = io.addr - baseAddr.U(32.W)
  val doReq     = io.req_valid && io.req_ready
  val doRead    = doReq && !io.wen
  val doWrite   = if (readOnly) false.B else (doReq && io.wen)

  mem.io.rdAddr := localAddr
  mem.io.wrAddr := localAddr
  mem.io.write  := doWrite
  mem.io.mask   := io.wmask.asBools
  mem.io.dataIn := io.wdata.asTypeOf(mem.dataType)

  val respValidReg = RegNext(doReq, false.B)
  val respDataReg  = RegEnable(mem.io.dataOut.asUInt, doRead)
  io.resp_valid := respValidReg
  io.rdata      := respDataReg
}

class SimIROMMem(sizeInByte: Int) extends BlackBox with HasBlackBoxInline {
  val addrWidth = log2Ceil(sizeInByte) - 2
  val depth     = sizeInByte / 4
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val en    = Input(Bool())
    val addr  = Input(UInt(addrWidth.W))
    val data  = Output(UInt(32.W))
  })

  setInline(
    s"${desiredName}.v",
    s"""
       |module ${desiredName}(
       |  input clock,
       |  input en,
       |  input [${addrWidth - 1}:0] addr,
       |  output reg [31:0] data
       |);
       |  reg [31:0] Memory [0:${depth - 1}];
       |  always @(posedge clock) begin
       |    if (en) begin
       |      data <= Memory[addr];
       |    end
       |  end
       |endmodule
     """.stripMargin
  )
}

class SimpleBusROM(sizeInByte: Int, baseAddr: BigInt) extends Module {
  val io = IO(SimpleBusIO.Slave)
  io.dontCareResp()
  io.req_ready := true.B

  val mem = Module(new SimIROMMem(sizeInByte))
  mem.io.clock := clock

  val localWordAddr = (io.addr - baseAddr.U(32.W))(log2Ceil(sizeInByte) - 1, 2)
  val doRead        = io.req_valid && io.req_ready && !io.wen
  val respValidReg  = RegNext(doRead, false.B)
  mem.io.en   := doRead
  mem.io.addr := localWordAddr

  io.resp_valid := respValidReg
  io.rdata      := mem.io.data
}

class SimpleBusOneWordRWDevice(updFuncName: String) extends Module {
  val io = IO(SimpleBusIO.Slave)
  io.dontCareResp()
  io.req_ready := true.B

  val dataReg = RegInit(0.U(32.W))
  val doReq   = io.req_valid && io.req_ready
  val doWrite = doReq && io.wen

  when(doWrite) {
    dataReg := io.wdata
    dpiwrap.ClockedCallVoidDPIC(updFuncName)(clock, true.B, io.wdata)
  }

  io.resp_valid := RegNext(doReq, false.B)
  io.rdata      := dataReg
}

class SimpleBusTimer extends Module {
  val io = IO(SimpleBusIO.Slave)
  io.dontCareResp()
  io.req_ready := true.B

  object State extends ChiselEnum {
    val idle, ticking, finished = Value
  }

  val MAGIC_START = "h80000000".U
  val MAGIC_STOP  = "hffffffff".U

  val state = RegInit(State.idle)
  val timer = RegInit(0.U(32.W))
  val doReq = io.req_valid && io.req_ready

  when(state === State.ticking) {
    timer := timer + 1.U
  }

  when(doReq && io.wen) {
    when(io.wdata === MAGIC_START) {
      state := State.ticking
    }.elsewhen(io.wdata === MAGIC_STOP) {
      state := State.finished
    }
  }

  io.resp_valid := RegNext(doReq, false.B)
  io.rdata      := timer
}

class SimpleBusUART extends Module {
  val io = IO(SimpleBusIO.Slave)
  io.dontCareResp()
  io.req_ready := true.B

  val uartToStdOut = Module(new UARTToStdOut)
  val doWrite      = io.req_valid && io.req_ready && io.wen
  uartToStdOut.io.clock  := clock
  uartToStdOut.io.enable := doWrite
  uartToStdOut.io.chData := io.wdata(7, 0)

  io.resp_valid := RegNext(io.req_valid && io.req_ready, false.B)
  io.rdata      := 0.U
}

class JYDPeripheralBridge extends Module {
  val io = IO(new Bundle {
    val cpu  = SimpleBusIO.Slave
    val dram = SimpleBusIO.Master
    val led  = SimpleBusIO.Master
    val seg  = SimpleBusIO.Master
    val cnt  = SimpleBusIO.Master
    val uart = SimpleBusIO.Master
  })

  io.cpu.dontCareResp()
  io.cpu.req_ready := false.B
  Seq(io.dram, io.led, io.seg, io.cnt, io.uart).foreach(_.dontCareReq())

  object DevSel extends ChiselEnum {
    val dram, led, seg, cnt, uart = Value
  }

  val pendingSel = RegInit(DevSel.dram.asUInt)
  val waitingResp = RegInit(false.B)

  val isDRAM = AddrSpace.inRng(io.cpu.addr, AddrSpace.DRAM)
  val isLED  = AddrSpace.inRng(io.cpu.addr, AddrSpace.LED)
  val isSEG  = AddrSpace.inRng(io.cpu.addr, AddrSpace.SEG)
  val isCNT  = AddrSpace.inRng(io.cpu.addr, AddrSpace.CNT)
  val isUART = AddrSpace.inRng(io.cpu.addr, AddrSpace.SelfExtSpace.UART)

  val targetSel = MuxCase(
    DevSel.dram.asUInt,
    Seq(
      isLED  -> DevSel.led.asUInt,
      isSEG  -> DevSel.seg.asUInt,
      isCNT  -> DevSel.cnt.asUInt,
      isUART -> DevSel.uart.asUInt
    )
  )

  val targets = Seq(
    DevSel.dram.asUInt -> io.dram,
    DevSel.led.asUInt  -> io.led,
    DevSel.seg.asUInt  -> io.seg,
    DevSel.cnt.asUInt  -> io.cnt,
    DevSel.uart.asUInt -> io.uart
  )

  val selectedReady = MuxLookup(targetSel, io.dram.req_ready)(
    targets.map { case (sel, bus) => sel -> bus.req_ready }
  )
  io.cpu.req_ready := !waitingResp && selectedReady

  for ((sel, bus) <- targets) {
    val hit = targetSel === sel
    bus.req_valid := !waitingResp && io.cpu.req_valid && hit
    bus.addr      := io.cpu.addr
    bus.size      := io.cpu.size
    bus.wdata     := io.cpu.wdata
    bus.wmask     := io.cpu.wmask
    bus.wen       := io.cpu.wen
  }

  when(!waitingResp && io.cpu.req_valid && io.cpu.req_ready) {
    pendingSel  := targetSel
    waitingResp := true.B
  }

  val respValid = MuxLookup(pendingSel, io.dram.resp_valid)(
    targets.map { case (sel, bus) => sel -> bus.resp_valid }
  )
  val respData = MuxLookup(pendingSel, io.dram.rdata)(
    targets.map { case (sel, bus) => sel -> bus.rdata }
  )

  io.cpu.resp_valid := waitingResp && respValid
  io.cpu.rdata      := respData

  when(waitingResp && respValid) {
    waitingResp := false.B
  }
}

class JYDSoC(val resetPC: UInt = "h80000000".U) extends Module {
  val cpu   = Module(new CPUCoreAsBlackBox)
  val irom  = Module(new SimpleBusROM(1024 * 256, 0x80000000L))
  val dram  = Module(new SimpleBusMem(1024 * 512, 0x80100000L))
  val led   = Module(new SimpleBusOneWordRWDevice("jyd_update_led"))
  val seg   = Module(new SimpleBusOneWordRWDevice("jyd_update_seg"))
  val cnt   = Module(new SimpleBusTimer)
  val uart  = Module(new SimpleBusUART)
  val perip = Module(new JYDPeripheralBridge)

  val resetPCProvider = Module(new PCProviderAsBlackBox)
  assert(resetPCProvider.io.resetPC === resetPC, f"Reset PC should be 0x${resetPC.litValue}%x for JYDSoC")

  cpu.io.clock        := clock
  cpu.io.reset        := reset
  cpu.io.io.interrupt := false.B

  SimpleBusIO.connectMasterSlave(cpu.io.io.irom, irom.io)
  SimpleBusIO.connectMasterSlave(cpu.io.io.dram, perip.io.cpu)
  SimpleBusIO.connectMasterSlave(perip.io.dram, dram.io)
  SimpleBusIO.connectMasterSlave(perip.io.led, led.io)
  SimpleBusIO.connectMasterSlave(perip.io.seg, seg.io)
  SimpleBusIO.connectMasterSlave(perip.io.cnt, cnt.io)
  SimpleBusIO.connectMasterSlave(perip.io.uart, uart.io)
}
