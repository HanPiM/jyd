package top

import chisel3._
import regfile._
import cpu._
import chisel3.util.circt.dpi._
import chisel3.util._

import axi4._
import common_def._
import btb._
import branchpredictor._
import config._
import dpiwrap.DifftestLayer
import dpiwrap._
import simplebus._

class TopIO extends Bundle {
  val interrupt = Input(Bool())
  val master    = AXI4IO.Master
  val slave     = AXI4IO.Slave
}

class CPUCoreIO extends Bundle {
  val interrupt = Input(Bool())
  val irom      = SimpleBusIO.Master
  val dram      = SimpleBusIO.Master
}

class CPUCoreAsBlackBox extends BlackBox {
  override def desiredName: String = "CPUCore"
  val io = IO(new Bundle {
    val clock = Input(Clock())
    val reset = Input(Bool())
    val io    = new CPUCoreIO
  })
}

class PCProviderAsBlackBox extends BlackBox {
  override def desiredName: String = "CPUTop_ResetPCProvider"
  val io = IO(new Bundle {
    val resetPC = Output(Types.UWord)
  })
}

class CPUTop(parm: CPUParameters) extends Module {
  val io = IO(new TopIO)
  implicit val p: CPUParameters = parm

  dontTouch(io)

  val core = Module(new CPUCore)
  core.io.interrupt := io.interrupt

  val memBridge = Module(new DualSimpleBusToAXI4)
  core.io.irom <> memBridge.io.ifu
  core.io.dram <> memBridge.io.lsu
  memBridge.io.out <> io.master

  io.slave := DontCare

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
}

class CPUTop_ResetPCProvider extends BlackBox with HasBlackBoxInline {
  val io      = IO(new Bundle {
    val resetPC = Output(Types.UWord)
  })
  val pcMacro = name + "_RESET_PC"
  setInline(
    s"${name}.v",
    s"""
       |`ifndef ${pcMacro}
       |  `define ${pcMacro} 32'h80000000
       |`endif
       |module ${name}(
       |  output [31:0] resetPC
       |);
       |  assign resetPC = `$pcMacro;
       |endmodule
     """.stripMargin
  )
}

class DualSimpleBusToAXI4 extends Module {
  val io = IO(new Bundle {
    val ifu = SimpleBusIO.Slave
    val lsu = SimpleBusIO.Slave
    val out = AXI4IO.Master
  })

  io.out.dontCareAW()
  io.out.dontCareW()
  io.out.dontCareB()
  io.out.dontCareAR()
  io.out.dontCareR()

  io.ifu.dontCareReq()
  io.ifu.dontCareResp()
  io.lsu.dontCareReq()
  io.lsu.dontCareResp()

  object State extends ChiselEnum {
    val idle, sendAR, waitR, sendAWW, waitB = Value
  }
  val state = RegInit(State.idle)

  val selLSU   = RegInit(false.B)
  val reqAddr  = Reg(UInt(32.W))
  val reqSize  = Reg(UInt(3.W))
  val reqWData = Reg(UInt(32.W))
  val reqWMask = Reg(UInt(4.W))
  val reqWEn   = Reg(Bool())

  val awSent = RegInit(false.B)
  val wSent  = RegInit(false.B)

  val takeLSU  = io.lsu.req_valid
  val selAddr  = Mux(takeLSU, io.lsu.addr, io.ifu.addr)
  val selSize  = Mux(takeLSU, io.lsu.size, io.ifu.size)
  val selWData = Mux(takeLSU, io.lsu.wdata, io.ifu.wdata)
  val selWMask = Mux(takeLSU, io.lsu.wmask, io.ifu.wmask)
  val selWEn   = Mux(takeLSU, io.lsu.wen, io.ifu.wen)
  val hasReq   = io.lsu.req_valid || io.ifu.req_valid

  io.lsu.req_ready := state === State.idle
  io.ifu.req_ready := (state === State.idle) && !takeLSU

  state := MuxLookup(state, State.idle)(
    Seq(
      State.idle -> Mux(hasReq, Mux(selWEn, State.sendAWW, State.sendAR), State.idle),
      State.sendAR -> Mux(io.out.arready, State.waitR, State.sendAR),
      State.waitR -> Mux(io.out.rvalid, State.idle, State.waitR),
      State.sendAWW -> Mux((awSent || io.out.awready) && (wSent || io.out.wready), State.waitB, State.sendAWW),
      State.waitB -> Mux(io.out.bvalid, State.idle, State.waitB)
    )
  )

  when(state === State.idle && hasReq) {
    selLSU   := takeLSU
    reqAddr  := selAddr
    reqSize  := selSize
    reqWData := selWData
    reqWMask := selWMask
    reqWEn   := selWEn
    awSent   := false.B
    wSent    := false.B
  }

  when(state === State.sendAR) {
    io.out.arvalid := true.B
    io.out.araddr  := reqAddr
    io.out.arid    := 0.U
    io.out.arlen   := 0.U
    io.out.arsize  := reqSize
    io.out.arburst := AXI4IO.BurstType.INCR
  }

  when(state === State.waitR) {
    io.out.rready := true.B
    when(io.out.rvalid) {
      when(selLSU) {
        io.lsu.resp_valid := true.B
        io.lsu.rdata      := io.out.rdata
      }.otherwise {
        io.ifu.resp_valid := true.B
        io.ifu.rdata      := io.out.rdata
      }
    }
  }

  when(state === State.sendAWW) {
    io.out.awvalid := !awSent
    io.out.awaddr  := reqAddr
    io.out.awid    := 0.U
    io.out.awlen   := 0.U
    io.out.awsize  := reqSize
    io.out.awburst := AXI4IO.BurstType.INCR

    io.out.wvalid := !wSent
    io.out.wdata  := reqWData
    io.out.wstrb  := reqWMask
    io.out.wlast  := true.B

    when(io.out.awvalid && io.out.awready) {
      awSent := true.B
    }
    when(io.out.wvalid && io.out.wready) {
      wSent := true.B
    }
  }

  when(state === State.waitB) {
    io.out.bready := true.B
    when(io.out.bvalid) {
      io.lsu.resp_valid := selLSU
      io.ifu.resp_valid := !selLSU
    }
  }
}

class CPUCore(
  implicit p: CPUParameters)
    extends Module {
  val io = IO(new CPUCoreIO)
  dontTouch(io)
  io := DontCare

  val redirectValid      = Wire(Bool())
  val redirectTarget     = Wire(Types.UWord)
  val activeRedirectValid = Wire(Bool())
  val activeRedirectTarget = Wire(Types.UWord)
  val redirectPendingReg = RegInit(false.B)
  val redirectTargetReg  = Reg(Types.UWord)

  val gprs = Module(new RegisterFile(READ_PORTS = 2))
  val csrs = Module(new ControlStatusRegisterFile())

  val ifu = Module(new IFU)
  val idu = Module(new IDU)
  val exu = Module(new EXU)
  val lsu = Module(new LSU)
  val wbu = Module(new WBU)
  val dataMemBus = Module(new DataMemBusCombiner)

  val resetPCProvider = Module(new CPUTop_ResetPCProvider)
  val INIT_PC         = resetPCProvider.io.resetPC

  val pc             = RegInit(INIT_PC)
  val nxtPredictedPC = Wire(Types.UWord)
  dontTouch(nxtPredictedPC)

  if (Config.useBTBAndBP) {
    val btb = Module(new BranchTargetBuffer)
    val bp  = Module(new BranchPredictor)
    btb.io.query.addr   := pc
    bp.io.pc            := pc
    bp.io.historyHit    := btb.io.query.hit
    bp.io.historyTarget := btb.io.query.target
    bp.io.historyIsJAL  := btb.io.query.isJAL

    btb.io.update.en     := exu.io.out.valid && exu.io.jmpHappen
    btb.io.update.addr   := exu.io.pc
    btb.io.update.target := exu.io.nxtPC
    btb.io.update.isJAL  := exu.io.isJAL

    nxtPredictedPC := bp.io.predictTarget
  } else {
    nxtPredictedPC := pc + 4.U
  }

  ifu.io.predictedNextPC := nxtPredictedPC

  val isIFUAckRedirectTarget = Wire(Bool())
  redirectValid  := exu.io.out.valid && exu.io.predWrong
  redirectTarget := exu.io.nxtPC

  when(redirectValid) {
    redirectPendingReg := true.B
    redirectTargetReg  := redirectTarget
  }.elsewhen(isIFUAckRedirectTarget) {
    redirectPendingReg := false.B
  }

  activeRedirectValid  := redirectValid || redirectPendingReg
  activeRedirectTarget := Mux(redirectValid, redirectTarget, redirectTargetReg)

  // NOTICE: for IFU
  // must wait until IFU accepts the jump target (pc fire) can not
  // just check the valid, sometimes IFU still fetching old wrong
  // target, if think it meets the correct target, then the wrong
  // target will be passed to IDU since that time isWrongPred is unset.
  isIFUAckRedirectTarget := ifu.io.pc.fire && activeRedirectValid && (ifu.io.pc.bits === activeRedirectTarget)
  dontTouch(isIFUAckRedirectTarget)
  dontTouch(activeRedirectValid)
  dontTouch(activeRedirectTarget)

  pc := Mux(
    ifu.io.pc.ready,
    // Sometimes although jump,
    // target is near current pc and IFU just meets it
    Mux(activeRedirectValid && (!isIFUAckRedirectTarget), activeRedirectTarget, nxtPredictedPC),
    pc
  )

  io.irom <> ifu.io.mem
  io.dram <> dataMemBus.io.out
  exu.io.memReq <> dataMemBus.io.exuMemReq
  lsu.io.memResp <> dataMemBus.io.lsuResp

  ifu.io.pc.bits  := pc
  ifu.io.pc.valid := true.B

  layer.block(DifftestLayer) {
    val iduOut = Wire(Decoupled(new DecodedInst))
    iduOut.valid := idu.io.out.valid
    iduOut.bits  := idu.io.out.bits

    val exuDifftest = Module(new EXUForDifftest)
    exuDifftest.io.actual.inReady  := exu.io.in.ready
    exuDifftest.io.actual.pc       := exu.io.pc
    exuDifftest.io.actual.nxtPC    := exu.io.nxtPC
    exuDifftest.io.actual.memAddr  := exu.io.out.bits.destAddr
    exuDifftest.io.actual.outValid := exu.io.out.valid
    pipelineConnect(iduOut, exuDifftest.io.in, exuDifftest.io.out, kill = redirectValid)

    val lsuDifftest = Module(new LSUForDifftest)
    pipelineConnect(exuDifftest.io.out, lsuDifftest.io.in, lsuDifftest.io.out)
    lsuDifftest.io.actualLSU.inReady  := lsu.io.in.ready
    lsuDifftest.io.actualLSU.outValid := lsu.io.out.valid

    val wbuDifftest = Module(new WBUForDifftest)
    pipelineConnect(lsuDifftest.io.out, wbuDifftest.io.in)
  }

  pipelineConnect(ifu.io.out, idu.io.in, idu.io.out, kill = activeRedirectValid)
  pipelineConnect(idu.io.out, exu.io.in, exu.io.out, kill = redirectValid)
  pipelineConnect(exu.io.out, lsu.io.in, lsu.io.out)

  idu.io.rvec <> gprs.io.read
  idu.io.csrRead <> csrs.io.read
  idu.io.csrJmpTarget.mepc := csrs.io.mepc
  idu.io.csrJmpTarget.mtvec := csrs.io.mtvec

  idu.io.wrBackInfo.exu := exu.io.fwd
  idu.io.wrBackInfo.lsu := ExtractFwdInfoFromLSU(lsu.io.in)
  idu.io.wrBackInfo.wbu := ExtractFwdInfoFromWrBack(wbu.io.in)

  idu.io.flush := false.B

  StageLogger(
    clock,
    StageLogConst.Event.flush,
    StageLogConst.Stage.idu,
    activeRedirectValid && idu.io.in.valid,
    idu.io.in.bits.iid
  )

  val foo = Wire(Decoupled(Bool()))
  foo       := DontCare
  foo.ready := true.B
  foo.valid := true.B
  pipelineConnect(lsu.io.out, wbu.io.in, foo)

  gprs.io.write <> wbu.io.gpr
  csrs.io.write <> wbu.io.csr
  csrs.io.is_ecall := wbu.io.is_ecall
}
