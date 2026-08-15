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

    val dot9Start = Input(Bool())
    val dot9Consume = Input(Bool())
    val dot9AddressA = Input(UInt(32.W))
    val dot9AddressB = Input(UInt(32.W))
    val dot9BitMode = Input(Bool())
    val dot9RequestFire = Input(Bool())
    val dot9MemResponse = Input(Valid(UInt(32.W)))
    val dot9Request = Output(Bool())
    val dot9RequestAddress = Output(UInt(32.W))
    val dot9Done = Output(Bool())
    val dot9Result = Output(UInt(32.W))

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

  object Dot9State extends ChiselEnum {
    val idle, lookup, requestA, responseA, requestB, responseB, multiply, accumulate, done = Value
  }
  val dot9State = RegInit(Dot9State.idle)
  val dot9OperandA = Reg(UInt(16.W))
  val dot9OperandB = Reg(UInt(16.W))
  val dot9Index = RegInit(0.U(4.W))
  val dot9Accumulator = Reg(UInt(32.W))
  val dot9Result = Reg(UInt(32.W))
  val dot9BitMode = Reg(Bool())
  val dot9RequestValid = RegInit(false.B)
  val dot9RequestUseB = RegInit(false.B)

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

  // List-find and dot9 are mutually exclusive, so share their registered
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

  val dot9Multiplier = Module(new mult_gen_mul16_fast)
  dot9Multiplier.io.CLK := clock
  dot9Multiplier.io.A := dot9OperandA
  dot9Multiplier.io.B := dot9OperandB
  val dot9Product = dot9Multiplier.io.P
  val dot9SignedTerm = dot9Product -
    Mux(dot9OperandA(15), Cat(dot9OperandB, 0.U(16.W)), 0.U) -
    Mux(dot9OperandB(15), Cat(dot9OperandA, 0.U(16.W)), 0.U)
  val dot9BitTerm = (dot9Product(5, 2) * dot9Product(11, 5)).pad(32)
  val dot9Term = Mux(dot9BitMode, dot9BitTerm, dot9SignedTerm)
  val dot9NextAccumulator = dot9Accumulator + dot9Term

  when(dot9State === Dot9State.idle && io.dot9Start) {
    assert(listFindState === ListFindState.idle, "dot9 and list-find walkers must be mutually exclusive")
    listFindQueryAddress := io.dot9AddressA
    listFindQueryAddressB := io.dot9AddressB
    dot9Index := 0.U
    dot9Accumulator := 0.U
    dot9BitMode := io.dot9BitMode
    dot9RequestValid := false.B
    dot9State := Dot9State.lookup
  }.elsewhen(dot9State === Dot9State.lookup) {
    dot9OperandA := Mux(listFindQueryAddress(1), listReadData(0)(31, 16), listReadData(0)(15, 0))
    dot9OperandB := Mux(listFindQueryAddressB(1), listReadData(1)(31, 16), listReadData(1)(15, 0))
    when(!listQueryHits(0)) {
      dot9RequestValid := true.B
      dot9RequestUseB := false.B
      dot9State := Dot9State.requestA
    }.elsewhen(!listQueryHits(1)) {
      dot9RequestValid := true.B
      dot9RequestUseB := true.B
      dot9State := Dot9State.requestB
    }.otherwise {
      dot9State := Dot9State.multiply
    }
  }.elsewhen(dot9State === Dot9State.requestA && io.dot9RequestFire) {
    dot9RequestValid := false.B
    dot9State := Dot9State.responseA
  }.elsewhen(dot9State === Dot9State.responseA && io.dot9MemResponse.valid) {
    dot9OperandA := Mux(
      listFindQueryAddress(1),
      io.dot9MemResponse.bits(31, 16),
      io.dot9MemResponse.bits(15, 0)
    )
    when(listQueryHits(1)) {
      dot9State := Dot9State.multiply
    }.otherwise {
      dot9RequestValid := true.B
      dot9RequestUseB := true.B
      dot9State := Dot9State.requestB
    }
  }.elsewhen(dot9State === Dot9State.requestB && io.dot9RequestFire) {
    dot9RequestValid := false.B
    dot9State := Dot9State.responseB
  }.elsewhen(dot9State === Dot9State.responseB && io.dot9MemResponse.valid) {
    dot9OperandB := Mux(
      listFindQueryAddressB(1),
      io.dot9MemResponse.bits(31, 16),
      io.dot9MemResponse.bits(15, 0)
    )
    dot9State := Dot9State.multiply
  }.elsewhen(dot9State === Dot9State.multiply) {
    dot9State := Dot9State.accumulate
  }.elsewhen(dot9State === Dot9State.accumulate) {
    dot9Accumulator := dot9NextAccumulator
    when(dot9Index === 8.U) {
      dot9Result := dot9NextAccumulator
      dot9State := Dot9State.done
    }.otherwise {
      listFindQueryAddress := listFindQueryAddress + 2.U
      listFindQueryAddressB := listFindQueryAddressB + 18.U
      dot9Index := dot9Index + 1.U
      dot9State := Dot9State.lookup
    }
  }.elsewhen(dot9State === Dot9State.done && io.dot9Consume) {
    dot9RequestValid := false.B
    dot9State := Dot9State.idle
  }

  io.dot9Request := dot9RequestValid
  io.dot9RequestAddress := Mux(
    dot9RequestUseB,
    listFindQueryAddressB,
    listFindQueryAddress
  ) & ~3.U(32.W)
  io.dot9Done := dot9State === Dot9State.done
  io.dot9Result := dot9Result

  when(listFindState === ListFindState.idle && io.listFindStart) {
    assert(dot9State === Dot9State.idle, "list-find and dot9 walkers must be mutually exclusive")
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
      when(value === listFindTarget) {
        listFindResult := listFindCurrent
        listFindState := ListFindState.done
      }.elsewhen(listFindNext === 0.U) {
        listFindResult := 0.U
        listFindState := ListFindState.done
      }.otherwise {
        listFindCurrent := listFindNext
        listFindQueryAddress := listFindNext
        listFindQueryAddressB := listFindNext + 4.U
        listFindState := ListFindState.nodeLookup
      }
    }.elsewhen(io.listFindRequestFire) {
      listFindState := ListFindState.dataMemory
    }
  }.elsewhen(listFindState === ListFindState.dataMemory && io.listFindMemResponse.valid) {
    val value = Mux(listFindDataMode, Cat(0.U(8.W), io.listFindMemResponse.bits(7, 0)),
      io.listFindMemResponse.bits(31, 16))
    when(value === listFindTarget) {
      listFindResult := listFindCurrent
      listFindState := ListFindState.done
    }.elsewhen(listFindNext === 0.U) {
      listFindResult := 0.U
      listFindState := ListFindState.done
    }.otherwise {
      listFindCurrent := listFindNext
      listFindQueryAddress := listFindNext
      listFindQueryAddressB := listFindNext + 4.U
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
