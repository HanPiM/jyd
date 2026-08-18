package cpu

import chisel3._
import chisel3.util.{Cat, RegEnable, Valid}
import cpu.alu.mult_gen_mul16_fast
import jyd.{BlkMemGen4KB, DistMemGen32x32, DistMemGen512x8}

class DCache extends Module {
  val io = IO(new Bundle {
    val queryIndex = Input(UInt(10.W))
    val queryTag   = Input(UInt(7.W))
    val hit       = Output(Bool())
    val readData  = Output(UInt(32.W))
    val listReverseHitCapture = Input(Bool())
    val listReverseCapturedHit = Output(Bool())
    val listReversePrefetchAddress = Input(UInt(32.W))
    val listReversePrefetchHit = Output(Bool())
    val listReversePrefetchData = Output(UInt(32.W))

    val listFindStart = Input(Bool())
    val listFindConsume = Input(Bool())
    val listFindAddress = Input(UInt(32.W))
    val listFindTarget = Input(UInt(16.W))
    val listFindDataMode = Input(Bool())
    val listFindRequestFire = Input(Bool())
    val listFindMemResponse = Input(Valid(UInt(32.W)))
    val listFindRequest = Output(Bool())
    val listFindRequestAddress = Output(UInt(32.W))
    val listFindDone = Output(Bool())
    val listFindResult = Output(UInt(32.W))

    val dotNStart = Input(Bool())
    val dotNConsume = Input(Bool())
    val dotNAddressA = Input(UInt(32.W))
    val dotNAddressB = Input(UInt(32.W))
    val dotNAddressC = Input(UInt(32.W))
    val dotNLength = Input(UInt(16.W))
    val dotNBitMode = Input(Bool())
    val dotNRowMode = Input(Bool())
    val dotNRequestFire = Input(Bool())
    val dotNMemResponse = Input(Valid(UInt(32.W)))
    val dotNRequest = Output(Bool())
    val dotNRequestAddress = Output(UInt(32.W))
    val dotNRequestWrite = Output(Bool())
    val dotNRequestWriteData = Output(UInt(32.W))
    val dotNDone = Output(Bool())
    val dotNResult = Output(UInt(32.W))

    val dataMutation = Input(Bool())
    val dataMutationAddr = Input(UInt(32.W))
    val storeUpdate = Input(Bool())
    val storeFull   = Input(Bool())
    val storeData   = Input(UInt(32.W))
    val storeMask   = Input(UInt(4.W))
    val update     = Input(Bool())
    val updateValid = Input(Bool())
    val updateAddr = Input(UInt(32.W))
    val updateData = Input(UInt(32.W))
    val updateMask = Input(UInt(4.W))
  })

  // Keep the ordinary data path in one 1024 x 32 block RAM. A single RAMB36
  // removes the post-BRAM bank mux while preserving the one-cycle read latency.
  val dataMem = Module(new BlkMemGen4KB)
  val tagMem  = Seq.fill(2)(Module(new DistMemGen512x8))
  val dotNRowAccumulatorMem = Module(new DistMemGen32x32)
  // Two private asynchronous read ports let list-find fetch a node's next and
  // info words in parallel. List reverse reuses port 0 while list-find is idle.
  val listTagMem = Seq.fill(2)(Seq.fill(2)(Module(new DistMemGen512x8)))
  val listDataMem = Seq.fill(2)(Seq.fill(2)(Seq.fill(4)(Module(new DistMemGen512x8))))

  object ListFindState extends ChiselEnum {
    val idle, nodeLookup, nodeResolve, nextMemory, infoRequest, infoMemory, dataLookup, dataResolve, dataMemory,
      done = Value
  }
  val listFindState = RegInit(ListFindState.idle)
  val listFindCurrent = Reg(UInt(32.W))
  val listFindNext = Reg(UInt(32.W))
  val listFindQueryAddress = Reg(UInt(32.W))
  val listFindQueryAddressB = Reg(UInt(32.W))
  val listFindTarget = Reg(UInt(16.W))
  val listFindDataMode = Reg(Bool())
  val listFindResult = Reg(UInt(32.W))
  val listFindWord = Reg(UInt(32.W))
  val listFindNextHit = Reg(Bool())
  val listFindInfoHit = Reg(Bool())
  val listFindDataHit = Reg(Bool())
  val listFindInfo = Reg(UInt(32.W))
  val listFindRequestValid = RegInit(false.B)
  val listFindRequestAddressReg = Reg(UInt(32.W))
  val listFindOwnsPort0 = RegInit(false.B)

  object DotNState extends ChiselEnum {
    val idle, stream, drain, rowLoadA, rowLoadADrain, rowStreamB, rowDrain, rowStore, done = Value
  }
  val dotNState = RegInit(DotNState.idle)
  val dotNStartPending = RegInit(false.B)
  val dotNOperandA = Reg(UInt(16.W))
  val dotNMultiplierOperandA = Reg(UInt(16.W))
  val dotNMultiplierOperandB = Reg(UInt(16.W))
  val dotNProductOperandA = Reg(UInt(16.W))
  val dotNProductOperandB = Reg(UInt(16.W))
  val dotNRemaining = Reg(UInt(16.W))
  val dotNStride = Reg(UInt(17.W))
  val dotNAddressA = Reg(UInt(32.W))
  val dotNAddressB = Reg(UInt(32.W))
  val dotNIssueB = RegInit(false.B)
  val dotNScalarRequestValid = RegInit(false.B)
  val dotNAccumulator = Reg(UInt(32.W))
  val dotNBitMode = Reg(Bool())
  val dotNRowMode = Reg(Bool())
  val dotNACacheValid = RegInit(VecInit(Seq.fill(8)(false.B)))
  val dotNACacheTag = Reg(Vec(8, UInt(27.W)))
  val dotNACacheData = Reg(Vec(8, UInt(32.W)))
  val dotNACacheHitReg = RegInit(false.B)
  val dotNACacheDataReg = Reg(UInt(16.W))
  val dotNRowA = Reg(Vec(16, UInt(16.W)))
  val dotNRowAddressC = Reg(UInt(32.W))
  val dotNRowRequestAddress = Reg(UInt(32.W))
  val dotNRowRequestValid = RegInit(false.B)
  val dotNRowRequestB = RegInit(false.B)
  val dotNRowRequestWrite = RegInit(false.B)
  val dotNRowIssueIndex = Reg(UInt(8.W))
  val dotNRowIssueColumn = Reg(UInt(4.W))
  val dotNRowIssueK = Reg(UInt(4.W))
  val dotNRowStoreIndex = Reg(UInt(4.W))

  // The backing store accepts an ordinary store one cycle before the mirrored
  // cache memories apply it. Do not expose the old line as a hit in that
  // intervening cycle.
  val storeUpdate = RegNext(io.storeUpdate, false.B)
  val storeIndex  = RegEnable(io.queryIndex, io.storeUpdate)
  val storeTag    = RegEnable(io.queryTag, io.storeUpdate)

  val queryBank  = io.queryIndex(9)
  val queryAddr  = io.queryIndex(8, 0)
  val tagEntries = tagMem.map(_.io.dpo)
  val tagEntry   = Mux(queryBank, tagEntries(1), tagEntries(0))

  tagMem.foreach(_.io.dpra := queryAddr)
  val queryHit = tagEntry(0) && tagEntry(7, 1) === io.queryTag
  val queryStoreConflict = storeUpdate && storeIndex === io.queryIndex && storeTag === io.queryTag
  io.hit := queryHit && !queryStoreConflict
  val listReverseCapturedHit = RegEnable(queryHit && !queryStoreConflict, io.listReverseHitCapture)
  io.listReverseCapturedHit := listReverseCapturedHit

  dataMem.io.clkb  := clock
  dataMem.io.enb   := true.B
  dataMem.io.addrb := io.queryIndex
  io.readData      := dataMem.io.doutb

  // Ownership changes only at walker start/consume boundaries. This keeps the
  // multi-bit FSM decode out of the asynchronous RAM address and result cones.
  val listQueryAddresses = Seq(
    Mux(listFindOwnsPort0, listFindQueryAddress, io.listReversePrefetchAddress),
    listFindQueryAddressB
  )
  val listQueryTags = listQueryAddresses.map(_(17, 11))
  val listQueryBanks = listQueryAddresses.map(_(11))
  val listQueryAddrs = listQueryAddresses.map(_(10, 2))
  val listTagEntries = listTagMem.zipWithIndex.map { case (portBanks, port) =>
    portBanks.foreach(_.io.dpra := listQueryAddrs(port))
    Mux(listQueryBanks(port), portBanks(1).io.dpo, portBanks(0).io.dpo)
  }
  val listQueryHits = listTagEntries.zip(listQueryTags).zip(listQueryAddrs).zip(listQueryBanks).map {
    case (((entry, tag), addr), bank) =>
      val storeConflict = storeUpdate && storeIndex === Cat(bank, addr) && storeTag === tag
      // Tag bit 0 is address bit 11, which already selected this physical bank.
      entry(0) && entry(7, 2) === tag(6, 1) && !storeConflict
  }
  val listReadData = listDataMem.zipWithIndex.map { case (portBanks, port) =>
    portBanks.flatten.foreach(_.io.dpra := listQueryAddrs(port))
    val bankData = portBanks.map(banks => Cat(banks.reverse.map(_.io.dpo)))
    Mux(listQueryBanks(port), bankData(1), bankData(0))
  }
  io.listReversePrefetchHit :=
    io.listReversePrefetchAddress(31, 16) === "h8010".U && listQueryHits(0)
  io.listReversePrefetchData := listReadData(0)

  // JYD memory accepts one request per cycle and returns responses in order two
  // cycles later. Cache recently used A words by their complete runtime address.
  // A miss preserves the exact alternating A/B stream; an A hit lets consecutive
  // B requests use the otherwise idle memory bandwidth.
  val dotNMutationIndex = io.dataMutationAddr(4, 2)
  val dotNACacheIndex = dotNAddressA(4, 2)
  val dotNACacheHit = dotNACacheHitReg && !io.dataMutation
  val dotNRequestIsB = dotNIssueB || dotNACacheHit
  val dotNCachedAHalf = dotNACacheDataReg

  val dotNLookupAddress = Mux(dotNState === DotNState.idle, io.dotNAddressA, dotNAddressA + 2.U)
  val dotNLookupIndex = dotNLookupAddress(4, 2)
  val dotNLookupMutation = io.dataMutation && io.dataMutationAddr(31, 2) === dotNLookupAddress(31, 2)
  val dotNLookupHit =
    dotNLookupAddress(31, 16) === "h8010".U && dotNACacheValid(dotNLookupIndex) &&
      dotNACacheTag(dotNLookupIndex) === dotNLookupAddress(31, 5) && !dotNLookupMutation
  val dotNLookupWord = dotNACacheData(dotNLookupIndex)
  val dotNLookupHalf = Mux(dotNLookupAddress(1), dotNLookupWord(31, 16), dotNLookupWord(15, 0))

  val dotNScalarRequestFire = io.dotNRequestFire && dotNScalarRequestValid
  val dotNRequestFireValid0 = RegNext(dotNScalarRequestFire, false.B)
  val dotNRequestIsB0 = RegEnable(dotNRequestIsB, io.dotNRequestFire)
  val dotNRequestHigh0 = RegEnable(Mux(dotNRequestIsB, dotNAddressB(1), dotNAddressA(1)), io.dotNRequestFire)
  val dotNRequestLast0 = RegEnable(dotNRequestIsB && dotNRemaining === 1.U, io.dotNRequestFire)
  val dotNRequestCachedA0 = RegEnable(dotNCachedAHalf, io.dotNRequestFire)
  val dotNRequestAFromMemory0 = RegEnable(dotNIssueB, io.dotNRequestFire)
  val dotNRequestAIndex0 = RegEnable(dotNACacheIndex, io.dotNRequestFire)
  val dotNRequestATag0 = RegEnable(dotNAddressA(31, 5), io.dotNRequestFire)
  val dotNRequestFireValid1 = RegNext(dotNRequestFireValid0, false.B)
  val dotNRequestIsB1 = RegEnable(dotNRequestIsB0, dotNRequestFireValid0)
  val dotNRequestHigh1 = RegEnable(dotNRequestHigh0, dotNRequestFireValid0)
  val dotNRequestLast1 = RegEnable(dotNRequestLast0, dotNRequestFireValid0)
  val dotNRequestCachedA1 = RegEnable(dotNRequestCachedA0, dotNRequestFireValid0)
  val dotNRequestAFromMemory1 = RegEnable(dotNRequestAFromMemory0, dotNRequestFireValid0)
  val dotNRequestAIndex1 = RegEnable(dotNRequestAIndex0, dotNRequestFireValid0)
  val dotNRequestATag1 = RegEnable(dotNRequestATag0, dotNRequestFireValid0)
  val dotNResponseHalf = Mux(dotNRequestHigh1, io.dotNMemResponse.bits(31, 16), io.dotNMemResponse.bits(15, 0))
  val dotNScalarLaunchValid = io.dotNMemResponse.valid && dotNRequestFireValid1 && dotNRequestIsB1
  val dotNScalarLaunchLast = dotNScalarLaunchValid && dotNRequestLast1

  val dotNRowRequestFire = io.dotNRequestFire && dotNRowRequestValid
  val dotNRowReadFire = dotNRowRequestFire && !dotNRowRequestWrite
  val dotNRowLoadAActive = dotNRowRequestValid && !dotNRowRequestWrite && !dotNRowRequestB
  val dotNRowLoadARequest = dotNRowReadFire && !dotNRowRequestB
  val dotNRowBRequest = dotNRowReadFire && dotNRowRequestB
  val dotNRowLoadAFire0 = RegNext(dotNRowLoadARequest, false.B)
  val dotNRowLoadAIndex0 = RegEnable(dotNRowIssueIndex(3, 0), dotNRowLoadARequest)
  val dotNRowLoadAHigh0 = RegEnable(dotNRowRequestAddress(1), dotNRowLoadARequest)
  val dotNRowLoadALast0 = RegEnable(dotNRowIssueIndex === dotNRemaining - 1.U, dotNRowLoadARequest)
  val dotNRowBFire0 = RegNext(dotNRowBRequest, false.B)
  val dotNRowBK0 = RegEnable(dotNRowIssueK, dotNRowBRequest)
  val dotNRowBColumn0 = RegEnable(dotNRowIssueColumn, dotNRowBRequest)
  val dotNRowBHigh0 = RegEnable(dotNRowRequestAddress(1), dotNRowBRequest)
  val dotNRowBLast0 = RegEnable(
    dotNRowIssueK === dotNRemaining - 1.U && dotNRowIssueColumn === dotNRemaining - 1.U,
    dotNRowBRequest
  )
  val dotNRowLoadAFire1 = RegNext(dotNRowLoadAFire0, false.B)
  val dotNRowLoadAIndex1 = RegEnable(dotNRowLoadAIndex0, dotNRowLoadAFire0)
  val dotNRowLoadAHigh1 = RegEnable(dotNRowLoadAHigh0, dotNRowLoadAFire0)
  val dotNRowLoadALast1 = RegEnable(dotNRowLoadALast0, dotNRowLoadAFire0)
  val dotNRowBFire1 = RegNext(dotNRowBFire0, false.B)
  val dotNRowBK1 = RegEnable(dotNRowBK0, dotNRowBFire0)
  val dotNRowBColumn1 = RegEnable(dotNRowBColumn0, dotNRowBFire0)
  val dotNRowBHigh1 = RegEnable(dotNRowBHigh0, dotNRowBFire0)
  val dotNRowBLast1 = RegEnable(dotNRowBLast0, dotNRowBFire0)
  val dotNRowLoadAResponse = io.dotNMemResponse.valid && dotNRowLoadAFire1
  val dotNRowBResponse = io.dotNMemResponse.valid && dotNRowBFire1
  val dotNRowResponseHalf = Mux(dotNRowBHigh1, io.dotNMemResponse.bits(31, 16), io.dotNMemResponse.bits(15, 0))
  val dotNLaunchValid = dotNScalarLaunchValid || dotNRowBResponse
  val dotNLaunchLast = dotNScalarLaunchLast || (dotNRowBResponse && dotNRowBLast1)

  dotNMultiplierOperandA := Mux(
    dotNRowBResponse,
    dotNRowA(dotNRowBK1),
    Mux(dotNRequestAFromMemory1, dotNOperandA, dotNRequestCachedA1)
  )
  dotNMultiplierOperandB := Mux(dotNRowBResponse, dotNRowResponseHalf, dotNResponseHalf)
  val dotNMultiplierColumn = RegEnable(dotNRowBColumn1, dotNRowBResponse)
  val dotNMultiplierInputValid = RegNext(dotNLaunchValid, false.B)
  val dotNMultiplierInputLast = RegNext(dotNLaunchLast, false.B)

  val dotNMultiplier = Module(new mult_gen_mul16_fast)
  dotNMultiplier.io.CLK := clock
  dotNMultiplier.io.A := dotNMultiplierOperandA
  dotNMultiplier.io.B := dotNMultiplierOperandB
  dotNProductOperandA := dotNMultiplierOperandA
  dotNProductOperandB := dotNMultiplierOperandB
  val dotNProductColumn = RegNext(dotNMultiplierColumn)
  val dotNProductValid = RegNext(dotNMultiplierInputValid, false.B)
  val dotNProductLast = RegNext(dotNMultiplierInputLast, false.B)
  val dotNProduct = dotNMultiplier.io.P
  val dotNSignedTerm = dotNProduct -
    Mux(dotNProductOperandA(15), Cat(dotNProductOperandB, 0.U(16.W)), 0.U) -
    Mux(dotNProductOperandB(15), Cat(dotNProductOperandA, 0.U(16.W)), 0.U)
  val dotNBitTerm = (dotNProduct(5, 2) * dotNProduct(11, 5)).pad(32)
  val dotNTerm = Mux(dotNBitMode, dotNBitTerm, dotNSignedTerm)
  val dotNNextAccumulator = dotNAccumulator + dotNTerm
  val dotNRowTermValid = RegNext(dotNProductValid && dotNRowMode, false.B)
  val dotNRowTermLast = RegEnable(dotNProductLast, dotNProductValid && dotNRowMode)
  val dotNRowTermColumn = RegEnable(dotNProductColumn, dotNProductValid && dotNRowMode)
  val dotNRowTerm = RegEnable(dotNTerm, dotNProductValid && dotNRowMode)

  // The load-A phase clears its current lane; an external stall only repeats
  // that harmless zero write, while accepted requests advance the lane. Row
  // products then update the asynchronous LUTRAM read port one pipeline stage
  // after signed/bit term formation.
  val dotNRowAccumulatorWrite = dotNRowLoadAActive || dotNRowTermValid
  dotNRowAccumulatorMem.io.clk := clock
  dotNRowAccumulatorMem.io.we := dotNRowAccumulatorWrite
  dotNRowAccumulatorMem.io.a :=
    Mux(dotNRowLoadAActive, dotNRowIssueIndex(3, 0), dotNRowTermColumn).pad(6)
  dotNRowAccumulatorMem.io.dpra :=
    Mux(dotNRowRequestWrite, dotNRowStoreIndex, dotNRowTermColumn).pad(6)
  dotNRowAccumulatorMem.io.d :=
    Mux(dotNRowLoadAActive, 0.U, dotNRowAccumulatorMem.io.dpo + dotNRowTerm)

  // Capture the EXU request and payload at the cache boundary. The EXU keeps a
  // multi-cycle instruction valid until completion, so the pending bit both
  // breaks the raw start-to-FSM path and prevents a second capture.
  val dotNStartCapture = dotNState === DotNState.idle && !dotNStartPending && io.dotNStart
  when(dotNStartCapture) {
    assert(listFindState === ListFindState.idle, "dotN and list-find walkers must be mutually exclusive")
    dotNStartPending := true.B
    dotNAddressA := io.dotNAddressA
    dotNAddressB := io.dotNAddressB
    dotNRowAddressC := io.dotNAddressC
    dotNRemaining := io.dotNLength
    dotNStride := Cat(io.dotNLength, 0.U(1.W))
    dotNBitMode := io.dotNBitMode
    dotNRowMode := io.dotNRowMode
    dotNACacheHitReg := dotNLookupHit
    dotNACacheDataReg := dotNLookupHalf
  }

  when(dotNState === DotNState.idle && dotNStartPending) {
    assert(listFindState === ListFindState.idle, "dotN and list-find walkers must be mutually exclusive")
    dotNStartPending := false.B
    dotNIssueB := false.B
    dotNAccumulator := 0.U
    when(dotNRowMode) {
      assert(dotNRemaining >= 4.U && dotNRemaining <= 16.U, "dot-row length must be in [4, 16]")
      dotNScalarRequestValid := false.B
      dotNRowRequestAddress := dotNAddressA
      dotNRowRequestValid := true.B
      dotNRowRequestB := false.B
      dotNRowRequestWrite := false.B
      dotNRowIssueIndex := 0.U
      dotNRowIssueColumn := 0.U
      dotNRowIssueK := 0.U
      dotNRowStoreIndex := 0.U
      dotNState := DotNState.rowLoadA
    }.otherwise {
      dotNScalarRequestValid := dotNRemaining =/= 0.U
      dotNRowRequestValid := false.B
      dotNRowRequestB := false.B
      dotNRowRequestWrite := false.B
      dotNState := Mux(dotNRemaining === 0.U, DotNState.done, DotNState.stream)
    }
  }.elsewhen(dotNState === DotNState.stream && io.dotNRequestFire) {
    when(dotNRequestIsB) {
      dotNIssueB := false.B
      dotNAddressA := dotNAddressA + 2.U
      dotNAddressB := dotNAddressB + dotNStride
      dotNRemaining := dotNRemaining - 1.U
      dotNACacheHitReg := dotNLookupHit
      dotNACacheDataReg := dotNLookupHalf
      when(dotNRemaining === 1.U) {
        dotNScalarRequestValid := false.B
        dotNState := DotNState.drain
      }
    }.otherwise {
      dotNIssueB := true.B
    }
  }.elsewhen(dotNState === DotNState.drain && dotNProductValid && dotNProductLast) {
    dotNState := DotNState.done
  }.elsewhen(dotNState === DotNState.rowLoadA && dotNRowRequestFire) {
    dotNRowRequestAddress := dotNRowRequestAddress + 2.U
    dotNRowIssueIndex := dotNRowIssueIndex + 1.U
    when(dotNRowIssueIndex === dotNRemaining - 1.U) {
      dotNRowRequestValid := false.B
      dotNState := DotNState.rowLoadADrain
    }
  }.elsewhen(dotNState === DotNState.rowStreamB && dotNRowRequestFire) {
    dotNRowRequestAddress := dotNRowRequestAddress + 2.U
    when(dotNRowIssueColumn === dotNRemaining - 1.U) {
      dotNRowIssueColumn := 0.U
      dotNRowIssueK := dotNRowIssueK + 1.U
    }.otherwise {
      dotNRowIssueColumn := dotNRowIssueColumn + 1.U
    }
    when(dotNRowIssueK === dotNRemaining - 1.U && dotNRowIssueColumn === dotNRemaining - 1.U) {
      dotNRowRequestValid := false.B
      dotNState := DotNState.rowDrain
    }
  }.elsewhen(dotNState === DotNState.rowStore && dotNRowRequestFire) {
    dotNRowRequestAddress := dotNRowRequestAddress + 4.U
    dotNRowStoreIndex := dotNRowStoreIndex + 1.U
    when(dotNRowStoreIndex === dotNRemaining - 1.U) {
      dotNRowRequestValid := false.B
      dotNRowRequestWrite := false.B
      dotNState := DotNState.done
    }
  }.elsewhen(dotNState === DotNState.done && io.dotNConsume) {
    dotNState := DotNState.idle
  }

  when(io.dotNMemResponse.valid && dotNRequestFireValid1) {
    when(!dotNRequestIsB1) {
      dotNOperandA := dotNResponseHalf
      when(dotNRequestATag1(26, 11) === "h8010".U) {
        dotNACacheValid(dotNRequestAIndex1) := true.B
        dotNACacheTag(dotNRequestAIndex1) := dotNRequestATag1
        dotNACacheData(dotNRequestAIndex1) := io.dotNMemResponse.bits
      }
    }
  }

  when(dotNRowLoadAResponse) {
    dotNRowA(dotNRowLoadAIndex1) :=
      Mux(dotNRowLoadAHigh1, io.dotNMemResponse.bits(31, 16), io.dotNMemResponse.bits(15, 0))
    when(dotNRowLoadALast1) {
      dotNRowRequestAddress := dotNAddressB
      dotNRowRequestValid := true.B
      dotNRowRequestB := true.B
      dotNState := DotNState.rowStreamB
    }
  }

  when(io.dataMutation) {
    dotNACacheValid(dotNMutationIndex) := false.B
  }
  when(dotNRowRequestFire && dotNRowRequestWrite) {
    dotNACacheValid(dotNRowRequestAddress(4, 2)) := false.B
  }

  when(dotNProductValid && !dotNRowMode) {
    dotNAccumulator := dotNNextAccumulator
  }
  when(dotNRowTermValid && dotNRowTermLast) {
    dotNRowRequestAddress := dotNRowAddressC
    dotNRowRequestValid := true.B
    dotNRowRequestB := false.B
    dotNRowRequestWrite := true.B
    dotNState := DotNState.rowStore
  }

  io.dotNRequest := dotNScalarRequestValid || dotNRowRequestValid
  io.dotNRequestAddress := Mux(
    dotNRowRequestValid,
    dotNRowRequestAddress,
    Mux(dotNRequestIsB, dotNAddressB, dotNAddressA)
  ) & ~3.U(32.W)
  io.dotNRequestWrite := dotNRowRequestWrite
  io.dotNRequestWriteData := dotNRowAccumulatorMem.io.dpo
  io.dotNDone := dotNState === DotNState.done
  io.dotNResult := dotNAccumulator

  def stageListFindNode(nodeAddress: UInt): Unit = {
    listFindNextHit := listQueryHits(0)
    listFindInfoHit := listQueryHits(1)
    listFindNext := listReadData(0)
    listFindInfo := listReadData(1)
    // Port A has finished reading the node's next pointer. Point it at the
    // captured info target for the following resolve cycle without feeding
    // the hit decision back through the asynchronous RAM address.
    listFindQueryAddress := listReadData(1)
    listFindRequestValid := !listQueryHits(0) || !listQueryHits(1)
    listFindRequestAddressReg := Mux(listQueryHits(0), listFindQueryAddressB, nodeAddress)
    listFindState := ListFindState.nodeResolve
  }

  when(listFindState === ListFindState.idle && io.listFindStart) {
    assert(
      dotNState === DotNState.idle && !dotNStartPending && !dotNStartCapture,
      "list-find and dotN walkers must be mutually exclusive"
    )
    listFindOwnsPort0 := true.B
    listFindRequestValid := false.B
    listFindCurrent := io.listFindAddress
    listFindQueryAddress := io.listFindAddress
    listFindQueryAddressB := io.listFindAddress + 4.U
    listFindTarget := io.listFindTarget
    listFindDataMode := io.listFindDataMode
    when(io.listFindAddress === 0.U) {
      listFindResult := 0.U
      listFindState := ListFindState.done
    }.otherwise {
      listFindState := ListFindState.nodeLookup
    }
  }.elsewhen(listFindState === ListFindState.nodeLookup) {
    stageListFindNode(listFindCurrent)
  }.elsewhen(listFindState === ListFindState.nodeResolve) {
    when(!listFindNextHit) {
      when(io.listFindRequestFire) {
        listFindState := ListFindState.nextMemory
      }
    }.elsewhen(!listFindInfoHit) {
      when(io.listFindRequestFire) {
        listFindState := ListFindState.infoMemory
      }
    }.otherwise {
      listFindDataHit := listQueryHits(0)
      listFindWord := listReadData(0)
      listFindRequestValid := !listQueryHits(0)
      listFindRequestAddressReg := listFindInfo
      // The data word is captured above. Use both private read ports to fetch
      // the next node while the registered word is resolved.
      listFindQueryAddress := listFindNext
      listFindQueryAddressB := listFindNext + 4.U
      listFindState := ListFindState.dataResolve
    }
  }.elsewhen(listFindState === ListFindState.nextMemory && io.listFindMemResponse.valid) {
    listFindNext := io.listFindMemResponse.bits
    when(listFindInfoHit) {
      listFindQueryAddress := listFindInfo
      listFindState := ListFindState.dataLookup
    }.otherwise {
      listFindRequestValid := true.B
      listFindRequestAddressReg := listFindQueryAddressB
      listFindState := ListFindState.infoRequest
    }
  }.elsewhen(listFindState === ListFindState.infoRequest) {
    when(io.listFindRequestFire) {
      listFindState := ListFindState.infoMemory
    }
  }.elsewhen(listFindState === ListFindState.infoMemory && io.listFindMemResponse.valid) {
    listFindQueryAddress := io.listFindMemResponse.bits
    listFindState := ListFindState.dataLookup
  }.elsewhen(listFindState === ListFindState.dataLookup) {
    listFindDataHit := listQueryHits(0)
    listFindWord := listReadData(0)
    listFindRequestValid := !listQueryHits(0)
    listFindRequestAddressReg := listFindQueryAddress
    listFindQueryAddress := listFindNext
    listFindQueryAddressB := listFindNext + 4.U
    listFindState := ListFindState.dataResolve
  }.elsewhen(listFindState === ListFindState.dataResolve) {
    when(listFindDataHit) {
      val value = Mux(listFindDataMode, Cat(0.U(8.W), listFindWord(7, 0)), listFindWord(31, 16))
      // The next node was prefetched while this registered value was waiting
      // to be compared. Its metadata is irrelevant when the search completes.
      stageListFindNode(listFindNext)
      listFindCurrent := listFindNext
      when(value === listFindTarget) {
        listFindRequestValid := false.B
        listFindResult := listFindCurrent
        listFindState := ListFindState.done
      }.elsewhen(listFindNext === 0.U) {
        listFindRequestValid := false.B
        listFindResult := 0.U
        listFindState := ListFindState.done
      }
    }.elsewhen(io.listFindRequestFire) {
      listFindState := ListFindState.dataMemory
    }
  }.elsewhen(listFindState === ListFindState.dataMemory && io.listFindMemResponse.valid) {
    val value = Mux(listFindDataMode, Cat(0.U(8.W), io.listFindMemResponse.bits(7, 0)),
      io.listFindMemResponse.bits(31, 16))
    stageListFindNode(listFindNext)
    listFindCurrent := listFindNext
    when(value === listFindTarget) {
      listFindRequestValid := false.B
      listFindResult := listFindCurrent
      listFindState := ListFindState.done
    }.elsewhen(listFindNext === 0.U) {
      listFindRequestValid := false.B
      listFindResult := 0.U
      listFindState := ListFindState.done
    }
  }.elsewhen(listFindState === ListFindState.done && io.listFindConsume) {
    listFindRequestValid := false.B
    listFindOwnsPort0 := false.B
    listFindState := ListFindState.idle
  }

  when(io.listFindRequestFire) {
    listFindRequestValid := false.B
  }

  io.listFindRequest := listFindRequestValid
  io.listFindRequestAddress := listFindRequestAddressReg
  io.listFindDone := listFindState === ListFindState.done
  io.listFindResult := listFindResult

  // Register the ordinary store port at the cache boundary. The external store
  // has already committed its request, while this local copy updates the cache
  // one cycle later. This prevents EXU valid/decode from directly driving every
  // mirrored distributed-memory write enable.
  val storeFull   = RegEnable(io.storeFull, io.storeUpdate)
  val storeData   = RegEnable(io.storeData, io.storeUpdate)
  val storeMask   = RegEnable(io.storeMask, io.storeUpdate)
  when(storeUpdate) {
    assert(!io.update, "A delayed store and cache refill must be mutually exclusive")
  }

  // A store wins over an older WBU refill/update. Full-word stores allocate a
  // complete line. A narrow store conservatively invalidates the line: feeding
  // the asynchronous tag lookup back into the tag RAM write data forms a long
  // read/compare/write path, while retaining the line is only a performance
  // optimization. The backing memory and byte-masked data write remain exact.
  val updateTagData = Cat(io.updateAddr(17, 11), io.updateValid)
  val tagWrite      = storeUpdate || io.update
  val tagWriteIndex = Mux(storeUpdate, storeIndex, io.updateAddr(11, 2))
  val tagWriteBank  = tagWriteIndex(9)
  val tagWriteAddr  = tagWriteIndex(8, 0)
  tagMem.zipWithIndex.foreach { case (bank, index) =>
    val storeTagData  = Cat(storeTag, storeFull)
    val tagWriteData  = Mux(storeUpdate, storeTagData, updateTagData)
    bank.io.clk := clock
    bank.io.we  := tagWrite && tagWriteBank === index.U
    bank.io.a   := tagWriteAddr
    bank.io.d   := tagWriteData
  }
  listTagMem.flatten.zipWithIndex.foreach { case (bank, replicaIndex) =>
    val bankIndex = replicaIndex % 2
    val storeTagData = Cat(storeTag, storeFull)
    val tagWriteData = Mux(storeUpdate, storeTagData, updateTagData)
    bank.io.clk := clock
    bank.io.we := tagWrite && tagWriteBank === bankIndex.U
    bank.io.a := tagWriteAddr
    bank.io.d := tagWriteData
  }
  val dataWrite = storeUpdate || io.update
  val dataWriteMask = Mux(storeUpdate, storeMask, Mux(io.update, io.updateMask, 0.U))
  val dataWriteIndex = Mux(storeUpdate, storeIndex, io.updateAddr(11, 2))
  val dataWriteData = Mux(storeUpdate, storeData, io.updateData)
  dataMem.io.clka  := clock
  dataMem.io.ena   := dataWrite
  dataMem.io.wea   := dataWriteMask
  dataMem.io.addra := dataWriteIndex
  dataMem.io.dina  := dataWriteData

  val dataWriteBank = dataWriteIndex(9)
  val dataWriteAddr = dataWriteIndex(8, 0)

  listDataMem.foreach { portBanks =>
    portBanks.zipWithIndex.foreach { case (banks, bankIndex) =>
      banks.zipWithIndex.foreach { case (bank, byte) =>
        bank.io.clk := clock
        bank.io.we := dataWrite && dataWriteBank === bankIndex.U && dataWriteMask(byte)
        bank.io.a := dataWriteAddr
        bank.io.d := dataWriteData(8 * byte + 7, 8 * byte)
      }
    }
  }
}
