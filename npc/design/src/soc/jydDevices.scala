package jyd

import chisel3._
import chisel3.util._
import simplebus._
import uart._
import top.{CPUCoreAsBlackBox, PCProviderAsBlackBox}
import testSoC.MaskedRdWrMem

object AddrSpace {
  val IROM = ("h80000000".U, "h80080000".U)
  val DRAM = ("h80100000".U, "h80180000".U)
  val MMIO = ("h80200000".U, "h80200100".U)

  val LED = ("h80200040".U, "h80200044".U)
  val SEG = ("h80200020".U, "h80200024".U)

  val CNT = ("h80200050".U, "h80200054".U)

  object SelfExtSpace {
    val UART = ("h802000a0".U, "h802000af".U)
  }

  def needSkipDifftestGroup = Seq(
    MMIO,
    SelfExtSpace.UART
  )

  def inRng(addr: UInt, rng: (UInt, UInt)): Bool = {
    // 0x [8  0]  [0/1/2]   x ...
    //    ^31-24  ^23-20

    val keyBits = addr(21, 20)
    val rngKeyBits = rng._1(21, 20)
    val mmioHit = addr(7,4) === rng._1(7,4)

    val isMMIO = keyBits(1)

    (isMMIO && mmioHit) | (~isMMIO && keyBits(0) === rngKeyBits(0))

    // (addr >= rng._1) && (addr < rng._2)
  }
}

object JYDSoCConfig {
  val iromSizeInByte    = 1024 * 512
  val dramSizeInByte    = 1024 * 512
  val fpgaMemAddrWidth  = 18
  val fpgaMemDataWidth  = 32
  val iromBaseAddr: BigInt = 0x80000000L
  val dramBaseAddr: BigInt = 0x80100000L
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

  val respValidReg  = RegNext(RegNext(doReq, false.B), false.B)
  val respDataPipe0 = RegEnable(mem.io.dataOut.asUInt, doRead)
  val respDataReg   = RegNext(respDataPipe0)
  io.resp_valid := respValidReg
  io.rdata      := respDataReg
}

class SimIROMMem(sizeInByte: Int) extends BlackBox with HasBlackBoxInline {
  val addrWidth = log2Ceil(sizeInByte) - 2
  val depth     = sizeInByte / 4
  val io        = IO(new Bundle {
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

class SimpleBusOneWordRWDevice(updFuncName: Option[String] = None) extends Module {
  val io = IO(new Bundle {
    val bus   = SimpleBusIO.Slave
    val value = Output(UInt(32.W))
  })
  io.bus.dontCareResp()
  io.bus.req_ready := true.B

  val dataReg = RegInit(0.U(32.W))
  val doReq   = io.bus.req_valid && io.bus.req_ready
  val doWrite = doReq && io.bus.wen

  when(doWrite) {
    dataReg := io.bus.wdata
  }
  updFuncName.foreach { name =>
    dpiwrap.ClockedCallVoidDPIC(name)(clock, doWrite, io.bus.wdata)
  }

  val respDataPipe0 = RegEnable(dataReg, doReq)
  val respDataReg   = RegNext(respDataPipe0)
  io.bus.resp_valid := RegNext(RegNext(doReq, false.B), false.B)
  io.bus.rdata      := respDataReg
  io.value          := dataReg
}

class SimpleBusTimer extends Module {
  val io = IO(SimpleBusIO.Slave)
  io.dontCareResp()
  io.req_ready := true.B

  private val clockFreqHz      = 50000000
  private val cyclesPerMs      = clockFreqHz / 1000
  private val cycleCounterMax  = cyclesPerMs - 1
  private val cycleCounterBits = math.max(1, log2Ceil(cyclesPerMs))

  val MAGIC_START = "h80000000".U(32.W)
  val MAGIC_STOP  = "hffffffff".U(32.W)

  val start        = RegInit(false.B)
  val cycleCounter = RegInit(0.U(cycleCounterBits.W))
  val timerMs      = RegInit(0.U(32.W))
  val doReq        = io.req_valid && io.req_ready

  when(start) {
    when(cycleCounter === cycleCounterMax.U) {
      cycleCounter := 0.U
      timerMs      := timerMs + 1.U
    }.otherwise {
      cycleCounter := cycleCounter + 1.U
    }
  }.otherwise {
    cycleCounter := 0.U
  }

  when(doReq && io.wen) {
    when(io.wdata === MAGIC_START) {
      start := true.B
    }.elsewhen(io.wdata === MAGIC_STOP) {
      start := false.B
    }
  }

  val respValidReg  = RegNext(RegNext(doReq, false.B), false.B)
  val respDataPipe0 = RegEnable(timerMs, doReq)
  val respDataReg   = RegNext(respDataPipe0)
  io.resp_valid := respValidReg
  io.rdata      := respDataReg
}

class JYDFPGAIROMBlackBox extends BlackBox {
  override def desiredName: String = "blk_mem_gen_irom"
  val io = IO(new Bundle {
    val clka  = Input(Clock())
    val ena   = Input(Bool())
    val addra = Input(UInt(JYDSoCConfig.fpgaMemAddrWidth.W))
    val douta = Output(UInt(JYDSoCConfig.fpgaMemDataWidth.W))
  })
}

/** Simulation model for the 2 KiB, 32-bit-wide Vivado block-memory IP.
  *
  * Keep the BlackBox name identical to the Vivado IP so FPGA builds bind this
  * instance to the IP output product. The inline SystemVerilog source is used
  * only by RTL simulation and is excluded from synthesis/FPGA packaging.
  */
class BlkMemGen2KB extends BlackBox with HasBlackBoxInline {
  override def desiredName: String = "blk_mem_gen_2KB"
  val io = IO(new Bundle {
    val clka  = Input(Clock())
    val ena   = Input(Bool())
    val wea   = Input(UInt(4.W))
    val addra = Input(UInt(9.W))
    val dina  = Input(UInt(32.W))
    val douta = Output(UInt(32.W))
  })

  setInline(
    "blk_mem_gen_2KB.sv",
    """module blk_mem_gen_2KB (
      |  input  wire        clka,
      |  input  wire        ena,
      |  input  wire [3:0]  wea,
      |  input  wire [8:0]  addra,
      |  input  wire [31:0] dina,
      |  output reg  [31:0] douta
      |);
      |  reg [31:0] mem [0:511];
      |
      |  always @(posedge clka) begin
      |    if (ena) begin
      |      // Match the Vivado IP's read-first read-during-write mode.
      |      douta <= mem[addra];
      |      if (wea[0]) mem[addra][7:0]   <= dina[7:0];
      |      if (wea[1]) mem[addra][15:8]  <= dina[15:8];
      |      if (wea[2]) mem[addra][23:16] <= dina[23:16];
      |      if (wea[3]) mem[addra][31:24] <= dina[31:24];
      |    end
      |  end
      |endmodule
      |""".stripMargin
  )
}

class JYDFPGADRAMBlackBox extends BlackBox {
  override def desiredName: String = "blk_mem_gen_dram"
  val io = IO(new Bundle {
    val clka  = Input(Clock())
    val ena   = Input(Bool())
    val wea   = Input(UInt(4.W))
    val addra = Input(UInt(JYDSoCConfig.fpgaMemAddrWidth.W))
    val dina  = Input(UInt(JYDSoCConfig.fpgaMemDataWidth.W))
    val douta = Output(UInt(JYDSoCConfig.fpgaMemDataWidth.W))
  })
}

class JYDFPGACounterBlackBox extends BlackBox {
  override def desiredName: String = "counter"
  val io = IO(new Bundle {
    val cpu_clk        = Input(Clock())
    val cnt_clk        = Input(Clock())
    val rst            = Input(Bool())
    val cnt_enable_cpu = Input(Bool())
    val perip_rdata    = Output(UInt(32.W))
  })
}

class SimpleBusFPGAROM(sizeInByte: Int, baseAddr: BigInt) extends Module {
  val io = IO(SimpleBusIO.Slave)
  io.dontCareResp()
  io.req_ready := true.B

  val mem = Module(new JYDFPGAIROMBlackBox)
  val localWordAddr = io.addr(log2Ceil(sizeInByte) - 1, 2)
  val doRead        = io.req_valid && io.req_ready

  mem.io.clka  := clock
  mem.io.ena   := true.B
  mem.io.addra := localWordAddr

  io.resp_valid := RegNext(doRead, false.B)
  io.rdata      := mem.io.douta
}

class SimpleBusFPGAMem(sizeInByte: Int, baseAddr: BigInt) extends Module {
  val io = IO(SimpleBusIO.Slave)
  io.dontCareResp()
  io.req_ready := true.B

  val mem = Module(new JYDFPGADRAMBlackBox)
  // val localWordAddr = (io.addr - baseAddr.U(32.W))(log2Ceil(sizeInByte) - 1, 2)
  val localWordAddr = io.addr(log2Ceil(sizeInByte) - 1, 2)
  val doReq         = io.req_valid && io.req_ready
  val doWrite       = doReq && io.wen

  mem.io.clka  := clock
  // BRAM Output register was controlled by ena!!!
  // NEED keep ena high during output register update
  mem.io.ena   := true.B 
  // since keep ena always high, we need to make write happen
  // only when request is valid
  mem.io.wea   := io.wmask & Fill(4, io.wen & io.req_valid)
  mem.io.addra := localWordAddr
  mem.io.dina  := io.wdata

  io.resp_valid := RegNext(RegNext(doReq, false.B), false.B)
  io.rdata      := mem.io.douta
}

class SimpleBusFPGACounter extends Module {
  val io = IO(new Bundle {
    val bus       = SimpleBusIO.Slave
    val clk_50Mhz = Input(Clock())
    val rst       = Input(Bool())
    val cntEnable = Input(Bool())
  })
  io.bus.dontCareResp()
  io.bus.req_ready := true.B

  val counter = Module(new JYDFPGACounterBlackBox)
  val doReq   = io.bus.req_valid && io.bus.req_ready

  counter.io.cpu_clk        := clock
  counter.io.cnt_clk        := io.clk_50Mhz
  counter.io.rst            := io.rst
  counter.io.cnt_enable_cpu := io.cntEnable

  val respValidReg  = RegNext(RegNext(doReq, false.B), false.B)
  val respDataPipe0 = RegEnable(counter.io.perip_rdata, doReq)
  val respDataReg   = RegNext(respDataPipe0)
  io.bus.resp_valid := respValidReg
  io.bus.rdata      := respDataReg
}

class JYDPeripheralBridge(
  hasLED:  Boolean = true,
  hasSEG:  Boolean = true,
  hasCNT:  Boolean = true,
  hasUART: Boolean = true)
    extends Module {
  val io = IO(new Bundle {
    val cpu       = SimpleBusIO.Slave
    val dram      = SimpleBusIO.Master
    val led       = SimpleBusIO.Master
    val seg       = SimpleBusIO.Master
    val cnt       = SimpleBusIO.Master
    val uart      = SimpleBusIO.Master
    val cntEnable = Output(Bool())
  })

  io.cpu.dontCareResp()
  io.cpu.req_ready := false.B
  Seq(io.dram, io.led, io.seg, io.cnt, io.uart).foreach(_.dontCareReq())

  val respSelPipe0   = RegInit(0.U(3.W))
  val respSelPipe1   = RegInit(0.U(3.W))
  val respValidPipe0 = RegInit(false.B)
  val respValidPipe1 = RegInit(false.B)
  val cntEnableReg = RegInit(false.B)

  val cntStartCmd = "h80000000".U(32.W)
  val cntStopCmd  = "hffffffff".U(32.W)

  val extraTargets = Seq(
    Option.when(hasLED)((1.U(3.W), io.led, AddrSpace.inRng(io.cpu.addr, AddrSpace.LED))),
    Option.when(hasSEG)((2.U(3.W), io.seg, AddrSpace.inRng(io.cpu.addr, AddrSpace.SEG))),
    Option.when(hasCNT)((3.U(3.W), io.cnt, AddrSpace.inRng(io.cpu.addr, AddrSpace.CNT))),
    Option.when(hasUART)((4.U(3.W), io.uart, AddrSpace.inRng(io.cpu.addr, AddrSpace.SelfExtSpace.UART)))
  ).flatten

  val targetSel = MuxCase(
    0.U(3.W),
    extraTargets.map { case (sel, _, hit) => hit -> sel }
  )

  val targets = Seq((0.U(3.W), io.dram)) ++ extraTargets.map { case (sel, bus, _) => (sel, bus) }

  val selectedReady = MuxLookup(targetSel, io.dram.req_ready)(
    targets.map { case (sel, bus) => sel -> bus.req_ready }
  )
  io.cpu.req_ready := selectedReady

  val reqFire = io.cpu.req_valid && io.cpu.req_ready

  for ((sel, bus) <- targets) {
    val hit = targetSel === sel
    bus.req_valid := io.cpu.req_valid && hit
    bus.addr      := io.cpu.addr
    bus.size      := io.cpu.size
    bus.wdata     := io.cpu.wdata
    bus.wmask     := io.cpu.wmask
    bus.wen       := io.cpu.wen
  }

  when(reqFire && targetSel === 3.U && io.cpu.wen) {
    when(io.cpu.wdata === cntStartCmd) {
      cntEnableReg := true.B
    }.elsewhen(io.cpu.wdata === cntStopCmd) {
      cntEnableReg := false.B
    }
  }

  respSelPipe0   := targetSel
  respSelPipe1   := respSelPipe0
  respValidPipe0 := reqFire
  respValidPipe1 := respValidPipe0

  val respValid = MuxLookup(respSelPipe1, io.dram.resp_valid)(
    targets.map { case (sel, bus) => sel -> bus.resp_valid }
  )
  val respData  = MuxLookup(respSelPipe1, io.dram.rdata)(
    targets.map { case (sel, bus) => sel -> bus.rdata }
  )

  io.cpu.resp_valid := respValidPipe1 && respValid
  io.cpu.rdata      := respData
  io.cntEnable      := cntEnableReg
}

trait HasJYDCPUAndResetPC { this: Module =>
  def resetPC: UInt

  val cpu             = Module(new CPUCoreAsBlackBox)
  val resetPCProvider = Module(new PCProviderAsBlackBox)
  private val socName = this.getClass.getSimpleName.stripSuffix("$")

  assert(resetPCProvider.io.resetPC === resetPC, f"Reset PC should be 0x${resetPC.litValue}%x for $socName")

  cpu.io.clock        := clock
  cpu.io.reset        := reset
  cpu.io.io.interrupt := false.B
}

class JYDSoC(val resetPC: UInt = "h80000000".U) extends Module with HasJYDCPUAndResetPC {
  val irom  = Module(new SimpleBusROM(JYDSoCConfig.iromSizeInByte, JYDSoCConfig.iromBaseAddr))
  val dram  = Module(new SimpleBusMem(JYDSoCConfig.dramSizeInByte, JYDSoCConfig.dramBaseAddr))
  val led   = Module(new SimpleBusOneWordRWDevice(Some("jyd_update_led")))
  val seg   = Module(new SimpleBusOneWordRWDevice(Some("jyd_update_seg")))
  val cnt   = Module(new SimpleBusTimer)
  val uart  = Module(new SimpleBusUART)
  val perip = Module(new JYDPeripheralBridge)

  cpu.io.io.irom <> irom.io
  cpu.io.io.dram <> perip.io.cpu

  perip.io.dram <> dram.io
  perip.io.led <> led.io.bus
  perip.io.seg <> seg.io.bus
  perip.io.cnt <> cnt.io
  perip.io.uart <> uart.io
}

class JYDFPGATop(val resetPC: UInt = "h80000000".U) extends Module with HasJYDCPUAndResetPC {
  val clk_50Mhz = IO(Input(Clock()))
  val led       = IO(Output(UInt(32.W)))
  val seg       = IO(Output(UInt(32.W)))

  val irom     = Module(new SimpleBusFPGAROM(JYDSoCConfig.iromSizeInByte, JYDSoCConfig.iromBaseAddr))
  val dram     = Module(new SimpleBusFPGAMem(JYDSoCConfig.dramSizeInByte, JYDSoCConfig.dramBaseAddr))
  val ledReg = Module(new SimpleBusOneWordRWDevice())
  val segReg = Module(new SimpleBusOneWordRWDevice())
  val cnt      = Module(new SimpleBusFPGACounter)
  val perip    = Module(new JYDPeripheralBridge(hasUART = false))
  val dummyUART = Wire(SimpleBusIO.Slave)

  dummyUART.dontCareReq()
  dummyUART.dontCareResp()

  cpu.io.io.irom <> irom.io
  cpu.io.io.dram <> perip.io.cpu

  perip.io.dram <> dram.io
  perip.io.led <> ledReg.io.bus
  perip.io.seg <> segReg.io.bus
  perip.io.cnt <> cnt.io.bus
  perip.io.uart <> dummyUART

  cnt.io.clk_50Mhz := clk_50Mhz
  cnt.io.rst       := reset.asBool
  cnt.io.cntEnable := perip.io.cntEnable

  led := ledReg.io.value
  seg := segReg.io.value
}
