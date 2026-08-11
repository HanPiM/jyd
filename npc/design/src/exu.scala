package cpu
import chisel3._
import chisel3.util._
import common_def._
import busfsm._

import regfile._
import cpu.alu._
import axi4._
import dpiwrap._
import dpiwrap.ClockedCallVoidDPIC

object GenMemWMask {
  def apply(offset: UInt, func3t: UInt): UInt = {
    val memOpIsWord = func3t(1)
    val memOpIsHalf = (~func3t(1)) && func3t(0)
    val memOpIsByte = (~func3t(1)) && (~func3t(0))

    val isLW = memOpIsWord

    // lw : always
    // lh : when offset==0, is lo half
    // lb : when offset==0, is b[0]
    val memWMaskB0 = (offset(1, 0) === 0.U)
    // lh : when offset==0
    // lb : when offset==1
    //
    // offset can be 0 or 1
    val memWMaskB1 = (~offset(1) && Mux(memOpIsByte, offset(0), ~offset(0)))

    // lh : when offset==2, is hi half
    // lb : when offset==2
    val memWMaskB2 = (offset(1, 0) === 2.U)

    // lh : when offset==2
    // lb : when offset==3
    // offset can be 2 or 3
    val memWMaskB3 = (offset(1) && Mux(memOpIsByte, offset(0), ~offset(0)))

    Cat(memWMaskB3, memWMaskB2, memWMaskB1, memWMaskB0) | Fill(4, isLW)
  }
}

object GenMemWData {
  def apply(offset: UInt, data: UInt): UInt = {
    val memWData = MuxLookup(offset(1, 0), 0.U(32.W))(
      Seq(
        0.U -> data,
        // only byte align case can store to odd byte
        // so only need to shift lo 8 bits
        1.U -> 0.U(16.W) ## data(7, 0) ## 0.U(8.W),
        2.U -> Cat(data(15, 0), 0.U(16.W)),
        3.U -> Cat(data(7, 0), 0.U(24.W))
      )
    )
    memWData
  }
}

class EXU(
  implicit p: CPUParameters)
    extends Module {
  val io = IO(new Bundle {
    val in          = Flipped(Decoupled(new DecodedInst))
    val jmpHappen   = Output(Bool())
    val isJAL       = Output(Bool())
    val isBranch    = Output(Bool())
    val isReturn    = Output(Bool())
    val isCall      = Output(Bool())
    val branchTaken = Output(Bool())
    val btbUpdateEn = Output(Bool())

    val predWrong = Output(Bool())

    val branchTarget   = Output(Types.UWord)
    val staticTarget   = Output(Types.UWord)

    val pc    = Output(Types.UWord)
    val nxtPC = Output(Types.UWord)

    val fwd = Output(new WrBackForwardInfo)
    val lateLoadProducer = Output(new LateLoadProducerInfo)
    val lateLoadLSU = Input(new LateLoadSourceInfo)
    val lateLoadWBU = Input(new LateLoadSourceInfo)
    val lateLoadWBURawData = Input(Types.UWord)
    val lateLoadWBUFunc3   = Input(UInt(3.W))
    val lateLoadWBUOffset  = Input(UInt(2.W))
    val previousStageFwd = Input(new WrBackForwardInfo)

    val dcache = new Bundle {
      val hit        = Input(Bool())
      val lateReadData = Input(Types.UWord)
      val storeEpoch = Input(Bool())
      val queryIndex = Output(UInt(10.W))
      val queryTag   = Output(UInt(7.W))
      val storeUpdate = Output(Bool())
      val storeFull   = Output(Bool())
      val storeData   = Output(Types.UWord)
      val storeMask   = Output(UInt(4.W))
      val fullUpdate     = Output(Bool())
      val fullUpdateValid = Output(Bool())
      val fullUpdateAddr = Output(Types.UWord)
      val fullUpdateData = Output(Types.UWord)
    }

    val memReq = Decoupled(new MemReq)
    val memResp = Input(Valid(Types.UWord))
    val out    = Decoupled(new LSUInput)
  })

  val fastInteger = Module(new FastIntegerALU)
  // Keep the historical instance name for simulator hierarchy probes. This
  // module is now only the special cluster; fast integer execution is separate.
  val alu = Module(new SpecialExecutionCluster)

  fastInteger.io.out.ready := io.out.ready
  alu.io.out.ready := io.out.ready

  val fast_in = fastInteger.io.in.bits
  val special_in = alu.io.in.bits
  val dinst  = io.in.bits
  val func3t = dinst.code(14, 12)
  val func7t = dinst.code(31, 25)

  val isFmtI          = InstFmt.hasSame(dinst.info.fmt, InstFmt.imm)
  val isTypSys        = InstType.hasSame(dinst.info.typ, InstType.system)
  val isTypLoad       = InstType.hasSame(dinst.info.typ, InstType.load)
  val isTypStore      = InstType.hasSame(dinst.info.typ, InstType.store)
  val isTypAUIPC      = InstType.hasSame(dinst.info.typ, InstType.auipc)
  val isTypJAL        = InstType.hasSame(dinst.info.typ, InstType.jal)
  val isTypJALR       = InstType.hasSame(dinst.info.typ, InstType.jalr)
  val isTypBranch     = InstType.hasSame(dinst.info.typ, InstType.branch)
  val isTypArithmetic = InstType.hasSame(dinst.info.typ, InstType.arithmetic)
  val isTypLUI        = InstType.hasSame(dinst.info.typ, InstType.lui)

  val isAdd = isTypArithmetic && func3t === 0.U && (isFmtI || func7t === 0.U)

  val isNumericDfa = dinst.info.numericDfaValid

  object XlrevState extends ChiselEnum {
    val idle, loadRequest, loadResponse, storeRequest, storeResponse, done = Value
  }
  val xlrevState   = RegInit(XlrevState.idle)
  val xlrevCurrent = Reg(Types.UWord)
  val xlrevPrevious = Reg(Types.UWord)
  val xlrevChainPrevious = Reg(Types.UWord)
  val xlrevNext    = Reg(Types.UWord)
  val xlrevResult  = Reg(Types.UWord)
  val xlrevSingleStoreCommitted = RegInit(false.B)
  // xlrev mutates memory behind the cache. Once it has run, bypass cached
  // loads until reset instead of updating every reversed node through the
  // cache RAM write ports.
  val dcachePoisonedByXlrev = RegInit(false.B)
  val isXlrev      = dinst.info.xlrevValid
  val isXlrevSingle = dinst.info.xlrevSingle
  val isXlrevChain = dinst.info.xlrevChain
  val isXlrevLoop = dinst.info.xlrevLoop
  val xlrevLoopTaken = RegInit(false.B)

  object XmsumState extends ChiselEnum {
    val idle, request, response, finalizeResult, done = Value
  }
  val xmsumState  = RegInit(XmsumState.idle)
  val xmsumAddress = Reg(Types.UWord)
  val xmsumSize   = Reg(UInt(16.W))
  val xmsumRow    = Reg(UInt(16.W))
  val xmsumColumn = Reg(UInt(16.W))
  val xmsumClip   = Reg(SInt(32.W))
  val xmsumTmp    = Reg(UInt(32.W))
  val xmsumPreviousClipped = Reg(Bool())
  val xmsumPrev   = Reg(UInt(32.W))
  val xmsumRet    = Reg(UInt(16.W))
  val xmsumRetClipped = Reg(Bool())
  val xmsumRetIncreased = Reg(Bool())
  val xmsumRetPending = RegInit(false.B)
  val xmsumResult = Reg(Types.UWord)
  val isXmsum     = dinst.info.xmsumValid

  val xmsumRetPlusOne = xmsumRet + 1.U
  val xmsumRetPlusTen = xmsumRet + 10.U
  val xmsumNextRet = Mux(xmsumRetClipped, xmsumRetPlusTen, Mux(xmsumRetIncreased, xmsumRetPlusOne, xmsumRet))

  // DCache hits still resolve a dependent consumer in this cycle.  A miss or
  // peripheral load reaches WBU later; capture that response first so the
  // memory-response mux cannot drive branch resolution and pipeline flush in
  // the same cycle.
  val capturedLateLoadValid = RegInit(false.B)
  val capturedLateLoadData  = Reg(Types.UWord)
  val captureLateLoadWBU =
    io.in.valid && (dinst.info.lateLoadRs1 || dinst.info.lateLoadRs2) &&
      !io.lateLoadLSU.valid && io.lateLoadWBU.valid && io.lateLoadWBU.dataValid && !capturedLateLoadValid

  when(!io.in.valid || io.in.fire) {
    capturedLateLoadValid := false.B
  }.elsewhen(captureLateLoadWBU) {
    capturedLateLoadValid := true.B
    capturedLateLoadData  := io.lateLoadWBU.data
  }

  // A late-load operand first looks at LSU. This priority is required when an
  // older instruction happens to target the same register. A miss keeps the
  // payload held until the WBU response has crossed the capture register.
  def resolveLateLoadOperand(late: Bool, normalData: UInt): (Bool, UInt) = {
    val lsuMatch = late && io.lateLoadLSU.valid
    val capturedMatch = late && !lsuMatch && capturedLateLoadValid
    val ready = !late || (lsuMatch && io.lateLoadLSU.dataValid) || capturedMatch
    val data = Mux(lsuMatch, io.lateLoadLSU.data, Mux(capturedMatch, capturedLateLoadData, normalData))
    (ready, data)
  }

  val baseRegV1 = dinst.info.reg1
  val baseRegV2 = dinst.info.reg2
  val fastRegV1 = Mux(dinst.info.fastAluRs1, io.previousStageFwd.data, baseRegV1)
  val fastRegV2 = Mux(dinst.info.fastAluRs2, io.previousStageFwd.data, baseRegV2)
  val branchRegV1 = Mux(dinst.info.fastBranchRs1, io.previousStageFwd.data, baseRegV1)
  val branchRegV2 = Mux(dinst.info.fastBranchRs2, io.previousStageFwd.data, baseRegV2)
  val storeRegV2 = Mux(dinst.info.fastStoreRs2, io.previousStageFwd.data, baseRegV2)
  // Preserve each adjacent-result selector as a local 2:1 boundary. Without
  // these nets Vivado can absorb the selector into every downstream ALU or
  // comparator LUT, turning the one-bit token into a high-fanout control net.
  dontTouch(fastRegV1)
  dontTouch(fastRegV2)
  dontTouch(branchRegV1)
  dontTouch(branchRegV2)
  dontTouch(storeRegV2)

  val (lateRs1Ready, lateRegV1) =
    resolveLateLoadOperand(dinst.info.lateLoadRs1, branchRegV1)
  val (lateRs2Ready, lateRegV2) =
    resolveLateLoadOperand(dinst.info.lateLoadRs2, branchRegV2)
  val hasLateLoadOperand = dinst.info.lateLoadRs1 || dinst.info.lateLoadRs2
  val lateDataReady = lateRs1Ready && lateRs2Ready
  val lateDataReadyFromLSU = hasLateLoadOperand && io.lateLoadLSU.dataValid

  // Speculative late-load consumers are limited to fixed ANDI 1 and SRLI 1,
  // whose compact result paths avoid a general ALU or adder.
  val lateForwardRegV1 = Mux(dinst.info.lateLoadRs1, io.lateLoadLSU.data, dinst.info.reg1)
  // Reuse the IDU invariant instead of repeating a 32-bit immediate
  // comparison in the EXU-to-IDU ready/forwarding cone.
  val isLateLoadAndi1 = hasLateLoadOperand && func3t === "b111".U
  val isLateLoadSrli1 = hasLateLoadOperand && func3t === "b101".U
  val lateBitResult = Mux(
    isLateLoadAndi1,
    Cat(0.U(31.W), lateRegV1(0)),
    Cat(0.U(1.W), lateRegV1(31, 1))
  )
  val lateBitForwardResult = Mux(
    isLateLoadAndi1,
    Cat(0.U(31.W), lateForwardRegV1(0)),
    Cat(0.U(1.W), lateForwardRegV1(31, 1))
  )
  val reg_v1       = baseRegV1
  val reg_v2       = baseRegV2
  val xlrevActiveCurrent = Mux(isXlrevLoop, xlrevResult, reg_v1)

  // A numeric-token scan consumes at most the configured data-region size, so 16-bit
  // counters retain the full architectural result while avoiding sixteen
  // unnecessary 32-bit incrementers in the timing-sensitive EXU.
  val xdfaCounters = RegInit(VecInit(Seq.fill(8)(0.U(12.W))))
  val xdfaFinalCounters = RegInit(VecInit(Seq.fill(8)(0.U(16.W))))
  val xdfaPendingMask = RegInit(0.U(8.W))
  object NumericDfaState extends ChiselEnum {
    val idle, request, response, processLow, processHigh, commit, done = Value
  }
  val xdfaWordState = RegInit(NumericDfaState.idle)
  val xdfaWordStartState = Reg(UInt(3.W))
  val xdfaWordAddress = Reg(Types.UWord)
  val xdfaWordStepResult = Reg(Types.UWord)
  val xdfaWordResponseData = Reg(Types.UWord)
  val xdfaWordIntermediate = Reg(UInt(16.W))
  val xdfaCommitMask = Reg(UInt(8.W))
  val xdfaCommitFinalState = Reg(UInt(3.W))
  val isNumericDfaStep = isNumericDfa && func3t === 5.U
  val isNumericDfaHistogramStep = isNumericDfaStep && func7t === 1.U
  val xdfaWordOffset = xdfaWordAddress(1, 0)
  val xdfaWordLow = Module(new NumericTokenDfa2ByteStep)
  val xdfaWordHigh = Module(new NumericTokenDfa2ByteStep)
  val xdfaWord4ShiftedData = xdfaWordResponseData >> (xdfaWordOffset << 3)
  val xdfaWordAvailable = 4.U(3.W) - xdfaWordOffset
  xdfaWordLow.io.state := xdfaWordStartState
  xdfaWordLow.io.mask := 0.U
  xdfaWordLow.io.consumed := 0.U
  xdfaWordLow.io.active := true.B
  xdfaWordLow.io.stopped := false.B
  xdfaWordLow.io.symbols := xdfaWord4ShiftedData(15, 0)
  xdfaWordLow.io.available := xdfaWordAvailable
  xdfaWordHigh.io.state := xdfaWordIntermediate(2, 0)
  xdfaWordHigh.io.consumed := xdfaWordIntermediate(5, 3)
  xdfaWordHigh.io.active := xdfaWordIntermediate(6)
  xdfaWordHigh.io.stopped := xdfaWordIntermediate(7)
  xdfaWordHigh.io.mask := xdfaWordIntermediate(15, 8)
  xdfaWordHigh.io.symbols := xdfaWord4ShiftedData(31, 16)
  xdfaWordHigh.io.available := Mux(xdfaWordAvailable > 2.U, xdfaWordAvailable - 2.U, 0.U)
  val xdfaCounterRead = Mux(func7t === 1.U, xdfaFinalCounters(reg_v1(2, 0)), xdfaCounters(reg_v1(2, 0)))
  val xdfaWordResult = Mux(func3t === 2.U, xdfaCounterRead, Mux(isNumericDfaStep, xdfaWordStepResult, 0.U))

  when(xdfaWordState === NumericDfaState.idle && io.in.valid && isNumericDfaStep) {
    xdfaWordStartState := reg_v1(2, 0)
    xdfaWordAddress := reg_v2
    xdfaWordState := NumericDfaState.request
  }.elsewhen(xdfaWordState === NumericDfaState.request && io.memReq.fire) {
    xdfaWordState := NumericDfaState.response
  }.elsewhen(xdfaWordState === NumericDfaState.response && io.memResp.valid) {
    xdfaWordResponseData := io.memResp.bits
    xdfaWordState := NumericDfaState.processLow
  }.elsewhen(xdfaWordState === NumericDfaState.processLow) {
    xdfaWordIntermediate := xdfaWordLow.io.result
    xdfaWordState := NumericDfaState.processHigh
  }.elsewhen(xdfaWordState === NumericDfaState.processHigh) {
    val combinedMask = xdfaPendingMask | xdfaWordHigh.io.result(15, 8)
    xdfaWordStepResult := Cat(0.U(17.W), xdfaWordHigh.io.result(15, 8), xdfaWordHigh.io.result(7),
      xdfaWordHigh.io.result(5, 3), xdfaWordHigh.io.result(2, 0))
    when(isNumericDfaHistogramStep) {
      when(xdfaWordHigh.io.result(7)) {
        xdfaCommitMask := combinedMask
        xdfaCommitFinalState := xdfaWordHigh.io.result(2, 0)
        xdfaPendingMask := 0.U
        xdfaWordState := NumericDfaState.commit
      }.otherwise {
        xdfaPendingMask := combinedMask
        xdfaWordState := NumericDfaState.done
      }
    }.otherwise {
      xdfaWordState := NumericDfaState.done
    }
  }.elsewhen(xdfaWordState === NumericDfaState.commit) {
    for (state <- 0 until 8) {
      when(xdfaCommitMask(state)) {
        xdfaCounters(state) := xdfaCounters(state) + 1.U
      }
      when(xdfaCommitFinalState === state.U) {
        xdfaFinalCounters(state) := xdfaFinalCounters(state) + 1.U
      }
    }
    xdfaWordState := NumericDfaState.done
  }.elsewhen(xdfaWordState === NumericDfaState.done && io.out.fire) {
    xdfaWordState := NumericDfaState.idle
  }

  when(io.in.fire && isNumericDfa && func3t === 0.U) {
    xdfaCounters.foreach(_ := 0.U)
    xdfaFinalCounters.foreach(_ := 0.U)
    xdfaPendingMask := 0.U
  }
  when(io.in.fire && isNumericDfa && func3t === 3.U) {
    for (state <- 0 until 8) {
      when(reg_v1(state)) {
        xdfaCounters(state) := xdfaCounters(state) + 1.U
      }
    }
  }

  when(xlrevState === XlrevState.idle && io.in.valid && isXlrev) {
    // Capture the asynchronous cache value for every xlrev entry.  Whether it
    // is consumed is decided by the state transition, keeping xlrevSingle out
    // of this register's timing-critical write-enable cone.
    xlrevNext := io.dcache.lateReadData
    xlrevSingleStoreCommitted := false.B
    when(!isXlrevSingle) {
      dcachePoisonedByXlrev := true.B
    }
    xlrevCurrent  := xlrevActiveCurrent
    xlrevPrevious := Mux(isXlrevChain || isXlrevLoop, xlrevChainPrevious, Mux(isXlrevSingle, reg_v2, 0.U))
    when(isXlrevSingle) {
      xlrevChainPrevious := xlrevActiveCurrent
    }
    when(xlrevActiveCurrent === 0.U) {
      xlrevResult := Mux(isXlrevChain || isXlrevLoop, xlrevChainPrevious, 0.U)
      xlrevLoopTaken := false.B
      xlrevState  := XlrevState.done
    }.elsewhen(isXlrevSingle && io.dcache.hit) {
      xlrevState := XlrevState.storeRequest
    }.otherwise {
      xlrevState := XlrevState.loadRequest
    }
  }.elsewhen(xlrevState === XlrevState.loadRequest && io.memReq.fire) {
    xlrevState := XlrevState.loadResponse
  }.elsewhen(xlrevState === XlrevState.loadResponse && io.memResp.valid) {
    xlrevNext  := io.memResp.bits
    xlrevState := XlrevState.storeRequest
  }.elsewhen(xlrevState === XlrevState.storeRequest && io.memReq.fire) {
    when(isXlrevSingle) {
      xlrevSingleStoreCommitted := true.B
      val loopTaken = isXlrevLoop && xlrevNext =/= 0.U
      xlrevResult := Mux(isXlrevLoop && !loopTaken, xlrevCurrent, xlrevNext)
      xlrevLoopTaken := loopTaken
      xlrevState   := XlrevState.done
    }.otherwise {
      xlrevState := XlrevState.storeResponse
    }
  }.elsewhen(xlrevState === XlrevState.storeResponse && io.memResp.valid) {
    when(isXlrevSingle) {
      xlrevResult := xlrevNext
      xlrevState  := XlrevState.done
    }.elsewhen(xlrevNext === 0.U) {
      xlrevResult := xlrevCurrent
      xlrevState  := XlrevState.done
    }.otherwise {
      xlrevPrevious := xlrevCurrent
      xlrevCurrent  := xlrevNext
      xlrevState    := XlrevState.loadRequest
    }
  }.elsewhen(xlrevState === XlrevState.done && io.out.fire) {
    xlrevState := XlrevState.idle
  }

  when(xmsumState === XmsumState.idle && io.in.valid && isXmsum) {
    val n = reg_v2(31, 16)
    xmsumAddress := reg_v1
    xmsumSize := n
    xmsumRow := 0.U
    xmsumColumn := 0.U
    xmsumClip  := Cat(Fill(16, reg_v2(15)), reg_v2(15, 0)).asSInt
    xmsumTmp   := 0.U
    xmsumPreviousClipped := false.B
    xmsumPrev  := 0.U
    xmsumRet   := 0.U
    xmsumRetPending := false.B
    when(n === 0.U) {
      xmsumResult := 0.U
      xmsumState  := XmsumState.done
    }.otherwise {
      xmsumState := XmsumState.request
    }
  }.elsewhen(xmsumState === XmsumState.request && io.memReq.fire) {
    xmsumState := XmsumState.response
  }.elsewhen(xmsumState === XmsumState.response && io.memResp.valid) {
    val current = io.memResp.bits
    val sum     = Mux(xmsumPreviousClipped, 0.U, xmsumTmp) + current
    val clipped = sum.asSInt > xmsumClip
    xmsumTmp  := sum
    xmsumPreviousClipped := clipped
    xmsumPrev := current
    xmsumRetClipped := clipped
    xmsumRetIncreased := !clipped && current.asSInt > xmsumPrev.asSInt
    val endOfRow = xmsumColumn + 1.U === xmsumSize
    val endOfMatrix = endOfRow && xmsumRow + 1.U === xmsumSize
    when(endOfMatrix) {
      xmsumState := XmsumState.finalizeResult
    }.otherwise {
      xmsumAddress := xmsumAddress + 4.U
      when(endOfRow) {
        xmsumRow := xmsumRow + 1.U
        xmsumColumn := 0.U
      }.otherwise {
        xmsumColumn := xmsumColumn + 1.U
      }
      xmsumRetPending := true.B
      xmsumState := XmsumState.request
    }
  }.elsewhen(xmsumState === XmsumState.finalizeResult) {
    xmsumResult := Cat(Fill(16, xmsumNextRet(15)), xmsumNextRet)
    xmsumState   := XmsumState.done
  }.elsewhen(xmsumState === XmsumState.done && io.out.fire) {
    xmsumState := XmsumState.idle
  }

  when(xmsumState === XmsumState.request && xmsumRetPending) {
    xmsumRet        := xmsumNextRet
    xmsumRetPending := false.B
  }

  val lateOtherOperandCaptured = RegInit(false.B)
  val capturedLateOtherRegV1 = Reg(Types.UWord)
  val capturedLateOtherRegV2 = Reg(Types.UWord)
  when(!io.in.valid || io.in.fire) {
    lateOtherOperandCaptured := false.B
  }.elsewhen(hasLateLoadOperand && !lateOtherOperandCaptured) {
    lateOtherOperandCaptured := true.B
    capturedLateOtherRegV1 := branchRegV1
    capturedLateOtherRegV2 := branchRegV2
  }
  val stableLateOtherRegV1 = Mux(lateOtherOperandCaptured, capturedLateOtherRegV1, branchRegV1)
  val stableLateOtherRegV2 = Mux(lateOtherOperandCaptured, capturedLateOtherRegV2, branchRegV2)
  val equalityRegV1 = Mux(dinst.info.lateLoadRs1, lateRegV1, stableLateOtherRegV1)
  val equalityRegV2 = Mux(dinst.info.lateLoadRs2, lateRegV2, stableLateOtherRegV2)
  // val pcAddImm   = dinst.pc + dinst.info.imm
  val pcAddImm   = dinst.info.pcAddImm
  val reg1AddImm = "h80".U(8.W) ## 0.U(2.W) ## dinst.info.reg1AddImm

  // Branches/JAL use PC+imm, while a JALR BTB entry must learn the resolved
  // rs1+imm target.  The BTB stores only the same trimmed PC bits either way.
  io.branchTarget   := Mux(isXlrevLoop, dinst.pc, Mux(isTypJALR, reg1AddImm, pcAddImm))

  fast_in.src1   := fastRegV1
  fast_in.src2   := fastRegV2
  fast_in.isImm  := isFmtI
  fast_in.isSub  := dinst.info.aluIsSub
  fast_in.func3t := func3t
  fast_in.func7t := func7t

  special_in.src1   := baseRegV1
  special_in.src2   := baseRegV2
  special_in.mulRawSrc1 := baseRegV1
  special_in.mulRawSrc2 := baseRegV2
  special_in.mulPrevData := 0.U
  special_in.mulPrevRs1 := false.B
  special_in.mulPrevRs2 := false.B
  special_in.mulNoLate := true.B
  special_in.is_imm := isFmtI
  special_in.isSub   := dinst.info.aluIsSub
  special_in.func3t := func3t
  special_in.func7t := func7t
  val isBExt = dinst.info.bExtValid
  special_in.bExtValid := isBExt
  special_in.crcValid := dinst.info.crcValid
  special_in.xbmulValid := dinst.info.xbmulValid

  val fastIntegerOut = fastInteger.io.out.bits
  val specialExecutionOut = alu.io.out.bits
  val resultIsFast = dinst.info.resultKind === ResultKind.fastInt
  val resultIsLong = dinst.info.resultKind === ResultKind.longArithmetic
  val resultIsAccelerator = dinst.info.resultKind === ResultKind.accelerator
  val simpleAccelerator = dinst.info.crcValid || dinst.info.xbmulValid

  fastInteger.io.in.valid := io.in.valid && isTypArithmetic && resultIsFast && !hasLateLoadOperand
  alu.io.in.valid :=
    io.in.valid && isTypArithmetic && (resultIsLong || (resultIsAccelerator && simpleAccelerator))

  when(io.in.valid && (dinst.info.fastAluRs1 || dinst.info.fastAluRs2)) {
    assert(resultIsFast && isTypArithmetic, "fast ALU token used by a non-fast consumer")
    assert(io.previousStageFwd.dataVaild && io.previousStageFwd.kind === ResultKind.fastInt,
      "deferred result entered the fast integer cluster")
  }
  when(io.in.valid && (dinst.info.fastBranchRs1 || dinst.info.fastBranchRs2)) {
    assert(isTypBranch, "fast branch token used by a non-branch consumer")
    assert(io.previousStageFwd.dataVaild && io.previousStageFwd.kind === ResultKind.fastInt,
      "deferred result entered the branch fast path")
  }
  when(io.in.valid && dinst.info.fastStoreRs2) {
    assert(isTypStore, "fast store-data token used by a non-store consumer")
    assert(io.previousStageFwd.dataVaild && io.previousStageFwd.kind === ResultKind.fastInt,
      "deferred result entered the store-data fast path")
  }

  // --- CSR ---
  val is_mret  = dinst.info.isMRet
  val is_ecall = dinst.info.isECall

  val csr_raddr = dinst.code(31, 20)
  val csr_rdata = dinst.info.csrReadData

  val writeBackInfo = io.out.bits.exuWriteBack

  val csrWrEnable = writeBackInfo.csr.en
  val csrWrAddr   = writeBackInfo.csr.addr
  val csrWrData   = writeBackInfo.csr.data

  object CSROp {
    val RW = 1.U
    val RS = 2.U
    val RC = 3.U
  }

  val csrUIMM = dinst.code(19, 15).pad(32)

  // val isCSRRW = (func3t === CSROp.RW) && isTypSys
  // val isCSRRS = (func3t === CSROp.RS) && isTypSys

  csrWrEnable := isTypSys && func3t(1, 0) =/= 0.U

  when(isTypSys) {

    val isRW = func3t(1, 0) === CSROp.RW
    val isRS = func3t(1, 0) === CSROp.RS
    val isRC = func3t(1, 0) === CSROp.RC

    val csrOpMask = Mux(func3t(2), csrUIMM, reg_v1)

    when(is_ecall) {
      csrWrAddr := CSRAddr.mepc
      // ecall: set mepc to pc
      // !!!note:
      // although wen = false
      // is_ecall flag makes csr to write wdata to mepc
      csrWrData := dinst.pc
    }.otherwise {
      csrWrAddr := csr_raddr
      csrWrData := Mux(
        isRC,
        csr_rdata & (~csrOpMask),
        Mux(isRS, csr_rdata | csrOpMask, csrOpMask)
      )
    }
  }.otherwise {
    csrWrAddr := DontCare
    csrWrData := DontCare
  }

  writeBackInfo.csr_ecallflag := is_ecall

  // --- Inst type decode ---
  val needMemReq = isTypLoad || isTypStore
  val memReqFire = io.memReq.valid && io.memReq.ready

  val isFmtB = InstFmt.hasSame(dinst.info.fmt, InstFmt.branch)

  val equalityDiff = equalityRegV1 ^ equalityRegV2
  dontTouch(equalityDiff)
  val equalityChunkNonZero = VecInit((0 until 4).map(i => equalityDiff(8 * i + 7, 8 * i).orR))
  dontTouch(equalityChunkNonZero)
  val extendedLoadEqual = !equalityChunkNonZero.asUInt.orR
  val isEqual     = extendedLoadEqual
  val isLessThan  = branchRegV1.asSInt < branchRegV2.asSInt
  val isLessThanU = branchRegV1 < branchRegV2

  // val isEqual = dinst.info.isEqual
  // val isLessThan = dinst.info.isLessThan
  // val isLessThanU = dinst.info.isLessThanU

  val takeBranch = Mux1H(
    Seq(
      dinst.info.is_beq  -> isEqual,
      dinst.info.is_bne  -> !isEqual,
      dinst.info.is_blt  -> isLessThan,
      dinst.info.is_bge  -> !isLessThan,
      dinst.info.is_bltu -> isLessThanU,
      dinst.info.is_bgeu -> !isLessThanU
    )
  )

  // --- LSU input ---
  val lsuInfo = io.out.bits
  lsuInfo.destAddr  := reg1AddImm
  lsuInfo.isLoad    := isTypLoad
  lsuInfo.isStore   := isTypStore
  lsuInfo.func3t    := dinst.code(14, 12)
  val supportedLoadWidth = func3t === "b000".U || func3t === "b001".U || func3t === "b010".U ||
    func3t === "b100".U || func3t === "b101".U
  val loadAddressAligned = Mux(func3t(1), reg1AddImm(1, 0) === 0.U, Mux(func3t(0), !reg1AddImm(0), true.B))
  lsuInfo.cacheableLoad :=
    isTypLoad && supportedLoadWidth && loadAddressAligned && reg1AddImm(21, 20) === "b01".U
  lsuInfo.dcacheHit := lsuInfo.cacheableLoad && io.dcache.hit && !dcachePoisonedByXlrev
  // Capture the asynchronous shadow result without sign extension. Extending
  // byte/half loads before this register lets synthesis map the replicated
  // sign bit onto slow synchronous-set pins; registered offset/width metadata
  // performs the extension in C1 instead.
  lsuInfo.lateLoadData := io.dcache.lateReadData
  lsuInfo.dcacheStoreEpoch := io.dcache.storeEpoch

  val snpc = dinst.info.staticNextPCOrCSRTarget
  io.staticTarget := Mux(isXlrevLoop, dinst.pc, snpc)

  val useSingleCycleForward = isTypArithmetic && resultIsFast && !hasLateLoadOperand
  val isLateLoadBit = isLateLoadAndi1 || isLateLoadSrli1
  val acceleratorData = Mux(
    isNumericDfa,
    xdfaWordResult,
    Mux(isXlrev, xlrevResult, Mux(isXmsum, xmsumResult, specialExecutionOut))
  )

  writeBackInfo.resultKind := dinst.info.resultKind
  writeBackInfo.fastResult.valid := dinst.info.rdWrEn && resultIsFast
  writeBackInfo.fastResult.rd := dinst.info.rd
  writeBackInfo.fastResult.data := Mux(isLateLoadBit, lateBitResult, fastIntegerOut)
  writeBackInfo.directResult.valid := dinst.info.rdWrEn && dinst.info.resultKind === ResultKind.direct
  writeBackInfo.directResult.rd := dinst.info.rd
  writeBackInfo.directResult.data := dinst.info.preMuxWrBackData
  writeBackInfo.longResult.valid := dinst.info.rdWrEn && resultIsLong
  writeBackInfo.longResult.rd := dinst.info.rd
  writeBackInfo.longResult.data := specialExecutionOut
  writeBackInfo.acceleratorResult.valid := dinst.info.rdWrEn && resultIsAccelerator
  writeBackInfo.acceleratorResult.rd := dinst.info.rd
  writeBackInfo.acceleratorResult.data := acceleratorData
  writeBackInfo.loadResult.valid := dinst.info.rdWrEn && isTypLoad
  writeBackInfo.loadResult.rd := dinst.info.rd

  when(io.in.valid) {
    assert(PopCount(ResultLaneSelect.validVec(writeBackInfo)) <= 1.U, "EXU result lanes must be one-hot")
    assert(!writeBackInfo.fastResult.valid || writeBackInfo.resultKind === ResultKind.fastInt)
    assert(!writeBackInfo.directResult.valid || writeBackInfo.resultKind === ResultKind.direct)
    assert(!writeBackInfo.longResult.valid || writeBackInfo.resultKind === ResultKind.longArithmetic)
    assert(!writeBackInfo.acceleratorResult.valid || writeBackInfo.resultKind === ResultKind.accelerator)
    assert(!writeBackInfo.loadResult.valid || writeBackInfo.resultKind === ResultKind.load)
    when(dinst.info.rdWrEn) {
      assert(PopCount(ResultLaneSelect.validVec(writeBackInfo)) === 1.U, "GPR producer must select exactly one lane")
    }
  }

  // Fill in LSU stage
  writeBackInfo.isLoad        := false.B
  writeBackInfo.isMemOp       := false.B
  writeBackInfo.lsuResult     := 0.U
  writeBackInfo.lsuFunc3t     := 0.U
  writeBackInfo.lsuAddrOffset := 0.U
  writeBackInfo.memAddr       := 0.U
  writeBackInfo.cacheableLoad := false.B
  writeBackInfo.dcacheHit     := false.B
  writeBackInfo.dcacheStoreEpoch := false.B

  val isMemOP        = isTypLoad || isTypStore
  val xlrevDone = isXlrev && xlrevState === XlrevState.done
  val xmsumDone = isXmsum && xmsumState === XmsumState.done
  val xdfaWordDone = isNumericDfaStep && xdfaWordState === NumericDfaState.done
  val exuResultValid =
    Mux(isNumericDfaStep, xdfaWordDone,
      Mux(isXlrev, xlrevDone, Mux(isXmsum, xmsumDone,
        (!isTypArithmetic || isNumericDfa ||
          Mux(resultIsFast, Mux(hasLateLoadOperand, lateDataReady, fastInteger.io.out.valid), alu.io.out.valid)) &&
          (!hasLateLoadOperand || lateDataReady))))
  // Keep the same-cycle forwarding loop independent of the multi-cycle M/D/B
  // result mux.  A multi-cycle producer still advertises its destination while it is
  // in EXU, but its data remains unavailable to IDU; a dependent consumer
  // waits one cycle and receives the registered result from LSU instead.
  val useLateBitForward = (isLateLoadAndi1 || isLateLoadSrli1) && exuResultValid && lateDataReadyFromLSU
  val exuForwardData = Mux(useLateBitForward, lateBitForwardResult, fastIntegerOut)
  // Only producers carried by the dedicated fast lane may arm the adjacent
  // EXU bypass token. Other single-cycle results wait one cycle and use the
  // ordinary LSU-to-IDU bypass, keeping them out of the ALU recurrence.
  val exuForwardDataValid = useSingleCycleForward || useLateBitForward
  io.fwd := WrBackForwardInfo(io.in.valid, dinst, exuForwardDataValid, exuForwardData, csrWrEnable)
  // The producer token is decode-only.  In particular, do not feed the
  // current load address/cacheability back into IDU ready; cache hit only
  // decides whether the already-issued consumer completes in the next cycle.
  val lateLoadWidthSupported =
    func3t === "b000".U || func3t === "b001".U || func3t === "b010".U || func3t === "b100".U || func3t === "b101".U
  io.lateLoadProducer.valid := io.in.valid && isTypLoad && lateLoadWidthSupported

  val memWMask = GenMemWMask(reg1AddImm(1, 0), func3t)

  val memWData = GenMemWData(reg1AddImm(1, 0), storeRegV2)

  val xlrevStoreRequest = xlrevState === XlrevState.storeRequest
  // xlrev's operand is held in the IDU/EXU payload for the instruction's
  // entire residence in EXU.  Its decoder disables the previous-EXU direct
  // bypass, so this registered value cannot create a forwarding-to-tag path.
  // xlrevLoop always consumes the preceding xlrev result: first the init
  // result, then the result of its previous self-iteration.  The raw decoded
  // operand can therefore lag behind even when the dependency has already
  // reached LSU/WBU.  Query from the local result register for every loop
  // iteration without reconnecting the global forwarding path to the tag RAM.
  val xlrevQueryAddr = Mux(isXlrevLoop, xlrevResult, reg_v1)
  val dcacheQueryAddr = Mux(isXlrevSingle, xlrevQueryAddr, reg1AddImm)
  io.dcache.queryIndex := dcacheQueryAddr(11, 2)
  io.dcache.queryTag   := dcacheQueryAddr(17, 11)
  val cacheableStore = isTypStore && reg1AddImm(21, 20) === "b01".U
  val cacheableStoreFire = memReqFire && cacheableStore
  val xlrevSingleCacheStore = isXlrevSingle && xlrevState === XlrevState.done && xlrevSingleStoreCommitted
  // Keep the asynchronous tag lookup out of this cross-module control and
  // every data-memory write enable.
  io.dcache.storeUpdate := cacheableStoreFire
  io.dcache.storeFull   := cacheableStoreFire && memWMask.andR
  io.dcache.storeData   := memWData
  io.dcache.storeMask   := memWMask
  io.dcache.fullUpdate     := xlrevSingleCacheStore
  io.dcache.fullUpdateValid := true.B
  io.dcache.fullUpdateAddr := xlrevCurrent
  io.dcache.fullUpdateData := xlrevPrevious

  val xlrevLoadRequest = xlrevState === XlrevState.loadRequest
  val xlrevRequest = xlrevLoadRequest || xlrevStoreRequest
  val xmsumRequest = xmsumState === XmsumState.request
  val xdfaWordRequest = xdfaWordState === NumericDfaState.request
  val normalMemReq = Wire(new MemReq)
  // The accelerator kind is held in ID/EX for the instruction's entire EXU
  // residence. Use that registered identity to select the request payload;
  // state-machine request bits only qualify valid. This keeps state decode out
  // of the shared address/data network and does not change the handshake.
  normalMemReq.addr  := Mux(isNumericDfaStep, xdfaWordAddress & ~3.U(32.W),
    Mux(isXlrev, xlrevCurrent, Mux(isXmsum, xmsumAddress, reg1AddImm)))
  normalMemReq.size  := Mux(isNumericDfaStep || isXlrev || isXmsum, 2.U, func3t(1, 0))
  normalMemReq.wen   := Mux(isNumericDfaStep, false.B, Mux(isXlrev, xlrevStoreRequest, !isXmsum && isTypStore))
  normalMemReq.wdata := Mux(xlrevStoreRequest, xlrevPrevious,
    Mux(isXmsum || (isXlrev && !xlrevStoreRequest), 0.U, memWData))
  normalMemReq.wmask := Mux(xlrevStoreRequest, "b1111".U,
    Mux(isXmsum || (isXlrev && !xlrevStoreRequest), 0.U, memWMask))
  io.memReq.valid := xdfaWordRequest || xlrevRequest || xmsumRequest || (needMemReq && io.in.valid && io.out.ready)
  io.memReq.bits := normalMemReq

  val normalReady = memReqFire || (
    io.out.ready && !needMemReq && exuResultValid
  )
  val normalValid = memReqFire || (
    io.in.valid && !needMemReq && exuResultValid
  )
  io.in.ready := Mux(isNumericDfaStep, xdfaWordDone && io.out.ready,
    Mux(isXlrev, xlrevDone && io.out.ready, Mux(isXmsum, xmsumDone && io.out.ready, normalReady)))
  io.out.valid := Mux(isNumericDfaStep, xdfaWordDone,
    Mux(isXlrev, xlrevDone, Mux(isXmsum, xmsumDone, normalValid)))

  writeBackInfo.iid := dinst.iid

  // --- Next PC ---
  val isJmpCsr = is_ecall || is_mret
  val xlrevLoopBranch = isXlrevLoop && xlrevDone
  val willJmp  = (isTypBranch && takeBranch) || isTypJALR || isTypJAL || isJmpCsr ||
    (xlrevLoopBranch && xlrevLoopTaken)

  val normalNxtPC = Wire(Types.UWord)
  val nxtPC       = Wire(Types.UWord)

  normalNxtPC := TrimmedPC.expand(
    Mux(
      isTypJALR,
      TrimmedPC.trim(reg1AddImm),
      Mux(
        isTypJAL || (isFmtB && takeBranch),
        TrimmedPC.trim(pcAddImm),
        TrimmedPC.trim(snpc)
      )
    )
  )
  nxtPC       := Mux(xlrevLoopBranch && xlrevLoopTaken, dinst.pc, normalNxtPC)
  io.nxtPC    := nxtPC
  io.pc       := dinst.pc

  io.jmpHappen   := willJmp
  // Reuse the existing unconditional-entry bit for direct JAL and the exact
  // return encoding that IDU can validate without an address-add dependency.
  io.isJAL       := isTypJAL || dinst.code === "h00008067".U
  io.isBranch    := isTypBranch || xlrevLoopBranch
  io.isReturn    := isTypJALR && dinst.code === "h00008067".U
  io.isCall      := (isTypJAL || isTypJALR) && dinst.info.rd =/= 0.U
  io.branchTaken := Mux(xlrevLoopBranch, xlrevLoopTaken, takeBranch)
  io.btbUpdateEn := isTypBranch || isTypJAL || isTypJALR || xlrevLoopBranch
  io.predWrong := exuResultValid && Mux(
    xlrevLoopBranch,
    xlrevLoopTaken ^ dinst.predTake,
    (isFmtB && (takeBranch ^ dinst.predTake)) || io.in.bits.info.notBranchPredWrong
  )

  StageLogger(
    clock,
    StageLogConst.Event.stage,
    StageLogConst.Stage.exu,
    io.in.fire,
    dinst.iid
  )

  val dbgIsBranch = WireDefault(isTypBranch)
  val dbgIsJALR   = WireDefault(isTypJALR)
  val dbgIsJAL    = WireDefault(isTypJAL)
  val dbgIsCSRJmp = WireDefault(isJmpCsr)
  dontTouch(dbgIsBranch)
  dontTouch(dbgIsJALR)
  dontTouch(dbgIsJAL)
  dontTouch(dbgIsCSRJmp)
}

class EXUForDifftest(
  implicit p: CPUParameters)
    extends Module {
  val io = IO(new Bundle {
    val in     = Flipped(Decoupled(new DecodedInst))
    val actual = new Bundle {
      val inReady  = Input(Bool())
      val pc       = Input(Types.UWord)
      val nxtPC    = Input(Types.UWord)
      val memAddr  = Input(Types.UWord)
      val outValid = Input(Bool())
    }
    val out    = Decoupled(new LSUInputForDifftest)
  })
  io.in.ready := io.actual.inReady
  io.out.valid := io.actual.outValid

  val outInfo = io.out.bits
  outInfo.isLoad   := InstType.hasSame(io.in.bits.info.typ, InstType.load)
  outInfo.isStore  := InstType.hasSame(io.in.bits.info.typ, InstType.store)
  outInfo.pc       := io.actual.pc
  outInfo.nxtPC    := io.actual.nxtPC
  outInfo.isEBreak := io.in.bits.code === "h00100073".U
  outInfo.destAddr := io.actual.memAddr
}
