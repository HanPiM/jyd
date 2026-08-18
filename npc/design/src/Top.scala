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

  val pipelineEpoch              = RegInit(false.B)
  val redirectPendingReg         = RegInit(false.B)
  val redirectEpochReg           = RegInit(false.B)

  val gprs = Module(new RegisterFile(READ_PORTS = 2))

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
  // Redirected fetches use a fixed fall-through prediction below, so the BTB
  // only needs the stable speculative PC and never sees the redirect mailbox.
  btb.io.query.addr       := pc
  bp.io.pc                := pc
  bp.io.historyHit        := btb.io.query.hit
  bp.io.historyTarget     := btb.io.query.target
  bp.io.historyIsJAL      := btb.io.query.isJAL
  bp.io.historyIsBranch   := btb.io.query.isBranch
  bp.io.historyIsReturn   := btb.io.query.isReturn
  bp.io.historyDirectionTaken := btb.io.query.directionTaken
  val branchUpdateFireReg   = RegNext(exu.io.out.fire && exu.io.btbUpdateEn, false.B)
  val branchUpdatePcReg     = RegEnable(exu.io.pc, exu.io.out.fire)
  val branchUpdateTargetReg = RegEnable(exu.io.branchTarget, exu.io.out.fire)
  val branchUpdateIsCallReg = RegEnable(exu.io.isCall, exu.io.out.fire)
  val branchUpdateIsReturnReg = RegEnable(exu.io.isReturn, exu.io.out.fire)
  val branchUpdateIsJALReg = RegEnable(exu.io.isJAL, exu.io.out.fire)
  val branchUpdateIsBranchReg = RegEnable(exu.io.isBranch, exu.io.out.fire)
  val branchUpdateTakenReg = RegEnable(exu.io.branchTaken, exu.io.out.fire)

  bp.io.updateEn       := branchUpdateFireReg
  bp.io.updatePc       := branchUpdatePcReg
  bp.io.updateIsCall   := branchUpdateIsCallReg
  bp.io.updateIsReturn := branchUpdateIsReturnReg

  btb.io.update.en     := branchUpdateFireReg
  btb.io.update.addr   := branchUpdatePcReg
  btb.io.update.target := branchUpdateTargetReg
  // Direct JAL and the selected predictable JALR form reuse the existing
  // unconditional-entry bit, adding neither a BTB data bit nor a module port.
  btb.io.update.isJAL       := branchUpdateIsJALReg
  btb.io.update.isBranch    := branchUpdateIsBranchReg
  btb.io.update.isReturn    := branchUpdateIsReturnReg
  btb.io.update.actualTaken := branchUpdateTakenReg

  val immediateRedirectNow = exu.io.out.valid && exu.io.immediatePredWrong
  val lateRedirectBlocked = lsu.io.in.valid && lsu.io.in.bits.lateBranchRedirect
  val lateRedirectNow = lateRedirectBlocked && lsu.io.in.ready
  val redirectNow = immediateRedirectNow || lateRedirectNow
  dontTouch(lateRedirectNow)
  val redirectPacket      = Wire(new RedirectPacket)
  val redirectPendingFire = ifu.io.pc.fire && redirectPendingReg

  when(redirectNow) {
    redirectPendingReg := true.B
    redirectEpochReg   := ~pipelineEpoch
    pipelineEpoch      := ~pipelineEpoch
  }.elsewhen(redirectPendingFire) {
    redirectPendingReg := false.B
  }

  redirectPacket.valid  := redirectPendingReg
  redirectPacket.target := Mux(!branchUpdateIsBranchReg || branchUpdateTakenReg,
    TrimmedPC.trim(branchUpdateTargetReg), TrimmedPC.trim(branchUpdatePcReg) + 1.U)
  redirectPacket.epoch  := redirectEpochReg

  val activeRedirectValid = Wire(Bool())
  activeRedirectValid := redirectPacket.valid
  dontTouch(activeRedirectValid)
  when(activeRedirectValid) {
    assert(redirectPacket.epoch === pipelineEpoch, "redirect packet epoch must match the active pipeline epoch")
  }

  // The resolving branch only toggles the epoch and fills the registered PC
  // mailbox. Younger instructions are discarded by local epoch filters.
  pc := TrimmedPC.expand(
    Mux(ifu.io.pc.ready, TrimmedPC.trim(nxtPredictedPC), TrimmedPC.trim(pc))
  )

  pcFeedToIFU := Mux(
    redirectPacket.valid,
    TrimmedPC.expand(redirectPacket.target),
    pc
  )

  // A redirected fetch is uncommon and already paid a pipeline flush. Avoid
  // sending its registered address through the asynchronous BTB and RAS in
  // the same cycle; prediction resumes normally from the fall-through PC.
  val fetchPrediction = Wire(new PredBundle)
  fetchPrediction := bp.io.pred
  when(redirectPacket.valid) {
    fetchPrediction.hit  := false.B
    fetchPrediction.take := false.B
    fetchPrediction.pc   := pcFeedToIFU + 4.U
  }
  nxtPredictedPC := fetchPrediction.pc
  ifu.io.predNext := fetchPrediction

  io.irom <> ifu.io.mem
  io.dram <> dataMemBus.io.out
  exu.io.memReq <> dataMemBus.io.exuMemReq
  exu.io.memResp := dataMemBus.io.memResp
  wbu.io.memResp <> dataMemBus.io.memResp
  dcache.io.queryIndex := exu.io.dcache.queryIndex
  dcache.io.readIndex  := exu.io.dcache.readIndex
  dcache.io.queryTag   := exu.io.dcache.queryTag
  exu.io.dcache.hit    := dcache.io.hit && p.enableDCache.B
  exu.io.dcache.readData := dcache.io.readData
  dcache.io.listReverseHitCapture := exu.io.dcache.listReverseHitCapture
  exu.io.dcache.listReverseCapturedHit := dcache.io.listReverseCapturedHit && p.enableDCache.B
  dcache.io.listReversePrefetchAddress := exu.io.dcache.listReversePrefetchAddress
  exu.io.dcache.listReversePrefetchHit := dcache.io.listReversePrefetchHit && p.enableDCache.B
  exu.io.dcache.listReversePrefetchData := dcache.io.listReversePrefetchData
  dcache.io.listFindStart := exu.io.dcache.listFindStart
  dcache.io.listFindConsume := exu.io.dcache.listFindConsume
  dcache.io.listFindAddress := exu.io.dcache.listFindAddress
  dcache.io.listFindTarget := exu.io.dcache.listFindTarget
  dcache.io.listFindDataMode := exu.io.dcache.listFindDataMode
  dcache.io.listFindRequestFire := exu.io.dcache.listFindRequestFire
  dcache.io.listFindMemResponse := exu.io.dcache.listFindMemResponse
  exu.io.dcache.listFindRequest := dcache.io.listFindRequest
  exu.io.dcache.listFindRequestAddress := dcache.io.listFindRequestAddress
  exu.io.dcache.listFindDone := dcache.io.listFindDone
  exu.io.dcache.listFindResult := dcache.io.listFindResult
  dcache.io.dotNStart := exu.io.dcache.dotNStart
  dcache.io.dotNConsume := exu.io.dcache.dotNConsume
  dcache.io.dotNAddressA := exu.io.dcache.dotNAddressA
  dcache.io.dotNAddressB := exu.io.dcache.dotNAddressB
  dcache.io.dotNAddressC := exu.io.dcache.dotNAddressC
  dcache.io.dotNLength := exu.io.dcache.dotNLength
  dcache.io.dotNBitMode := exu.io.dcache.dotNBitMode
  dcache.io.dotNRowMode := exu.io.dcache.dotNRowMode
  dcache.io.dotNRequestFire := exu.io.dcache.dotNRequestFire
  dcache.io.dotNMemResponse := exu.io.dcache.dotNMemResponse
  exu.io.dcache.dotNRequest := dcache.io.dotNRequest
  exu.io.dcache.dotNRequestAddress := dcache.io.dotNRequestAddress
  exu.io.dcache.dotNRequestWrite := dcache.io.dotNRequestWrite
  exu.io.dcache.dotNRequestWriteData := dcache.io.dotNRequestWriteData
  exu.io.dcache.dotNDone := dcache.io.dotNDone
  exu.io.dcache.dotNResult := dcache.io.dotNResult
  // Stage row-store mirror writes separately so the walker never enters the
  // ordinary EXU store-update cone. The external request remains authoritative.
  val dcacheRowStoreFire = exu.io.dcache.dotNRequestFire && dcache.io.dotNRequestWrite
  val dcacheRowStoreUpdate = RegNext(dcacheRowStoreFire && p.enableDCache.B, false.B)
  val dcacheRowStoreAddress = RegEnable(dcache.io.dotNRequestAddress, dcacheRowStoreFire)
  val dcacheRowStoreData = RegEnable(dcache.io.dotNRequestWriteData, dcacheRowStoreFire)
  dcache.io.dataMutation := exu.io.dcache.storeUpdate || exu.io.dcache.fullUpdate
  dcache.io.dataMutationAddr :=
    Mux(exu.io.dcache.fullUpdate, exu.io.dcache.fullUpdateAddr, exu.io.dcache.storeAddress)
  val dcacheStorePortMutation = exu.io.dcache.storeUpdate && p.enableDCache.B
  val dcacheRowStoreMutation = dcacheRowStoreFire && p.enableDCache.B
  val dcacheExternalStoreMutation = dcacheStorePortMutation || dcacheRowStoreMutation
  val dcacheStoreMutation = dcacheExternalStoreMutation || (exu.io.dcache.fullUpdate && p.enableDCache.B)
  val dcacheStoreEpoch    = RegInit(false.B)
  when(dcacheStoreMutation) {
    dcacheStoreEpoch := ~dcacheStoreEpoch
  }
  exu.io.dcache.storeEpoch := dcacheStoreEpoch
  wbu.io.dcacheStoreEpoch  := dcacheStoreEpoch
  val dcacheStoreUpdate = exu.io.dcache.storeUpdate && p.enableDCache.B
  dcache.io.storeUpdate := dcacheStoreUpdate
  dcache.io.storeFull   := exu.io.dcache.storeFull
  dcache.io.storeData   := exu.io.dcache.storeData
  dcache.io.storeMask   := exu.io.dcache.storeMask
  lsu.io.dcacheReadData := dcache.io.readData
  dcache.io.update := p.enableDCache.B && (
    dcacheRowStoreUpdate || exu.io.dcache.fullUpdate || (wbu.io.dcacheUpdate && !dcacheStoreMutation)
  )
  dcache.io.updateValid := Mux(
    dcacheRowStoreUpdate,
    true.B,
    Mux(exu.io.dcache.fullUpdate, exu.io.dcache.fullUpdateValid, true.B)
  )
  dcache.io.updateAddr := Mux(
    dcacheRowStoreUpdate,
    dcacheRowStoreAddress,
    Mux(exu.io.dcache.fullUpdate, exu.io.dcache.fullUpdateAddr, wbu.io.dcacheAddr)
  )
  dcache.io.updateData := Mux(
    dcacheRowStoreUpdate,
    dcacheRowStoreData,
    Mux(exu.io.dcache.fullUpdate, exu.io.dcache.fullUpdateData, wbu.io.dcacheData)
  )
  dcache.io.updateMask := Mux(
    dcacheRowStoreUpdate,
    "b1111".U,
    Mux(exu.io.dcache.fullUpdate, Mux(exu.io.dcache.fullUpdateValid, "b1111".U, 0.U), wbu.io.dcacheMask)
  )

  // JYD memory accepts one request per cycle and responds two cycles later.
  // A store in the intervening cycle changes the generation; a store in the
  // response cycle wins directly over the older refill.
  val previousDcacheStoreMutation = RegNext(dcacheExternalStoreMutation, false.B)
  when(wbu.io.dcacheUpdate && !exu.io.dcache.fullUpdate) {
    assert(!previousDcacheStoreMutation, "A refill must observe an intervening store generation")
  }
  when(dcache.io.update && !exu.io.dcache.fullUpdate) {
    assert(!dcacheStorePortMutation, "A store mutation and WBU refill must be mutually exclusive")
  }

  ifu.io.pc.bits  := pcFeedToIFU
  ifu.io.pc.valid := true.B
  ifu.io.epoch    := pipelineEpoch

  layer.block(DifftestLayer) {
    val exuDifftest = Module(new EXUForDifftest)
    exuDifftest.io.actual.inReady  := exu.io.in.ready
    exuDifftest.io.actual.pc       := exu.io.pc
    exuDifftest.io.actual.nxtPC    := exu.io.nxtPC
    exuDifftest.io.actual.memAddr  := exu.io.out.bits.destAddr
    exuDifftest.io.actual.outValid := exu.io.out.valid
    exuDifftest.io.in.bits := exu.io.in.bits
    exuDifftest.io.in.valid := exu.io.in.valid

    val lsuDifftest = Module(new LSUForDifftest)
    pipelineConnect(exuDifftest.io.out, lsuDifftest.io.in, lsuDifftest.io.out)
    lsuDifftest.io.actualLSU.inReady  := lsu.io.in.ready
    lsuDifftest.io.actualLSU.outValid := lsu.io.out.valid

    val wbuDifftest = Module(new WBUForDifftest)
    pipelineConnect(lsuDifftest.io.out, wbuDifftest.io.in)
  }

  // Fixed slots prevent this small, wide buffer from becoming RAMD32 with a
  // dynamic read address on every decode path. Keep dequeue/backpressure in
  // slot0's write enable instead of its payload data cone.
  val fetchSlot0 = Reg(new FetchedInst)
  val fetchSlot1 = Reg(new FetchedInst)
  val fetchValid0 = RegInit(false.B)
  val fetchValid1 = RegInit(false.B)
  val iduPipe = Wire(Decoupled(new FetchedInst))
  iduPipe.bits := fetchSlot0
  iduPipe.valid := fetchValid0
  val iduEpochMatch = iduPipe.bits.epoch === pipelineEpoch
  idu.io.in.bits := iduPipe.bits
  idu.io.in.valid := iduPipe.valid && iduEpochMatch
  iduPipe.ready := idu.io.in.ready || !iduEpochMatch
  ifu.io.out.ready := !fetchValid1

  val fetchEnq = ifu.io.out.fire
  val fetchDeq = iduPipe.fire
  val fetchShift = fetchDeq && fetchValid1
  val fetchReplaceHead = fetchEnq && (!fetchValid0 || fetchDeq)
  val fetchSlot0Write = fetchShift || fetchReplaceHead
  val fetchSlot0Data = Mux(fetchValid1, fetchSlot1, ifu.io.out.bits)
  when(fetchSlot0Write) {
    fetchSlot0 := fetchSlot0Data
  }
  // A simultaneous dequeue leaves slot1 invalid, so its duplicate payload write is unobservable.
  // This keeps decode backpressure out of the wide slot1 write-enable cone.
  when(fetchEnq && fetchValid0) {
    fetchSlot1 := ifu.io.out.bits
  }
  switch(Cat(fetchEnq, fetchDeq)) {
    is("b10".U) {
      when(fetchValid0) {
        fetchValid1 := true.B
      }.otherwise {
        fetchValid0 := true.B
      }
    }
    is("b01".U) {
      when(fetchValid1) {
        fetchValid1 := false.B
      }.otherwise {
        fetchValid0 := false.B
      }
    }
  }

  val exuPipe       = Wire(Decoupled(new DecodedInst))
  val exuPayloadReg = Reg(new DecodedInst)
  val exuValidReg   = RegInit(false.B)
  // Resolve adjacent-result branches from their registered LSU payload. This
  // keeps the branch comparator out of the ID/EX valid-register input cone.
  exuPipe.bits  := exuPayloadReg
  exuPipe.valid := exuValidReg
  exu.io.in.bits := exuPipe.bits
  exu.io.in.valid := exuPipe.valid
  exuPipe.ready := exu.io.in.ready
  // Adjacent-fast is only set on ordinary branches, whose EXU readiness is
  // exactly downstream readiness. Keep the generic multi-cycle ready mux out of this bubble control.
  val adjacentBranchBubble = exuPipe.valid && exuPipe.bits.info.adjacentFastBranch && exu.io.out.ready
  val exuAllowIn = !exuValidReg || exuPipe.ready
  // Hold the younger IDU instruction for one cycle after an adjacent-result
  // branch. If the registered comparison redirects on the following cycle,
  // the redirect clears this slot. All epoch changes originate from a redirect,
  // so clearing the valid register also keeps the epoch out of every EXU unit's
  // combinational input-valid path.
  idu.io.out.ready := exuAllowIn && !adjacentBranchBubble && !lateRedirectBlocked
  // A same-cycle redirect makes the younger payload architecturally invalid,
  // but it need not suppress the wide payload register write. Keep branch
  // comparison and redirect logic out of every payload bit's write enable.
  when(idu.io.out.ready) {
    exuPayloadReg := idu.io.out.bits
  }
  when(adjacentBranchBubble || lateRedirectBlocked || immediateRedirectNow) {
    exuValidReg := false.B
  }.elsewhen(exuAllowIn) {
    exuValidReg := idu.io.out.valid
  }
  // Keep the ordinary cache index on a dedicated resettable register so it is
  // physically independent of the high-fanout address/result payload. It is
  // captured by the same ID/EX handshake and therefore adds no pipeline cycle.
  val stagedDcacheQueryIndex = RegInit(0.U(10.W))
  when(idu.io.out.fire) {
    stagedDcacheQueryIndex := idu.io.out.bits.info.reg1AddImm(11, 2)
  }
  exu.io.stagedDcacheQueryIndex := stagedDcacheQueryIndex
  pipelineConnect(exu.io.out, lsu.io.in, lsu.io.out)

  when(lateRedirectBlocked) {
    assert(!immediateRedirectNow, "late and immediate redirects must be mutually exclusive")
    assert(!exu.io.in.valid && !exu.io.out.valid, "a late redirect must discard the younger EXU instruction")
    assert(!exu.io.memReq.valid, "a late redirect must suppress the younger EXU memory request")
    assert(!exu.io.dcache.storeUpdate && !exu.io.dcache.fullUpdate,
      "a late redirect must suppress younger DCache mutations")
  }

  idu.io.rvec <> gprs.io.read

  val lsuFwdInfo = ExtractFwdInfoFromLSU(lsu.io.in, dcache.io.readData)
  val lsuFastFwdInfo = ExtractFastFwdInfoFromLSU(lsu.io.in)
  idu.io.wrBackInfo.exu := exu.io.fwd
  idu.io.wrBackInfo.lsu := lsuFwdInfo
  idu.io.wrBackInfo.wbu := ExtractFwdInfoFromWrBack(wbu.io.in, wbu.io.memResp)
  // This shadow token advances under the same allowIn as the LSU/WBU payload.
  // It keeps slow-result lane-valid control out of the IDU-to-fetch CE path.
  val wbuAddressBlocked = RegInit(false.B)
  val wbuCandidate = lsu.io.out.bits
  val wbuCandidateLoadBlocked =
    wbuCandidate.loadResult.valid && !(wbuCandidate.cacheableLoad && wbuCandidate.dcacheHit)
  when(lsu.io.out.ready) {
    wbuAddressBlocked := lsu.io.out.valid && ResultLaneSelect.anyValid(wbuCandidate) && (
      wbuCandidate.resultKind === ResultKind.longArithmetic ||
        wbuCandidate.resultKind === ResultKind.accelerator || wbuCandidateLoadBlocked
    )
  }
  dontTouch(wbuAddressBlocked)
  idu.io.wbuAddressBlocked := wbuAddressBlocked
  when(wbu.io.in.valid) {
    val currentLoadBlocked =
      wbu.io.in.bits.loadResult.valid && !(wbu.io.in.bits.cacheableLoad && wbu.io.in.bits.dcacheHit)
    assert(
      wbuAddressBlocked === (ResultLaneSelect.anyValid(wbu.io.in.bits) && (
        wbu.io.in.bits.resultKind === ResultKind.longArithmetic ||
          wbu.io.in.bits.resultKind === ResultKind.accelerator || currentLoadBlocked
      )),
      "WBU address-block shadow must stay aligned with its payload"
    )
  }
  idu.io.lsuFastAddressFwd := lsuFastFwdInfo
  exu.io.previousStageFwd := lsuFastFwdInfo

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
}
