package cpu

import chisel3._
import chisel3.util.{Cat, RegEnable, Valid}
import cpu.alu.mult_gen_mul16_fast
import jyd.{BlkMemGen4KB, DistMemGen512x8}

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
    val dotNLength = Input(UInt(16.W))
    val dotNBitMode = Input(Bool())
    val dotNRequestFire = Input(Bool())
    val dotNMemResponse = Input(Valid(UInt(32.W)))
    val dotNRequest = Output(Bool())
    val dotNRequestAddress = Output(UInt(32.W))
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
  // Two private asynchronous read replicas let list-find fetch a node's next
  // and info words in parallel. List reverse has a separate read-only replica,
  // so neither walker's address selection enters the other's RAM read path.
  val listTagMem = Seq.fill(2)(Seq.fill(2)(Module(new DistMemGen512x8)))
  val listDataMem = Seq.fill(2)(Seq.fill(2)(Seq.fill(4)(Module(new DistMemGen512x8))))
  val listReverseTagMem = Seq.fill(2)(Module(new DistMemGen512x8))
  val listReverseDataMem = Seq.fill(2)(Seq.fill(4)(Module(new DistMemGen512x8)))

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

  object DotNState extends ChiselEnum {
    val idle, stream, drain, done = Value
  }
  val dotNState = RegInit(DotNState.idle)
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
  val dotNAccumulator = Reg(UInt(32.W))
  val dotNBitMode = Reg(Bool())
  val dotNACacheValid = RegInit(VecInit(Seq.fill(8)(false.B)))
  val dotNACacheTag = Reg(Vec(8, UInt(27.W)))
  val dotNACacheData = Reg(Vec(8, UInt(32.W)))
  val dotNACacheHitReg = RegInit(false.B)
  val dotNACacheDataReg = Reg(UInt(16.W))

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

  val listQueryAddresses = Seq(listFindQueryAddress, listFindQueryAddressB)
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
  val listReverseQueryTag = io.listReversePrefetchAddress(17, 11)
  val listReverseQueryBank = io.listReversePrefetchAddress(11)
  val listReverseQueryAddr = io.listReversePrefetchAddress(10, 2)
  listReverseTagMem.foreach(_.io.dpra := listReverseQueryAddr)
  val listReverseTagEntry = Mux(
    listReverseQueryBank,
    listReverseTagMem(1).io.dpo,
    listReverseTagMem(0).io.dpo
  )
  val listReverseStoreConflict =
    storeUpdate && storeIndex === io.listReversePrefetchAddress(11, 2) && storeTag === listReverseQueryTag
  io.listReversePrefetchHit :=
    io.listReversePrefetchAddress(31, 16) === "h8010".U &&
      listReverseTagEntry(0) && listReverseTagEntry(7, 2) === listReverseQueryTag(6, 1) &&
      !listReverseStoreConflict
  listReverseDataMem.flatten.foreach(_.io.dpra := listReverseQueryAddr)
  val listReverseBankData = listReverseDataMem.map(banks => Cat(banks.reverse.map(_.io.dpo)))
  io.listReversePrefetchData := Mux(listReverseQueryBank, listReverseBankData(1), listReverseBankData(0))

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

  val dotNRequestFireValid0 = RegNext(io.dotNRequestFire, false.B)
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
  val dotNLaunchValid = io.dotNMemResponse.valid && dotNRequestFireValid1 && dotNRequestIsB1
  val dotNLaunchLast = dotNLaunchValid && dotNRequestLast1

  dotNMultiplierOperandA := Mux(dotNRequestAFromMemory1, dotNOperandA, dotNRequestCachedA1)
  dotNMultiplierOperandB := dotNResponseHalf
  val dotNMultiplierInputValid = RegNext(dotNLaunchValid, false.B)
  val dotNMultiplierInputLast = RegNext(dotNLaunchLast, false.B)

  val dotNMultiplier = Module(new mult_gen_mul16_fast)
  dotNMultiplier.io.CLK := clock
  dotNMultiplier.io.A := dotNMultiplierOperandA
  dotNMultiplier.io.B := dotNMultiplierOperandB
  dotNProductOperandA := dotNMultiplierOperandA
  dotNProductOperandB := dotNMultiplierOperandB
  val dotNProductValid = RegNext(dotNMultiplierInputValid, false.B)
  val dotNProductLast = RegNext(dotNMultiplierInputLast, false.B)
  val dotNProduct = dotNMultiplier.io.P
  val dotNSignedTerm = dotNProduct -
    Mux(dotNProductOperandA(15), Cat(dotNProductOperandB, 0.U(16.W)), 0.U) -
    Mux(dotNProductOperandB(15), Cat(dotNProductOperandA, 0.U(16.W)), 0.U)
  val dotNBitTerm = (dotNProduct(5, 2) * dotNProduct(11, 5)).pad(32)
  val dotNTerm = Mux(dotNBitMode, dotNBitTerm, dotNSignedTerm)
  val dotNNextAccumulator = dotNAccumulator + dotNTerm

  when(dotNState === DotNState.idle && io.dotNStart) {
    assert(listFindState === ListFindState.idle, "dotN and list-find walkers must be mutually exclusive")
    dotNAddressA := io.dotNAddressA
    dotNAddressB := io.dotNAddressB
    dotNRemaining := io.dotNLength
    dotNStride := Cat(io.dotNLength, 0.U(1.W))
    dotNIssueB := false.B
    dotNAccumulator := 0.U
    dotNBitMode := io.dotNBitMode
    dotNACacheHitReg := dotNLookupHit
    dotNACacheDataReg := dotNLookupHalf
    dotNState := Mux(io.dotNLength === 0.U, DotNState.done, DotNState.stream)
  }.elsewhen(dotNState === DotNState.stream && io.dotNRequestFire) {
    when(dotNRequestIsB) {
      dotNIssueB := false.B
      dotNAddressA := dotNAddressA + 2.U
      dotNAddressB := dotNAddressB + dotNStride
      dotNRemaining := dotNRemaining - 1.U
      dotNACacheHitReg := dotNLookupHit
      dotNACacheDataReg := dotNLookupHalf
      when(dotNRemaining === 1.U) {
        dotNState := DotNState.drain
      }
    }.otherwise {
      dotNIssueB := true.B
    }
  }.elsewhen(dotNState === DotNState.drain && dotNProductValid && dotNProductLast) {
    dotNState := DotNState.done
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

  when(io.dataMutation) {
    dotNACacheValid(dotNMutationIndex) := false.B
  }

  when(dotNProductValid) {
    dotNAccumulator := dotNNextAccumulator
  }

  io.dotNRequest := dotNState === DotNState.stream
  io.dotNRequestAddress := Mux(dotNRequestIsB, dotNAddressB, dotNAddressA) & ~3.U(32.W)
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
    assert(dotNState === DotNState.idle, "list-find and dotN walkers must be mutually exclusive")
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
  listReverseTagMem.zipWithIndex.foreach { case (bank, bankIndex) =>
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
  listReverseDataMem.zipWithIndex.foreach { case (banks, bankIndex) =>
    banks.zipWithIndex.foreach { case (bank, byte) =>
      bank.io.clk := clock
      bank.io.we := dataWrite && dataWriteBank === bankIndex.U && dataWriteMask(byte)
      bank.io.a := dataWriteAddr
      bank.io.d := dataWriteData(8 * byte + 7, 8 * byte)
    }
  }
}
