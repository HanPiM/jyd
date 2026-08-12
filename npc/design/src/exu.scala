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
    val lateBranchPreview = Input(new LateBranchPreview)
    val lateLoadLSU = Input(new LateLoadSourceInfo)
    val lateLoadWBU = Input(new LateLoadSourceInfo)
    val lateLoadWBURawData = Input(Types.UWord)
    val lateLoadWBUFunc3   = Input(UInt(3.W))
    val lateLoadWBUOffset  = Input(UInt(2.W))
    val previousStageFwd = Input(new WrBackForwardInfo)

    val dcache = new Bundle {
      val hit        = Input(Bool())
      val lateReadData = Input(Types.UWord)
      val lateBranchEqualValid = Input(Bool())
      val lateBranchEqual = Input(Bool())
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

  object ListReverseState extends ChiselEnum {
    val idle, loadRequest, loadResponse, storeRequest, done = Value
  }
  val listReverseState = RegInit(ListReverseState.idle)
  val listReverseCurrent = Reg(Types.UWord)
  val listReversePrevious = Reg(Types.UWord)
  val listReverseChainPrevious = Reg(Types.UWord)
  val listReverseNext = Reg(Types.UWord)
  val listReverseResult = Reg(Types.UWord)
  val listReverseLoopAddress = RegInit(0.U(32.W))
  val listReverseStepStoreCommitted = RegInit(false.B)
  val isListReverse = dinst.info.listReverseValid
  val isListReverseStep = dinst.info.listReverseStep
  val isListReverseLoop = dinst.info.listReverseLoop
  val listReverseLoopTaken = RegInit(false.B)

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
  def decodeAdjacentFastGroups(encoded: UInt, base: UInt): UInt = encoded ^ base(7, 0)
  def expandAdjacentFastGroups(groups: UInt): UInt = Cat((7 to 0 by -1).map(i => Fill(4, groups(i))))
  def selectAdjacentFast(groups: UInt, base: UInt): UInt = {
    val mask = expandAdjacentFastGroups(groups)
    (io.previousStageFwd.data & mask) | (base & ~mask)
  }
  val fastAluRs1Groups = decodeAdjacentFastGroups(dinst.info.fastAluRs1, baseRegV1)
  val fastAluRs2Groups = decodeAdjacentFastGroups(dinst.info.fastAluRs2, baseRegV2)
  val fastBranchRs1Groups = decodeAdjacentFastGroups(dinst.info.fastBranchRs1, baseRegV1)
  val fastBranchRs2Groups = decodeAdjacentFastGroups(dinst.info.fastBranchRs2, baseRegV2)
  val fastStoreRs2Groups = decodeAdjacentFastGroups(dinst.info.fastStoreRs2, baseRegV2)
  val fastRegV1 = selectAdjacentFast(fastAluRs1Groups, baseRegV1)
  val fastRegV2 = selectAdjacentFast(fastAluRs2Groups, baseRegV2)
  val branchRegV1 = selectAdjacentFast(fastBranchRs1Groups, baseRegV1)
  val branchRegV2 = selectAdjacentFast(fastBranchRs2Groups, baseRegV2)
  val storeRegV2 = selectAdjacentFast(fastStoreRs2Groups, baseRegV2)
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
  val listReverseActiveCurrent = Mux(isListReverseLoop, listReverseLoopAddress, reg_v1)

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
  val xdfaWordAvailable = Reg(UInt(3.W))
  val xdfaWordIntermediate = Reg(UInt(16.W))
  val xdfaCommitMask = Reg(UInt(8.W))
  val xdfaCommitFinalState = Reg(UInt(3.W))
  val isNumericDfaStep = isNumericDfa && func3t === 5.U
  val isNumericDfaHistogramStep = isNumericDfaStep && func7t === 1.U
  val xdfaWordLow = Module(new NumericTokenDfa2ByteStep)
  val xdfaWordHigh = Module(new NumericTokenDfa2ByteStep)
  xdfaWordLow.io.state := xdfaWordStartState
  xdfaWordLow.io.mask := 0.U
  xdfaWordLow.io.consumed := 0.U
  xdfaWordLow.io.active := true.B
  xdfaWordLow.io.stopped := false.B
  xdfaWordLow.io.symbols := xdfaWordResponseData(15, 0)
  xdfaWordLow.io.available := xdfaWordAvailable
  xdfaWordHigh.io.state := xdfaWordIntermediate(2, 0)
  xdfaWordHigh.io.consumed := xdfaWordIntermediate(5, 3)
  xdfaWordHigh.io.active := xdfaWordIntermediate(6)
  xdfaWordHigh.io.stopped := xdfaWordIntermediate(7)
  xdfaWordHigh.io.mask := xdfaWordIntermediate(15, 8)
  xdfaWordHigh.io.symbols := xdfaWordResponseData(31, 16)
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
    // Terminate address-dependent alignment at the response register boundary.
    xdfaWordResponseData := io.memResp.bits >> (xdfaWordAddress(1, 0) << 3)
    xdfaWordAvailable := 4.U - xdfaWordAddress(1, 0)
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

  val numericDfaLocalFire = io.in.valid && io.out.ready && isNumericDfa && !isNumericDfaStep
  when(numericDfaLocalFire && func3t === 0.U) {
    xdfaCounters.foreach(_ := 0.U)
    xdfaFinalCounters.foreach(_ := 0.U)
    xdfaPendingMask := 0.U
  }
  when(numericDfaLocalFire && func3t === 3.U) {
    for (state <- 0 until 8) {
      when(reg_v1(state)) {
        xdfaCounters(state) := xdfaCounters(state) + 1.U
      }
    }
  }

  when(listReverseState === ListReverseState.idle && io.in.valid && isListReverse) {
    // Capture the asynchronous cache value for every list-reversal entry. Whether it
    // is consumed is decided by the state transition, keeping init decode out
    // of this register's timing-critical write-enable cone.
    listReverseNext := io.dcache.lateReadData
    listReverseStepStoreCommitted := false.B
    listReverseCurrent := listReverseActiveCurrent
    listReversePrevious := Mux(isListReverseLoop, listReverseChainPrevious, reg_v2)
    when(isListReverseStep) {
      listReverseChainPrevious := listReverseActiveCurrent
    }
    when(listReverseActiveCurrent === 0.U) {
      listReverseResult := Mux(isListReverseLoop, listReverseChainPrevious, 0.U)
      listReverseLoopTaken := false.B
      listReverseState := ListReverseState.done
    }.elsewhen(isListReverseStep && io.dcache.hit) {
      listReverseState := ListReverseState.storeRequest
    }.otherwise {
      listReverseState := ListReverseState.loadRequest
    }
  }.elsewhen(listReverseState === ListReverseState.loadRequest && io.memReq.fire) {
    listReverseState := ListReverseState.loadResponse
  }.elsewhen(listReverseState === ListReverseState.loadResponse && io.memResp.valid) {
    listReverseNext := io.memResp.bits
    listReverseState := ListReverseState.storeRequest
  }.elsewhen(listReverseState === ListReverseState.storeRequest && io.memReq.fire) {
    listReverseStepStoreCommitted := true.B
    val loopTaken = isListReverseLoop && listReverseNext =/= 0.U
    listReverseResult := Mux(isListReverseLoop && !loopTaken, listReverseCurrent, listReverseNext)
    listReverseLoopTaken := loopTaken
    listReverseState := ListReverseState.done
  }.elsewhen(listReverseState === ListReverseState.done && io.out.fire) {
    listReverseState := ListReverseState.idle
  }

  // The loop instruction can enter EXU as the previous list operation leaves done.
  // Capture its result at that boundary so the next iteration's asynchronous
  // cache lookup cannot feed back directly from the accelerator result lane.
  when(listReverseState === ListReverseState.done) {
    listReverseLoopAddress := listReverseResult
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
  io.branchTarget   := Mux(isListReverseLoop, dinst.pc, Mux(isTypJALR, reg1AddImm, pcAddImm))

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

  when(io.in.valid && (fastAluRs1Groups.orR || fastAluRs2Groups.orR)) {
    assert(resultIsFast && isTypArithmetic, "fast ALU token used by a non-fast consumer")
    assert(io.previousStageFwd.dataVaild && io.previousStageFwd.kind === ResultKind.fastInt,
      "deferred result entered the fast integer cluster")
  }
  when(io.in.valid && (fastBranchRs1Groups.orR || fastBranchRs2Groups.orR)) {
    assert(isTypBranch, "fast branch token used by a non-branch consumer")
    assert(io.previousStageFwd.dataVaild && io.previousStageFwd.kind === ResultKind.fastInt,
      "deferred result entered the branch fast path")
  }
  when(io.in.valid && fastStoreRs2Groups.orR) {
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

  def rawLoadEqual(rawData: UInt, offset: UInt, loadFunc3t: UInt, other: UInt): Bool = {
    val selectedHalf = Mux(offset(1), rawData(31, 16), rawData(15, 0))
    val selectedByte = MuxLookup(offset, rawData(7, 0))(
      Seq(
        1.U -> rawData(15, 8),
        2.U -> rawData(23, 16),
        3.U -> rawData(31, 24)
      )
    )
    val unsignedLoad = loadFunc3t(2)
    val byteUpperMatches = Mux(unsignedLoad, !other(31, 8).orR, other(31, 8) === Fill(24, selectedByte(7)))
    val halfUpperMatches = Mux(unsignedLoad, !other(31, 16).orR, other(31, 16) === Fill(16, selectedHalf(15)))
    Mux(
      loadFunc3t(1),
      rawData === other,
      Mux(loadFunc3t(0), selectedHalf === other(15, 0) && halfUpperMatches,
        selectedByte === other(7, 0) && byteUpperMatches)
    )
  }

  val lsuLateEqual = Mux(
    dinst.info.lateLoadRs1 && dinst.info.lateLoadRs2,
    true.B,
    Mux(
      dinst.info.lateLoadRs1,
      rawLoadEqual(io.lateLoadLSU.rawData, io.lateLoadLSU.offset, io.lateLoadLSU.func3t, stableLateOtherRegV2),
      rawLoadEqual(io.lateLoadLSU.rawData, io.lateLoadLSU.offset, io.lateLoadLSU.func3t, stableLateOtherRegV1)
    )
  )
  val useRegisteredRawLoadEqual = hasLateLoadOperand && io.lateLoadLSU.dataValid
  val previewLoadWidthSupported = func3t === "b000".U || func3t === "b001".U || func3t === "b010".U ||
    func3t === "b100".U || func3t === "b101".U
  val previewLoadAddressAligned =
    Mux(func3t(1), reg1AddImm(1, 0) === 0.U, Mux(func3t(0), !reg1AddImm(0), true.B))
  val lateBranchPreviewValid =
    io.in.valid && isTypLoad && previewLoadWidthSupported && previewLoadAddressAligned &&
      reg1AddImm(21, 20) === "b01".U && io.dcache.hit && io.lateBranchPreview.valid
  val isEqual     = Mux(io.dcache.lateBranchEqualValid, io.dcache.lateBranchEqual,
    Mux(useRegisteredRawLoadEqual, lsuLateEqual, extendedLoadEqual))
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
  lsuInfo.addressResultData := ResultLaneSelect.nonLoadData(writeBackInfo)
  val supportedLoadWidth = func3t === "b000".U || func3t === "b001".U || func3t === "b010".U ||
    func3t === "b100".U || func3t === "b101".U
  val loadAddressAligned = Mux(func3t(1), reg1AddImm(1, 0) === 0.U, Mux(func3t(0), !reg1AddImm(0), true.B))
  lsuInfo.cacheableLoad :=
    isTypLoad && supportedLoadWidth && loadAddressAligned && reg1AddImm(21, 20) === "b01".U
  lsuInfo.dcacheHit := lsuInfo.cacheableLoad && io.dcache.hit
  // Capture the asynchronous shadow result without sign extension. Extending
  // byte/half loads before this register lets synthesis map the replicated
  // sign bit onto slow synchronous-set pins; registered offset/width metadata
  // performs the extension in C1 instead.
  // Accelerator cache queries must not populate the ordinary load-result lane.
  lsuInfo.lateLoadData := Mux(lsuInfo.cacheableLoad, io.dcache.lateReadData, 0.U)
  lsuInfo.dcacheStoreEpoch := io.dcache.storeEpoch

  val snpc = dinst.info.staticNextPCOrCSRTarget
  io.staticTarget := Mux(isListReverseLoop, dinst.pc, snpc)

  val useSingleCycleForward = isTypArithmetic && resultIsFast && !hasLateLoadOperand
  val isLateLoadBit = isLateLoadAndi1 || isLateLoadSrli1
  val acceleratorData = Mux(
    isNumericDfa,
    xdfaWordResult,
    Mux(isListReverse, listReverseResult, Mux(isXmsum, xmsumResult, specialExecutionOut))
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
  val listReverseDone = isListReverse && listReverseState === ListReverseState.done
  val xmsumDone = isXmsum && xmsumState === XmsumState.done
  val xdfaWordDone = isNumericDfaStep && xdfaWordState === NumericDfaState.done
  val exuResultValid =
    Mux(isNumericDfaStep, xdfaWordDone,
      Mux(isListReverse, listReverseDone, Mux(isXmsum, xmsumDone,
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

  val listReverseStoreRequest = listReverseState === ListReverseState.storeRequest
  // The list-reversal operand is held in the IDU/EXU payload for the instruction's
  // entire residence in EXU.  Its decoder disables the previous-EXU direct
  // bypass, so this registered value cannot create a forwarding-to-tag path.
  // The loop always consumes the preceding list-reversal result: first the init
  // result, then the result of its previous self-iteration. The done-boundary
  // register keeps that private recurrence out of the asynchronous tag RAM.
  val listReverseQueryAddress = Mux(isListReverseLoop, listReverseLoopAddress, reg_v1)
  val dcacheQueryAddr = Mux(isListReverseStep, listReverseQueryAddress, reg1AddImm)
  io.dcache.queryIndex := dcacheQueryAddr(11, 2)
  io.dcache.queryTag   := dcacheQueryAddr(17, 11)
  val cacheableStore = isTypStore && reg1AddImm(21, 20) === "b01".U
  val cacheableStoreFire = memReqFire && cacheableStore
  val listReverseStepCacheStore = isListReverseStep && listReverseState === ListReverseState.done &&
    listReverseStepStoreCommitted
  // Keep the asynchronous tag lookup out of this cross-module control and
  // every data-memory write enable.
  io.dcache.storeUpdate := cacheableStoreFire
  io.dcache.storeFull   := cacheableStoreFire && memWMask.andR
  io.dcache.storeData   := memWData
  io.dcache.storeMask   := memWMask
  io.dcache.fullUpdate     := listReverseStepCacheStore
  io.dcache.fullUpdateValid := true.B
  io.dcache.fullUpdateAddr := listReverseCurrent
  io.dcache.fullUpdateData := listReversePrevious

  val listReverseLoadRequest = listReverseState === ListReverseState.loadRequest
  val listReverseRequest = listReverseLoadRequest || listReverseStoreRequest
  val xmsumRequest = xmsumState === XmsumState.request
  val xdfaWordRequest = xdfaWordState === NumericDfaState.request
  val normalMemReq = Wire(new MemReq)
  // The accelerator kind is held in ID/EX for the instruction's entire EXU
  // residence. Use that registered identity to select the request payload;
  // state-machine request bits only qualify valid. This keeps state decode out
  // of the shared address/data network and does not change the handshake.
  normalMemReq.addr  := Mux(isNumericDfaStep, xdfaWordAddress & ~3.U(32.W),
    Mux(isListReverse, listReverseCurrent, Mux(isXmsum, xmsumAddress, reg1AddImm)))
  normalMemReq.size  := Mux(isNumericDfaStep || isListReverse || isXmsum, 2.U, func3t(1, 0))
  normalMemReq.wen   := Mux(isNumericDfaStep, false.B,
    Mux(isListReverse, listReverseStoreRequest, !isXmsum && isTypStore))
  normalMemReq.wdata := Mux(listReverseStoreRequest, listReversePrevious,
    Mux(isXmsum || (isListReverse && !listReverseStoreRequest), 0.U, memWData))
  normalMemReq.wmask := Mux(listReverseStoreRequest, "b1111".U,
    Mux(isXmsum || (isListReverse && !listReverseStoreRequest), 0.U, memWMask))
  io.memReq.valid := xdfaWordRequest || listReverseRequest || xmsumRequest ||
    (needMemReq && io.in.valid && io.out.ready)
  io.memReq.bits := normalMemReq

  val normalReady = memReqFire || (
    io.out.ready && !needMemReq && exuResultValid
  )
  val normalValid = memReqFire || (
    io.in.valid && !needMemReq && exuResultValid
  )
  io.in.ready := Mux(isNumericDfaStep, xdfaWordDone && io.out.ready,
    Mux(isListReverse, listReverseDone && io.out.ready, Mux(isXmsum, xmsumDone && io.out.ready, normalReady)))
  io.out.valid := Mux(isNumericDfaStep, xdfaWordDone,
    Mux(isListReverse, listReverseDone, Mux(isXmsum, xmsumDone, normalValid)))

  writeBackInfo.iid := dinst.iid

  // --- Next PC ---
  val isJmpCsr = is_ecall || is_mret
  val listReverseLoopBranch = isListReverseLoop && listReverseDone
  val willJmp  = (isTypBranch && takeBranch) || isTypJALR || isTypJAL || isJmpCsr ||
    (listReverseLoopBranch && listReverseLoopTaken)

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
  nxtPC       := Mux(listReverseLoopBranch && listReverseLoopTaken, dinst.pc, normalNxtPC)
  io.nxtPC    := nxtPC
  io.pc       := dinst.pc

  io.jmpHappen   := willJmp
  // Reuse the existing unconditional-entry bit for direct JAL and the exact
  // return encoding that IDU can validate without an address-add dependency.
  io.isJAL       := isTypJAL || dinst.code === "h00008067".U
  io.isBranch    := isTypBranch || listReverseLoopBranch
  io.isReturn    := isTypJALR && dinst.code === "h00008067".U
  io.isCall      := (isTypJAL || isTypJALR) && dinst.info.rd =/= 0.U
  io.branchTaken := Mux(listReverseLoopBranch, listReverseLoopTaken, takeBranch)
  io.btbUpdateEn := isTypBranch || isTypJAL || isTypJALR || listReverseLoopBranch
  io.predWrong := exuResultValid && Mux(
    listReverseLoopBranch,
    listReverseLoopTaken ^ dinst.predTake,
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
