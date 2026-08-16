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
    val isJAL       = Output(Bool())
    val isBranch    = Output(Bool())
    val isReturn    = Output(Bool())
    val isCall      = Output(Bool())
    val branchTaken = Output(Bool())
    val btbUpdateEn = Output(Bool())

    val predWrong = Output(Bool())
    val immediatePredWrong = Output(Bool())

    val branchTarget = Output(Types.UWord)

    val pc    = Output(Types.UWord)
    val nxtPC = Output(Types.UWord)

    val fwd = Output(new WrBackForwardInfo)
    val previousStageFwd = Input(new WrBackForwardInfo)
    val stagedDcacheQueryIndex = Input(UInt(10.W))

    val dcache = new Bundle {
      val hit        = Input(Bool())
      val readData   = Input(Types.UWord)
      val listReverseHitCapture = Output(Bool())
      val listReverseCapturedHit = Input(Bool())
      val storeEpoch = Input(Bool())
      val queryIndex = Output(UInt(10.W))
      val queryTag   = Output(UInt(7.W))
      val listFindStart = Output(Bool())
      val listFindConsume = Output(Bool())
      val listFindAddress = Output(Types.UWord)
      val listFindTarget = Output(UInt(16.W))
      val listFindDataMode = Output(Bool())
      val listFindRequestFire = Output(Bool())
      val listFindMemResponse = Output(Valid(Types.UWord))
      val listFindRequest = Input(Bool())
      val listFindRequestAddress = Input(Types.UWord)
      val listFindDone = Input(Bool())
      val listFindResult = Input(Types.UWord)
      val dotNStart = Output(Bool())
      val dotNConsume = Output(Bool())
      val dotNAddressA = Output(Types.UWord)
      val dotNAddressB = Output(Types.UWord)
      val dotNLength = Output(UInt(16.W))
      val dotNBitMode = Output(Bool())
      val dotNRequestFire = Output(Bool())
      val dotNMemResponse = Output(Valid(Types.UWord))
      val dotNRequest = Input(Bool())
      val dotNRequestAddress = Input(Types.UWord)
      val dotNDone = Input(Bool())
      val dotNResult = Input(Types.UWord)
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
  val csrs = Module(new ControlStatusRegisterFile)

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
    val idle, lookup, lookupResolve, loadRequest, loadResponse, storeRequest, cacheUpdate, done = Value
  }
  val listReverseState = RegInit(ListReverseState.idle)
  val listReverseCurrent = Reg(Types.UWord)
  val listReversePrevious = Reg(Types.UWord)
  val listReverseChainPrevious = Reg(Types.UWord)
  val listReverseNext = Reg(Types.UWord)
  val listReverseResult = Reg(Types.UWord)
  val listReverseLoopAddress = RegInit(0.U(32.W))
  val isListReverse = dinst.info.listReverseValid
  val isListReverseStep = dinst.info.listReverseStep
  val isListReverseLoop = dinst.info.listReverseLoop
  val listReversePrefetchValid = RegInit(false.B)
  val listReversePrefetchHit = Reg(Bool())
  val listReversePrefetchData = Reg(Types.UWord)
  val listReverseQueryAddress = Reg(Types.UWord)

  val isListFind = dinst.info.listFindValid
  val isDotConfig = dinst.info.xdotConfigValid
  val isDotN = dinst.info.xdotNValid
  val isDcacheWalker = isListFind || isDotN
  val dotLength = RegInit(9.U(16.W))

  object XmsumState extends ChiselEnum {
    val idle, firstRequest, stream, done = Value
  }
  val xmsumState               = RegInit(XmsumState.idle)
  val xmsumAddress             = Reg(Types.UWord)
  val xmsumSize                = Reg(UInt(16.W))
  val xmsumIssueRow            = Reg(UInt(16.W))
  val xmsumIssueColumn         = Reg(UInt(16.W))
  val xmsumResponseRow         = Reg(UInt(16.W))
  val xmsumResponseColumn      = Reg(UInt(16.W))
  val xmsumAllIssued           = RegInit(false.B)
  val xmsumClip                = Reg(SInt(32.W))
  val xmsumTmp                 = Reg(UInt(32.W))
  val xmsumPrev                = Reg(UInt(32.W))
  val xmsumRet                 = Reg(UInt(16.W))
  val xmsumResponseData        = Reg(UInt(32.W))
  val xmsumResponsePending = RegInit(false.B)
  val xmsumResponseLast        = Reg(Bool())
  val xmsumResult              = Reg(Types.UWord)
  val isXmsum                  = dinst.info.xmsumValid

  // Store zero after a clipped sample so the following sum does not need the
  // previous-clipped bit in front of its carry chain.
  val xmsumResponseSum = xmsumTmp + xmsumResponseData
  val xmsumResponseClipped = xmsumResponseSum.asSInt > xmsumClip
  val xmsumResponseIncreased = !xmsumResponseClipped && xmsumResponseData.asSInt > xmsumPrev.asSInt
  val xmsumResponseNextRet = Mux(xmsumResponseClipped, xmsumRet + 10.U,
    Mux(xmsumResponseIncreased, xmsumRet + 1.U, xmsumRet))

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

  val reg_v1       = baseRegV1
  val reg_v2       = baseRegV2
  val listReverseActiveCurrent = Mux(isListReverseLoop, listReverseLoopAddress, reg_v1)

  // The configuration instruction retires before the following walker can
  // enter EXU.  Keep N local to EXU and snapshot it into DCache on dot start.
  when(io.in.valid && io.out.ready && isDotConfig) {
    dotLength := reg_v1(15, 0)
  }

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
  val xdfaWordAddressUpperPlusOne = Reg(UInt(29.W))
  val xdfaWordStepResult = Reg(Types.UWord)
  val xdfaWordResponseData = Reg(Types.UWord)
  val xdfaWordAvailable = Reg(UInt(3.W))
  val xdfaWordIntermediate = Reg(UInt(16.W))
  val xdfaCommitMask = Reg(UInt(8.W))
  val xdfaCommitFinalState = Reg(UInt(3.W))
  val xdfaInternalState = RegInit(0.U(3.W))
  val xdfaInternalStopped = RegInit(true.B)
  val isNumericDfaStep = isNumericDfa && func3t === 5.U
  val isNumericDfaHistogramStep = isNumericDfaStep && func7t === 1.U
  val isNumericDfaStepPtr = isNumericDfaStep && func7t === 2.U
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
    xdfaWordStartState := Mux(isNumericDfaStepPtr, Mux(xdfaInternalStopped, 0.U, xdfaInternalState), reg_v1(2, 0))
    xdfaWordAddress := reg_v2
    xdfaWordState := Mux(io.memReq.fire, NumericDfaState.response, NumericDfaState.request)
  }.elsewhen(xdfaWordState === NumericDfaState.request && io.memReq.fire) {
    xdfaWordState := NumericDfaState.response
  }.elsewhen(xdfaWordState === NumericDfaState.response && io.memResp.valid) {
    // Terminate address-dependent alignment at the response register boundary.
    xdfaWordResponseData := io.memResp.bits >> (xdfaWordAddress(1, 0) << 3)
    xdfaWordAvailable := 4.U - xdfaWordAddress(1, 0)
    xdfaWordAddressUpperPlusOne := xdfaWordAddress(31, 3) + 1.U
    xdfaWordState := NumericDfaState.processLow
  }.elsewhen(xdfaWordState === NumericDfaState.processLow) {
    xdfaWordIntermediate := xdfaWordLow.io.result
    xdfaWordState := NumericDfaState.processHigh
  }.elsewhen(xdfaWordState === NumericDfaState.processHigh) {
    val combinedMask = xdfaPendingMask | xdfaWordHigh.io.result(15, 8)
    xdfaPendingMask := Mux(xdfaWordHigh.io.result(7), 0.U, combinedMask)
    val nextAddressLow = xdfaWordAddress(2, 0) +& xdfaWordHigh.io.result(5, 3)
    val nextAddress = Cat(
      Mux(nextAddressLow(3), xdfaWordAddressUpperPlusOne, xdfaWordAddress(31, 3)),
      nextAddressLow(2, 0)
    )
    xdfaWordStepResult := Mux(isNumericDfaStepPtr,
      nextAddress,
      Cat(0.U(17.W), xdfaWordHigh.io.result(15, 8), xdfaWordHigh.io.result(7),
        xdfaWordHigh.io.result(5, 3), xdfaWordHigh.io.result(2, 0)))
    when(isNumericDfaStepPtr) {
      xdfaInternalState := xdfaWordHigh.io.result(2, 0)
      xdfaInternalStopped := xdfaWordHigh.io.result(7)
    }
    when(isNumericDfaHistogramStep || isNumericDfaStepPtr) {
      when(xdfaWordHigh.io.result(7)) {
        when(isNumericDfaStepPtr && xdfaWordHigh.io.result(5, 3) === 0.U && xdfaPendingMask === 0.U) {
          // Terminal empty-token NUL step: the software loop never executes it,
          // so it must not record a final state.
          xdfaWordState := NumericDfaState.done
        }.otherwise {
          xdfaCommitMask := combinedMask
          xdfaCommitFinalState := xdfaWordHigh.io.result(2, 0)
          xdfaWordState := NumericDfaState.commit
        }
      }.otherwise {
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
    xdfaInternalState := 0.U
    xdfaInternalStopped := true.B
  }
  when(numericDfaLocalFire && func3t === 3.U) {
    for (state <- 0 until 8) {
      when(reg_v1(state)) {
        xdfaCounters(state) := xdfaCounters(state) + 1.U
      }
    }
  }

  when(listReverseState === ListReverseState.idle && io.in.valid && isListReverse) {
    val usePrefetch = isListReverseLoop && listReversePrefetchValid
    listReverseNext := listReversePrefetchData
    listReversePrefetchValid := false.B
    listReverseCurrent := listReverseActiveCurrent
    listReverseQueryAddress := Mux(isListReverseLoop, listReversePrefetchData, listReverseActiveCurrent)
    listReversePrevious := Mux(isListReverseLoop, listReverseChainPrevious, reg_v2)
    when(isListReverseStep) {
      listReverseChainPrevious := listReverseActiveCurrent
    }
    when(listReverseActiveCurrent === 0.U) {
      listReverseResult := Mux(isListReverseLoop, listReverseChainPrevious, 0.U)
      listReverseState := ListReverseState.done
    }.elsewhen(isListReverseLoop) {
      listReverseState := Mux(usePrefetch && listReversePrefetchHit,
        ListReverseState.storeRequest, ListReverseState.loadRequest)
    }.otherwise {
      // Register the init address before consulting the asynchronous cache.
      // The loop path consumes only its registered prefetch result.
      listReverseState := ListReverseState.lookup
    }
  }.elsewhen(listReverseState === ListReverseState.lookup) {
    listReverseState := ListReverseState.lookupResolve
  }.elsewhen(listReverseState === ListReverseState.lookupResolve) {
    listReverseQueryAddress := io.dcache.readData
    listReverseNext := io.dcache.readData
    listReverseState := Mux(io.dcache.listReverseCapturedHit,
      ListReverseState.storeRequest, ListReverseState.loadRequest)
  }.elsewhen(listReverseState === ListReverseState.loadRequest && io.memReq.fire) {
    listReverseState := ListReverseState.loadResponse
  }.elsewhen(listReverseState === ListReverseState.loadResponse && io.memResp.valid) {
    listReverseQueryAddress := io.memResp.bits
    listReverseNext := io.memResp.bits
    listReverseState := ListReverseState.storeRequest
  }.elsewhen(listReverseState === ListReverseState.storeRequest && io.memReq.fire) {
    listReverseState := ListReverseState.cacheUpdate
  }.elsewhen(listReverseState === ListReverseState.cacheUpdate) {
    val resolvedPrefetchHit = listReverseNext === listReverseCurrent || io.dcache.listReverseCapturedHit
    val prefetchedData = Mux(
      listReverseNext === listReverseCurrent,
      listReversePrevious,
      io.dcache.readData
    )
    listReversePrefetchHit := resolvedPrefetchHit
    listReversePrefetchData := prefetchedData
    when(isListReverseLoop && listReverseNext =/= 0.U) {
      listReversePrevious := listReverseCurrent
      listReverseCurrent := listReverseNext
      listReverseNext := prefetchedData
      listReverseQueryAddress := prefetchedData
      listReverseState := Mux(resolvedPrefetchHit, ListReverseState.storeRequest, ListReverseState.loadRequest)
    }.otherwise {
      listReverseResult := Mux(isListReverseLoop, listReverseCurrent, listReverseNext)
      listReversePrefetchValid := !isListReverseLoop && listReverseNext =/= 0.U
      listReverseState := ListReverseState.done
    }
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
    xmsumIssueRow := 0.U
    xmsumIssueColumn := 0.U
    xmsumResponseRow := 0.U
    xmsumResponseColumn := 0.U
    xmsumAllIssued := false.B
    xmsumClip  := Cat(Fill(16, reg_v2(15)), reg_v2(15, 0)).asSInt
    xmsumTmp   := 0.U
    xmsumPrev  := 0.U
    xmsumRet   := 0.U
    xmsumResponsePending := false.B
    xmsumResponseLast := false.B
    when(n === 0.U) {
      xmsumResult := 0.U
      xmsumState  := XmsumState.done
    }.otherwise {
      xmsumState := XmsumState.firstRequest
    }
  }.elsewhen(xmsumState === XmsumState.firstRequest && io.memReq.fire) {
    xmsumState := XmsumState.stream
  }.elsewhen(xmsumState === XmsumState.stream) {
    when(xmsumResponsePending) {
      xmsumTmp := Mux(xmsumResponseClipped, 0.U, xmsumResponseSum)
      xmsumPrev := xmsumResponseData
      xmsumRet := xmsumResponseNextRet
      xmsumResponsePending := false.B
      when(xmsumResponseLast) {
        xmsumResult := Cat(Fill(16, xmsumResponseNextRet(15)), xmsumResponseNextRet)
        xmsumState := XmsumState.done
      }
    }

    when(io.memResp.valid) {
      val endOfResponseRow = xmsumResponseColumn + 1.U === xmsumSize
      val endOfResponseMatrix = endOfResponseRow && xmsumResponseRow + 1.U === xmsumSize
      xmsumResponseData := io.memResp.bits
      xmsumResponseLast := endOfResponseMatrix
      xmsumResponsePending := true.B
      when(!endOfResponseMatrix) {
        when(endOfResponseRow) {
          xmsumResponseRow := xmsumResponseRow + 1.U
          xmsumResponseColumn := 0.U
        }.otherwise {
          xmsumResponseColumn := xmsumResponseColumn + 1.U
        }
      }
    }
  }.elsewhen(xmsumState === XmsumState.done && io.out.fire) {
    xmsumState := XmsumState.idle
  }

  val xmsumIssueActive = xmsumState === XmsumState.firstRequest || xmsumState === XmsumState.stream
  when(xmsumIssueActive && io.memReq.fire) {
    val endOfIssueRow = xmsumIssueColumn + 1.U === xmsumSize
    val endOfIssueMatrix = endOfIssueRow && xmsumIssueRow + 1.U === xmsumSize
    when(endOfIssueMatrix) {
      xmsumAllIssued := true.B
    }.otherwise {
      xmsumAddress := xmsumAddress + 4.U
      when(endOfIssueRow) {
        xmsumIssueRow := xmsumIssueRow + 1.U
        xmsumIssueColumn := 0.U
      }.otherwise {
        xmsumIssueColumn := xmsumIssueColumn + 1.U
      }
    }
  }

  when(xmsumState === XmsumState.stream && xmsumResponseLast && xmsumResponsePending) {
    assert(!io.memResp.valid, "xmsum must not receive data after the final matrix element")
  }

  // val pcAddImm   = dinst.pc + dinst.info.imm
  val pcAddImm   = dinst.info.pcAddImm
  val reg1AddImm = "h80".U(8.W) ## 0.U(2.W) ## dinst.info.reg1AddImm

  // Branches/JAL use PC+imm, while a JALR BTB entry must learn the resolved
  // rs1+imm target.  The BTB stores only the same trimmed PC bits either way.
  val controlFlowTarget = Mux(isListReverseLoop, dinst.pc, Mux(isTypJALR, reg1AddImm, pcAddImm))

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
  special_in.xmbmValid := dinst.info.xmbmValid
  special_in.xmacaccValid := dinst.info.xmacaccValid

  val fastIntegerOut = fastInteger.io.out.bits
  val specialExecutionOut = alu.io.out.bits
  val simpleAcceleratorOut = alu.io.acceleratorOut
  val resultIsFast = dinst.info.resultKind === ResultKind.fastInt
  val resultIsLong = dinst.info.resultKind === ResultKind.longArithmetic
  val resultIsAccelerator = dinst.info.resultKind === ResultKind.accelerator
  val simpleAccelerator = dinst.info.crcValid || dinst.info.xbmulValid

  fastInteger.io.in.valid := io.in.valid && isTypArithmetic && resultIsFast
  alu.io.in.valid :=
    io.in.valid && isTypArithmetic && (resultIsLong || (resultIsAccelerator && simpleAccelerator))

  when(io.in.valid && !isDotConfig && !isDotN && (fastAluRs1Groups.orR || fastAluRs2Groups.orR)) {
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
  csrs.io.read.en   := io.in.valid && isTypSys
  csrs.io.read.addr := csr_raddr
  val csr_rdata = csrs.io.read.data

  val csrPrepared   = RegInit(false.B)
  val csrReadDataReg = Reg(Types.UWord)
  val csrNextPCReg  = Reg(Types.UWord)
  val csrWriteEnReg = Reg(Bool())
  val csrWriteAddrReg = Reg(UInt(Types.BitWidth.csr_addr.W))
  val csrWriteDataReg = Reg(Types.UWord)
  val csrECallReg   = Reg(Bool())

  val writeBackInfo = io.out.bits.exuWriteBack

  val csrWrEnable = WireDefault(isTypSys && func3t(1, 0) =/= 0.U)
  val csrWrAddr   = WireDefault(csr_raddr)
  val csrWrData   = Wire(Types.UWord)

  object CSROp {
    val RW = 1.U
    val RS = 2.U
    val RC = 3.U
  }

  val csrUIMM = dinst.code(19, 15).pad(32)

  // val isCSRRW = (func3t === CSROp.RW) && isTypSys
  // val isCSRRS = (func3t === CSROp.RS) && isTypSys

  when(isTypSys) {

    val isRW = func3t(1, 0) === CSROp.RW
    val isRS = func3t(1, 0) === CSROp.RS
    val isRC = func3t(1, 0) === CSROp.RC

    val csrOpMask = Mux(func3t(2), csrUIMM, reg_v1)

    when(is_ecall) {
      csrWrAddr := CSRAddr.mepc
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
    csrWrData := DontCare
  }

  // CSR instructions are local two-phase EXU transactions. The first cycle
  // captures every wide value; the second cycle commits and emits the result.
  // A redirect from an older instruction clears an uncommitted transaction.
  when(!io.in.valid) {
    csrPrepared := false.B
  }.elsewhen(isTypSys && !csrPrepared) {
    csrPrepared     := true.B
    csrReadDataReg  := csr_rdata
    csrNextPCReg    := Mux(is_ecall, csrs.io.mtvec, Mux(is_mret, csrs.io.mepc, dinst.info.staticNextPCOrCSRTarget))
    csrWriteEnReg   := csrWrEnable
    csrWriteAddrReg := csrWrAddr
    csrWriteDataReg := csrWrData
    csrECallReg     := is_ecall
  }.elsewhen(io.out.fire && isTypSys) {
    csrPrepared := false.B
  }

  csrs.io.write.en   := csrWriteEnReg && io.out.fire && isTypSys && csrPrepared
  csrs.io.write.addr := csrWriteAddrReg
  csrs.io.write.data := csrWriteDataReg
  csrs.io.is_ecall   := csrECallReg && io.out.fire && isTypSys && csrPrepared

  // --- Inst type decode ---
  val needMemReq = isTypLoad || isTypStore
  val memReqFire = io.memReq.valid && io.memReq.ready

  val isFmtB = InstFmt.hasSame(dinst.info.fmt, InstFmt.branch)

  val equalityDiff = branchRegV1 ^ branchRegV2
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
  val immediateBranchEqual = baseRegV1 === baseRegV2
  val immediateIsLessThan = baseRegV1.asSInt < baseRegV2.asSInt
  val immediateIsLessThanU = baseRegV1 < baseRegV2
  val immediateTakeBranch = Mux1H(
    Seq(
      dinst.info.is_beq  -> immediateBranchEqual,
      dinst.info.is_bne  -> !immediateBranchEqual,
      dinst.info.is_blt  -> immediateIsLessThan,
      dinst.info.is_bge  -> !immediateIsLessThan,
      dinst.info.is_bltu -> immediateIsLessThanU,
      dinst.info.is_bgeu -> !immediateIsLessThanU
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
  lsuInfo.dcacheHit := lsuInfo.cacheableLoad && io.dcache.hit
  lsuInfo.dcacheStoreEpoch := io.dcache.storeEpoch

  val snpc = Mux(isTypSys, csrNextPCReg, dinst.info.staticNextPCOrCSRTarget)

  val useSingleCycleForward = isTypArithmetic && resultIsFast
  val dcacheWalkerResult = Mux(isDotN, io.dcache.dotNResult, io.dcache.listFindResult)
  val acceleratorData = Mux(
    isNumericDfa,
    xdfaWordResult,
    Mux(isListReverse, listReverseResult,
      Mux(isDcacheWalker, dcacheWalkerResult, Mux(isXmsum, xmsumResult, simpleAcceleratorOut)))
  )

  writeBackInfo.resultKind := dinst.info.resultKind
  writeBackInfo.fastResult.valid := dinst.info.rdWrEn && resultIsFast
  writeBackInfo.fastResult.rd := dinst.info.rd
  writeBackInfo.fastResult.data := fastIntegerOut
  writeBackInfo.directResult.valid := dinst.info.rdWrEn && dinst.info.resultKind === ResultKind.direct
  writeBackInfo.directResult.rd := dinst.info.rd
  writeBackInfo.directResult.data := Mux(isTypSys, csrReadDataReg, dinst.info.preMuxWrBackData)
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
  val ordinaryResultValid =
    (!isTypSys || csrPrepared) &&
      (!isTypArithmetic || isNumericDfa || isDotConfig || Mux(resultIsFast, fastInteger.io.out.valid, alu.io.out.valid))
  val exuResultValid =
    Mux(isNumericDfaStep, xdfaWordDone,
      Mux(isListReverse, listReverseDone, Mux(isXmsum, xmsumDone, ordinaryResultValid)))
  // Keep the same-cycle forwarding loop independent of the multi-cycle M/D/B
  // result mux.  A multi-cycle producer still advertises its destination while it is
  // in EXU, but its data remains unavailable to IDU; a dependent consumer
  // waits one cycle and receives the registered result from LSU instead.
  // Only producers carried by the dedicated fast lane may arm the adjacent
  // EXU bypass token. Other single-cycle results wait one cycle and use the
  // ordinary LSU-to-IDU bypass, keeping them out of the ALU recurrence.
  io.fwd := WrBackForwardInfo(io.in.valid, dinst, useSingleCycleForward, fastIntegerOut)

  val memWMask = GenMemWMask(reg1AddImm(1, 0), func3t)

  val memWData = GenMemWData(reg1AddImm(1, 0), storeRegV2)

  val listReverseStoreRequest = listReverseState === ListReverseState.storeRequest
  // The list-reversal operand is held in the IDU/EXU payload for the instruction's
  // entire residence in EXU.  Its decoder disables the previous-EXU direct
  // bypass, so this registered value cannot create a forwarding-to-tag path.
  // The loop consumes the init result and then walks the remaining runtime
  // chain internally. The done-boundary register keeps that private handoff out
  // of the asynchronous tag RAM.
  val dcacheQueryAddr = Mux(isListReverse, listReverseQueryAddress, reg1AddImm)
  io.dcache.queryIndex := Mux(isListReverse, listReverseQueryAddress(11, 2), io.stagedDcacheQueryIndex)
  io.dcache.queryTag   := dcacheQueryAddr(17, 11)
  io.dcache.listReverseHitCapture :=
    listReverseState === ListReverseState.lookup || listReverseState === ListReverseState.storeRequest
  io.dcache.listFindStart := io.in.valid && isListFind && !io.dcache.listFindDone
  io.dcache.listFindConsume := io.out.fire && isListFind
  io.dcache.listFindAddress := reg_v1
  io.dcache.listFindTarget := reg_v2(15, 0)
  io.dcache.listFindDataMode := func7t === 3.U
  io.dcache.listFindRequestFire := io.memReq.fire && io.dcache.listFindRequest
  io.dcache.listFindMemResponse := io.memResp
  io.dcache.dotNStart := io.in.valid && isDotN && !io.dcache.dotNDone
  io.dcache.dotNConsume := io.out.fire && isDotN
  io.dcache.dotNAddressA := reg_v1
  io.dcache.dotNAddressB := reg_v2
  io.dcache.dotNLength := dotLength
  io.dcache.dotNBitMode := func7t === 5.U
  io.dcache.dotNRequestFire := io.memReq.fire && io.dcache.dotNRequest
  io.dcache.dotNMemResponse := io.memResp
  // Accelerator requests share the external bus but cannot update the cache's
  // normal store port. Qualify the architectural store locally so cache hit or
  // accelerator state never enters a distributed-memory write-enable cone.
  val normalStoreRequest = isTypStore && io.in.valid && io.out.ready
  val cacheableStoreFire = io.memReq.fire && normalStoreRequest && reg1AddImm(21, 20) === "b01".U
  val listReverseStepCacheStore = isListReverseStep && listReverseState === ListReverseState.cacheUpdate
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
  val listFindRequest = io.dcache.listFindRequest
  val dotNRequest = io.dcache.dotNRequest
  val dcacheWalkerRequest = listFindRequest || dotNRequest
  val dcacheWalkerRequestAddress = Mux(
    dotNRequest,
    io.dcache.dotNRequestAddress,
    io.dcache.listFindRequestAddress
  )
  val xmsumRequest = xmsumIssueActive && !xmsumAllIssued
  val xdfaWordFirstRequest = xdfaWordState === NumericDfaState.idle && io.in.valid && isNumericDfaStep
  val xdfaWordRequest = xdfaWordState === NumericDfaState.request || xdfaWordFirstRequest
  val normalMemReq = Wire(new MemReq)
  // The accelerator kind is held in ID/EX for the instruction's entire EXU
  // residence. Use that registered identity to select the request payload;
  // state-machine request bits only qualify valid. This keeps state decode out
  // of the shared address/data network and does not change the handshake.
  // Xmsum issues one request per cycle, so give its registered address a
  // direct arm instead of passing its selector through every accelerator mux.
  normalMemReq.addr  := Mux(isXmsum, xmsumAddress,
    Mux(isNumericDfaStep,
      Mux(xdfaWordFirstRequest, reg_v2, xdfaWordAddress) & ~3.U(32.W),
      Mux(isListReverse, listReverseCurrent,
        Mux(isDcacheWalker, dcacheWalkerRequestAddress, reg1AddImm))))
  normalMemReq.size  := Mux(isNumericDfaStep || isListReverse || isDcacheWalker || isXmsum,
    2.U, func3t(1, 0))
  normalMemReq.wen   := Mux(isNumericDfaStep, false.B,
    Mux(isListReverse, listReverseStoreRequest, !isDcacheWalker && !isXmsum && isTypStore))
  normalMemReq.wdata := Mux(listReverseStoreRequest, listReversePrevious,
    Mux(isXmsum || isDcacheWalker || (isListReverse && !listReverseStoreRequest), 0.U, memWData))
  normalMemReq.wmask := Mux(listReverseStoreRequest, "b1111".U,
    Mux(isXmsum || isDcacheWalker || (isListReverse && !listReverseStoreRequest), 0.U, memWMask))
  io.memReq.valid := xdfaWordRequest || listReverseRequest || dcacheWalkerRequest || xmsumRequest ||
    (needMemReq && io.in.valid && io.out.ready)
  io.memReq.bits := normalMemReq

  val normalReady = memReqFire || (
    io.out.ready && !needMemReq && exuResultValid
  )
  val normalValid = memReqFire || (
    io.in.valid && !needMemReq && exuResultValid
  )
  val dcacheWalkerDone = Mux(isDotN, io.dcache.dotNDone, io.dcache.listFindDone)
  io.in.ready := Mux(isNumericDfaStep, xdfaWordDone && io.out.ready,
    Mux(isListReverse, listReverseDone && io.out.ready,
      Mux(isDcacheWalker, dcacheWalkerDone && io.out.ready,
        Mux(isXmsum, xmsumDone && io.out.ready, normalReady))))
  io.out.valid := Mux(isNumericDfaStep, xdfaWordDone,
    Mux(isListReverse, listReverseDone,
      Mux(isDcacheWalker, dcacheWalkerDone, Mux(isXmsum, xmsumDone, normalValid))))

  writeBackInfo.iid := dinst.iid

  // --- Next PC ---
  val isJmpCsr = is_ecall || is_mret
  val normalNxtPC = Wire(Types.UWord)

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
  io.nxtPC    := normalNxtPC
  io.pc       := dinst.pc

  io.branchTarget := Mux(isTypBranch || isTypJAL || isTypJALR, controlFlowTarget, snpc)
  // Reuse the existing unconditional-entry bit for direct JAL and the exact
  // return encoding that IDU can validate without an address-add dependency.
  io.isJAL       := isTypJAL || dinst.code === "h00008067".U
  io.isBranch    := isTypBranch
  io.isReturn    := isTypJALR && dinst.code === "h00008067".U
  io.isCall      := (isTypJAL || isTypJALR) && dinst.info.rd =/= 0.U
  io.branchTaken := takeBranch
  io.btbUpdateEn := isTypBranch || isTypJAL || isTypJALR
  val adjacentFastBranchResolve = dinst.info.adjacentFastBranch
  val registeredBranchResolve = adjacentFastBranchResolve
  val registeredBranchRedirect = adjacentFastBranchResolve && (takeBranch ^ dinst.predTake)
  lsuInfo.lateBranchRedirect := exuResultValid && registeredBranchRedirect
  io.predWrong := exuResultValid &&
    ((isFmtB && (takeBranch ^ dinst.predTake)) || io.in.bits.info.notBranchPredWrong)
  dontTouch(io.predWrong)
  io.immediatePredWrong := exuResultValid &&
    ((isFmtB && !registeredBranchResolve && (immediateTakeBranch ^ dinst.predTake)) ||
      io.in.bits.info.notBranchPredWrong)

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
  outInfo.code     := io.in.bits.code
  outInfo.isLoad   := InstType.hasSame(io.in.bits.info.typ, InstType.load)
  outInfo.isStore  := InstType.hasSame(io.in.bits.info.typ, InstType.store)
  outInfo.pc       := io.actual.pc
  outInfo.nxtPC    := io.actual.nxtPC
  outInfo.isEBreak := io.in.bits.code === "h00100073".U
  outInfo.destAddr := io.actual.memAddr
}
