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
      State.idle    -> Mux(hasReq, Mux(selWEn, State.sendAWW, State.sendAR), State.idle),
      State.sendAR  -> Mux(io.out.arready, State.waitR, State.sendAR),
      State.waitR   -> Mux(io.out.rvalid, State.idle, State.waitR),
      State.sendAWW -> Mux((awSent || io.out.awready) && (wSent || io.out.wready), State.waitB, State.sendAWW),
      State.waitB   -> Mux(io.out.bvalid, State.idle, State.waitB)
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

  val redirectNow         = Wire(Bool())
  val redirectNowTarget   = Wire(Types.UWord)
  val activeRedirectValid = Wire(Bool())
  val redirectPendingFire = Wire(Bool())
  val redirectPendingReg  = RegInit(false.B)
  val redirectTargetReg   = Reg(Types.UWord)

  val gprs = Module(new RegisterFile(READ_PORTS = 2))
  val csrs = Module(new ControlStatusRegisterFile())

  val ifu        = Module(new IFU)
  val idu        = Module(new IDU)
  val exu        = Module(new EXU)
  val lsu        = Module(new LSU)
  val wbu        = Module(new WBU)
  val dataMemBus = Module(new DataMemBusCombiner)
  val dcache     = Module(new DCache)

  val resetPCProvider = Module(new CPUTop_ResetPCProvider)
  val INIT_PC         = resetPCProvider.io.resetPC

  val pc             = RegInit(INIT_PC)
  val nxtPredictedPC = Wire(Types.UWord)
  dontTouch(nxtPredictedPC)

  val pcFeedToIFU = Wire(Types.UWord)

  val btb = Module(new BranchTargetBuffer)
  val bp  = Module(new BranchPredictor)
  // A pending redirect is a registered PC mailbox. Query prediction state
  // with the address actually presented to IFU, not the speculative pcReg
  // hidden behind the mailbox.
  btb.io.query.addr       := pcFeedToIFU
  bp.io.pc                := pcFeedToIFU
  bp.io.historyHit        := btb.io.query.hit
  bp.io.historyTarget     := btb.io.query.target
  bp.io.historyIsJAL      := btb.io.query.isJAL
  bp.io.historyIsBranch   := btb.io.query.isBranch
  bp.io.historyDirectionTaken := btb.io.query.directionTaken
  bp.io.historyIsBackward := btb.io.query.isBackward

  btb.io.update.en         := RegNext(exu.io.out.valid && exu.io.btbUpdateEn)
  btb.io.update.addr       := RegNext(exu.io.pc)
  btb.io.update.target     := RegNext(exu.io.branchTarget)
  // Direct JAL and the selected predictable JALR form reuse the existing
  // unconditional-entry bit, adding neither a BTB data bit nor a module port.
  btb.io.update.isJAL      := RegNext(exu.io.isJAL)
  btb.io.update.isBranch   := RegNext(exu.io.isBranch)
  btb.io.update.actualTaken := RegNext(exu.io.branchTaken)
  btb.io.update.isBackward := RegNext(exu.io.branchBackward)

  nxtPredictedPC := bp.io.pred.pc

  ifu.io.predNext := bp.io.pred

  redirectNow         := exu.io.in.valid && exu.io.predWrong
  redirectNowTarget   := exu.io.nxtPC
  redirectPendingFire := ifu.io.pc.fire && redirectPendingReg

  when(redirectNow) {
    redirectPendingReg := true.B
    redirectTargetReg  := redirectNowTarget
  }.elsewhen(redirectPendingFire) {
    redirectPendingReg := false.B
  }

  activeRedirectValid := redirectNow || redirectPendingReg
  dontTouch(activeRedirectValid)

  // redirectNow still flushes younger instructions in the resolving cycle,
  // while the registered mailbox presents its target to IFU in the next
  // cycle. This is the same earliest visible target cycle as writing pc here,
  // but removes the branch comparator from pcReg's clock-enable path.
  pc := TrimmedPC.expand(
    Mux(ifu.io.pc.ready, TrimmedPC.trim(nxtPredictedPC), TrimmedPC.trim(pc))
  )

  pcFeedToIFU := Mux(redirectPendingReg, redirectTargetReg, pc)

  io.irom <> ifu.io.mem
  io.dram <> dataMemBus.io.out
  exu.io.memReq <> dataMemBus.io.exuMemReq
  wbu.io.memResp <> dataMemBus.io.memResp
  dcache.io.queryAddr  := exu.io.dcache.queryAddr
  exu.io.dcache.hit    := dcache.io.hit && p.enableDCache.B
  exu.io.dcache.lateReadData := dcache.io.lateReadData
  val dcacheStoreMutation = exu.io.dcache.storeUpdate && p.enableDCache.B
  val dcacheStoreEpoch    = RegInit(false.B)
  when(dcacheStoreMutation) {
    dcacheStoreEpoch := ~dcacheStoreEpoch
  }
  exu.io.dcache.storeEpoch := dcacheStoreEpoch
  wbu.io.dcacheStoreEpoch  := dcacheStoreEpoch
  val dcacheStoreUpdate = exu.io.dcache.storeUpdate && p.enableDCache.B
  dcache.io.storeUpdate := dcacheStoreUpdate
  dcache.io.storeData   := exu.io.dcache.storeData
  dcache.io.storeMask   := exu.io.dcache.storeMask
  lsu.io.dcacheReadData := dcache.io.readData
  dcache.io.update     := wbu.io.dcacheUpdate && p.enableDCache.B && !dcacheStoreMutation
  dcache.io.updateAddr := wbu.io.dcacheAddr
  dcache.io.updateData := wbu.io.dcacheData
  dcache.io.updateMask := wbu.io.dcacheMask

  // JYD memory accepts one request per cycle and responds two cycles later.
  // A store in the intervening cycle changes the generation; a store in the
  // response cycle wins directly over the older refill.
  val previousDcacheStoreMutation = RegNext(dcacheStoreMutation, false.B)
  when(wbu.io.dcacheUpdate) {
    assert(!previousDcacheStoreMutation, "A refill must observe an intervening store generation")
  }
  when(dcache.io.update) {
    assert(!dcacheStoreMutation, "A store mutation and WBU refill must be mutually exclusive")
  }

  ifu.io.pc.bits  := pcFeedToIFU
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
    pipelineConnect(iduOut, exuDifftest.io.in, exuDifftest.io.out, kill = redirectNow)

    val lsuDifftest = Module(new LSUForDifftest)
    pipelineConnect(exuDifftest.io.out, lsuDifftest.io.in, lsuDifftest.io.out)
    lsuDifftest.io.actualLSU.inReady  := lsu.io.in.ready
    lsuDifftest.io.actualLSU.outValid := lsu.io.out.valid

    val wbuDifftest = Module(new WBUForDifftest)
    pipelineConnect(lsuDifftest.io.out, wbuDifftest.io.in)
  }

  pipelineConnect(ifu.io.out, idu.io.in, idu.io.out, kill = activeRedirectValid)
  pipelineConnect(idu.io.out, exu.io.in, exu.io.out, kill = redirectNow)
  pipelineConnect(exu.io.out, lsu.io.in, lsu.io.out)

  idu.io.rvec <> gprs.io.read
  idu.io.csrRead <> csrs.io.read
  idu.io.csrJmpTarget.mepc  := csrs.io.mepc
  idu.io.csrJmpTarget.mtvec := csrs.io.mtvec

  val lsuFwdInfo = ExtractFwdInfoFromLSU(lsu.io.in)
  val dcacheFwdInfo = Wire(new DCacheForwardInfo)
  dcacheFwdInfo.valid := lsu.io.in.valid && lsu.io.in.bits.cacheableLoad && lsu.io.in.bits.dcacheHit
  dcacheFwdInfo.addr  := lsu.io.in.bits.exuWriteBack.gpr.addr
  dcacheFwdInfo.data  := lsu.io.in.bits.lateLoadData
  val wbuRawFwdInfo = Wire(new Reg1AddImmWbuRawInfo)
  wbuRawFwdInfo.dataValid := wbu.io.in.valid && !wbu.io.in.bits.isLoad
  wbuRawFwdInfo.data      := wbu.io.in.bits.gpr.data(21, 0)
  idu.io.wrBackInfo.exu := exu.io.fwd
  idu.io.lateLoadProducer := exu.io.lateLoadProducer
  idu.io.lateAddFwd := exu.io.lateAddFwd
  idu.io.exuAddFwd := exu.io.addFwd
  idu.io.wrBackInfo.lsu := lsuFwdInfo
  idu.io.wrBackInfo.wbu := ExtractFwdInfoFromWrBack(wbu.io.in, wbu.io.memResp)
  idu.io.dcacheFwd := dcacheFwdInfo
  idu.io.reg1AddImmWbuRawInfo := wbuRawFwdInfo

  val lateLoadLSUWidthSupported =
    lsu.io.in.bits.func3t === "b000".U || lsu.io.in.bits.func3t === "b001".U ||
      lsu.io.in.bits.func3t === "b010".U || lsu.io.in.bits.func3t === "b100".U ||
      lsu.io.in.bits.func3t === "b101".U
  val lateLoadLSUValid = lsu.io.in.valid && lsu.io.in.bits.isLoad && lateLoadLSUWidthSupported
  exu.io.lateLoadLSU.valid := lateLoadLSUValid
  exu.io.lateLoadLSU.dataValid :=
    lateLoadLSUValid && lsu.io.in.bits.cacheableLoad && lsu.io.in.bits.dcacheHit
  // lateLoadData was selected and extended from the asynchronous shadow in C0,
  // then crossed the existing EXU-to-LSU pipeline register. Do not reconnect
  // the C1 consumer directly to either shadow output or synchronous BRAM.
  exu.io.lateLoadLSU.data := lsu.io.in.bits.lateLoadData

  val lateLoadWBUWidthSupported =
    wbu.io.in.bits.lsuFunc3t === "b000".U || wbu.io.in.bits.lsuFunc3t === "b001".U ||
      wbu.io.in.bits.lsuFunc3t === "b010".U || wbu.io.in.bits.lsuFunc3t === "b100".U ||
      wbu.io.in.bits.lsuFunc3t === "b101".U
  val lateLoadWBUValid = wbu.io.in.valid && wbu.io.in.bits.isLoad && lateLoadWBUWidthSupported
  exu.io.lateLoadWBU.valid := lateLoadWBUValid
  exu.io.lateLoadWBU.dataValid := lateLoadWBUValid && wbu.io.memResp.valid
  exu.io.lateLoadWBU.data :=
    ExtLoadData(wbu.io.memResp.bits, wbu.io.in.bits.lsuAddrOffset, wbu.io.in.bits.lsuFunc3t)

  idu.io.pipelineFlush := activeRedirectValid

  StageLogger(
    clock,
    StageLogConst.Event.flush,
    StageLogConst.Stage.ifu,
    activeRedirectValid && ifu.io.out.valid,
    ifu.io.out.bits.iid
  )
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
