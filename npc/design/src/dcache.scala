package cpu

import chisel3._
import chisel3.util.{Cat, RegEnable, Valid}
import cpu.alu.mult_gen_mul16_fast
import jyd.{BlkMemGen2KB, DistMemGen512x8}

class DCache extends Module {
  val io = IO(new Bundle {
    val queryIndex = Input(UInt(10.W))
    val queryTag   = Input(UInt(7.W))
    val hit       = Output(Bool())
    val readData  = Output(UInt(32.W))
    val listReverseHitCapture = Input(Bool())
    val listReverseCapturedHit = Output(Bool())

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

  // Two 2 KiB banks form a 4 KiB direct-mapped cache.  Keep the bank select
  // outside the memory address: each Vivado IP remains the proven 512 x 32
  // block-memory configuration and each distributed tag/late-data RAM remains
  // the proven 512 x 8 configuration.
  val dataMem = Seq.fill(2)(Module(new BlkMemGen2KB))
  val tagMem  = Seq.fill(2)(Module(new DistMemGen512x8))
  // Two private asynchronous read replicas let the list walker fetch a node's
  // next and info words in parallel.  Their outputs terminate at walker-local
  // registers and never drive the ordinary DCache hit/result cone.
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

  object DotNState extends ChiselEnum {
    val idle, stream, requestA, responseA, requestB, responseB, launchStored, drain, done = Value
  }
  val dotNState = RegInit(DotNState.idle)
  val dotNOperandA = Reg(UInt(16.W))
  val dotNOperandB = Reg(UInt(16.W))
  val dotNOperandAHigh = Reg(Bool())
  val dotNBufferedBValid = Reg(Bool())
  val dotNBufferedBAddress = Reg(UInt(32.W))
  val dotNMultiplierOperandA = Reg(UInt(16.W))
  val dotNMultiplierOperandB = Reg(UInt(16.W))
  val dotNProductOperandA = Reg(UInt(16.W))
  val dotNProductOperandB = Reg(UInt(16.W))
  val dotNRemaining = Reg(UInt(16.W))
  val dotNStride = Reg(UInt(17.W))
  val dotNAccumulator = Reg(UInt(32.W))
  val dotNBitMode = Reg(Bool())
  val dotNRequestValid = RegInit(false.B)
  val dotNRequestAddressReg = Reg(UInt(32.W))

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

  dataMem.foreach { bank =>
    bank.io.clkb  := clock
    bank.io.enb   := true.B
    bank.io.addrb := queryAddr
  }
  // Block RAM returns the address from the previous cycle.  Its bank select
  // must therefore be delayed by the same cycle; using queryBank directly
  // aliases a load with the following instruction's bank.
  val readBank = RegNext(queryBank)
  io.readData := Mux(readBank, dataMem(1).io.doutb, dataMem(0).io.doutb)

  // List-find and dotN are mutually exclusive, so share their registered
  // private-query addresses. This keeps FSM decode and a 32-bit mux out of the
  // asynchronous tag/data RAM address cone.
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
      entry(0) && entry(7, 1) === tag && !storeConflict
  }
  val listReadData = listDataMem.zipWithIndex.map { case (portBanks, port) =>
    portBanks.flatten.foreach(_.io.dpra := listQueryAddrs(port))
    val bankData = portBanks.map(banks => Cat(banks.reverse.map(_.io.dpo)))
    Mux(listQueryBanks(port), bankData(1), bankData(0))
  }

  val dotNStreamOperandA = Mux(listFindQueryAddress(1), listReadData(0)(31, 16), listReadData(0)(15, 0))
  val dotNStreamOperandB = Mux(listFindQueryAddressB(1), listReadData(1)(31, 16), listReadData(1)(15, 0))
  val dotNStreamLaunch = dotNState === DotNState.stream && listQueryHits(0) && listQueryHits(1)
  val dotNStoredLaunch = dotNState === DotNState.launchStored
  val dotNLaunchValid = dotNStreamLaunch || dotNStoredLaunch
  val dotNLaunchOperandA = Mux(dotNState === DotNState.stream, dotNStreamOperandA, dotNOperandA)
  val dotNLaunchOperandB = Mux(dotNState === DotNState.stream, dotNStreamOperandB, dotNOperandB)
  val dotNLaunchLast = dotNLaunchValid && Mux(dotNStoredLaunch, dotNRemaining === 0.U, dotNRemaining === 1.U)

  dotNMultiplierOperandA := dotNLaunchOperandA
  dotNMultiplierOperandB := dotNLaunchOperandB
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
    listFindQueryAddress := io.dotNAddressA
    listFindQueryAddressB := io.dotNAddressB
    dotNRemaining := io.dotNLength
    dotNStride := Cat(io.dotNLength, 0.U(1.W))
    dotNAccumulator := 0.U
    dotNBitMode := io.dotNBitMode
    dotNRequestValid := false.B
    dotNState := Mux(io.dotNLength === 0.U, DotNState.done, DotNState.stream)
  }.elsewhen(dotNState === DotNState.stream) {
    dotNOperandA := dotNStreamOperandA
    dotNOperandB := dotNStreamOperandB
    dotNOperandAHigh := listFindQueryAddress(1)
    dotNBufferedBAddress := listFindQueryAddressB
    dotNRemaining := dotNRemaining - 1.U
    listFindQueryAddress := listFindQueryAddress + 2.U
    listFindQueryAddressB := listFindQueryAddressB + dotNStride
    when(dotNStreamLaunch) {
      when(dotNRemaining === 1.U) {
        dotNState := DotNState.drain
      }
    }.elsewhen(!listQueryHits(0)) {
      dotNBufferedBValid := listQueryHits(1)
      dotNRequestValid := true.B
      dotNRequestAddressReg := listFindQueryAddress & ~3.U(32.W)
      dotNState := DotNState.requestA
    }.otherwise {
      dotNRequestValid := true.B
      dotNRequestAddressReg := listFindQueryAddressB & ~3.U(32.W)
      dotNState := DotNState.requestB
    }
  }.elsewhen(dotNState === DotNState.requestA && io.dotNRequestFire) {
    dotNRequestValid := false.B
    dotNState := DotNState.responseA
  }.elsewhen(dotNState === DotNState.responseA && io.dotNMemResponse.valid) {
    dotNOperandA := Mux(
      dotNOperandAHigh,
      io.dotNMemResponse.bits(31, 16),
      io.dotNMemResponse.bits(15, 0)
    )
    when(dotNBufferedBValid) {
      dotNState := DotNState.launchStored
    }.otherwise {
      dotNRequestValid := true.B
      dotNRequestAddressReg := dotNBufferedBAddress & ~3.U(32.W)
      dotNState := DotNState.requestB
    }
  }.elsewhen(dotNState === DotNState.requestB && io.dotNRequestFire) {
    dotNRequestValid := false.B
    dotNState := DotNState.responseB
  }.elsewhen(dotNState === DotNState.responseB && io.dotNMemResponse.valid) {
    dotNOperandB := Mux(
      dotNBufferedBAddress(1),
      io.dotNMemResponse.bits(31, 16),
      io.dotNMemResponse.bits(15, 0)
    )
    dotNState := DotNState.launchStored
  }.elsewhen(dotNState === DotNState.launchStored) {
    when(dotNRemaining === 0.U) {
      dotNState := DotNState.drain
    }.otherwise {
      dotNState := DotNState.stream
    }
  }.elsewhen(dotNState === DotNState.drain && dotNProductValid && dotNProductLast) {
    dotNState := DotNState.done
  }.elsewhen(dotNState === DotNState.done && io.dotNConsume) {
    dotNRequestValid := false.B
    dotNState := DotNState.idle
  }

  when(dotNProductValid) {
    dotNAccumulator := dotNNextAccumulator
  }

  io.dotNRequest := dotNRequestValid
  io.dotNRequestAddress := dotNRequestAddressReg
  io.dotNDone := dotNState === DotNState.done
  io.dotNResult := dotNAccumulator

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
    listFindNextHit := listQueryHits(0)
    listFindInfoHit := listQueryHits(1)
    listFindNext := listReadData(0)
    listFindInfo := listReadData(1)
    // Port A has finished reading the node's next pointer. Point it at the
    // captured info target for the following resolve cycle without feeding
    // the hit decision back through the asynchronous RAM address.
    listFindQueryAddress := listReadData(1)
    listFindRequestValid := !listQueryHits(0) || !listQueryHits(1)
    listFindRequestAddressReg := Mux(listQueryHits(0), listFindQueryAddressB, listFindCurrent)
    listFindState := ListFindState.nodeResolve
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
      listFindQueryAddress := listFindInfo
      listFindDataHit := listQueryHits(0)
      listFindWord := listReadData(0)
      listFindRequestValid := !listQueryHits(0)
      listFindRequestAddressReg := listFindInfo
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
    listFindState := ListFindState.dataResolve
  }.elsewhen(listFindState === ListFindState.dataResolve) {
    when(listFindDataHit) {
      val value = Mux(listFindDataMode, Cat(0.U(8.W), listFindWord(7, 0)), listFindWord(31, 16))
      // Speculatively stage the next node before resolving the match.  These
      // registers are irrelevant in done, so the value comparison no longer
      // selects their timing-sensitive D inputs.
      listFindCurrent := listFindNext
      listFindQueryAddress := listFindNext
      listFindQueryAddressB := listFindNext + 4.U
      when(value === listFindTarget) {
        listFindResult := listFindCurrent
        listFindState := ListFindState.done
      }.elsewhen(listFindNext === 0.U) {
        listFindResult := 0.U
        listFindState := ListFindState.done
      }.otherwise {
        listFindState := ListFindState.nodeLookup
      }
    }.elsewhen(io.listFindRequestFire) {
      listFindState := ListFindState.dataMemory
    }
  }.elsewhen(listFindState === ListFindState.dataMemory && io.listFindMemResponse.valid) {
    val value = Mux(listFindDataMode, Cat(0.U(8.W), io.listFindMemResponse.bits(7, 0)),
      io.listFindMemResponse.bits(31, 16))
    listFindCurrent := listFindNext
    listFindQueryAddress := listFindNext
    listFindQueryAddressB := listFindNext + 4.U
    when(value === listFindTarget) {
      listFindResult := listFindCurrent
      listFindState := ListFindState.done
    }.elsewhen(listFindNext === 0.U) {
      listFindResult := 0.U
      listFindState := ListFindState.done
    }.otherwise {
      listFindState := ListFindState.nodeLookup
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

  val dataWrite = storeUpdate || io.update
  val dataWriteMask = Mux(storeUpdate, storeMask, Mux(io.update, io.updateMask, 0.U))
  val dataWriteIndex = Mux(storeUpdate, storeIndex, io.updateAddr(11, 2))
  val dataWriteBank  = dataWriteIndex(9)
  val dataWriteAddr  = dataWriteIndex(8, 0)
  val dataWriteData = Mux(storeUpdate, storeData, io.updateData)
  dataMem.zipWithIndex.foreach { case (bank, index) =>
    bank.io.clka  := clock
    bank.io.ena   := dataWrite && dataWriteBank === index.U
    bank.io.wea   := dataWriteMask
    bank.io.addra := dataWriteAddr
    bank.io.dina  := dataWriteData
  }

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
