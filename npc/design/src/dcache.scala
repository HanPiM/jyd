package cpu

import chisel3._
import chisel3.util.{Cat, RegEnable, Valid}
import jyd.{BlkMemGen2KB, DistMemGen512x8}

class DCache extends Module {
  val io = IO(new Bundle {
    val queryIndex = Input(UInt(10.W))
    val queryTag   = Input(UInt(7.W))
    val lateQueryIndex = Input(UInt(10.W))
    val hit       = Output(Bool())
    val readData  = Output(UInt(32.W))
    val lateReadData = Output(UInt(32.W))

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
  // The normal LSU/WBU path keeps using the synchronous BRAM.  Four byte-wide
  // distributed memories mirror its writes and provide an asynchronous C0
  // lookup for the narrow late-load path.  EXU captures this value in its
  // registered LSU payload; it is never consumed directly by the C1 adder.
  val lateDataMem = Seq.fill(2)(Seq.fill(4)(Module(new DistMemGen512x8)))
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

  val queryBank  = io.queryIndex(9)
  val queryAddr  = io.queryIndex(8, 0)
  val tagEntries = tagMem.map(_.io.dpo)
  val tagEntry   = Mux(queryBank, tagEntries(1), tagEntries(0))

  tagMem.foreach(_.io.dpra := queryAddr)
  val queryHit = tagEntry(0) && tagEntry(7, 1) === io.queryTag
  io.hit := queryHit

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

  val lateQueryBank = io.lateQueryIndex(9)
  val lateQueryAddr = io.lateQueryIndex(8, 0)
  lateDataMem.flatten.foreach { bank =>
    bank.io.dpra := lateQueryAddr
  }
  val lateReadData = lateDataMem.map { banks => Cat(banks.reverse.map(_.io.dpo)) }
  io.lateReadData := Mux(lateQueryBank, lateReadData(1), lateReadData(0))

  val listQueryAddresses = Seq(listFindQueryAddress, listFindQueryAddressB)
  val listQueryTags = listQueryAddresses.map(_(17, 11))
  val listQueryBanks = listQueryAddresses.map(_(11))
  val listQueryAddrs = listQueryAddresses.map(_(10, 2))
  val listTagEntries = listTagMem.zipWithIndex.map { case (portBanks, port) =>
    portBanks.foreach(_.io.dpra := listQueryAddrs(port))
    Mux(listQueryBanks(port), portBanks(1).io.dpo, portBanks(0).io.dpo)
  }
  val listQueryHits = listTagEntries.zip(listQueryTags).map { case (entry, tag) =>
    entry(0) && entry(7, 1) === tag
  }
  val listReadData = listDataMem.zipWithIndex.map { case (portBanks, port) =>
    portBanks.flatten.foreach(_.io.dpra := listQueryAddrs(port))
    val bankData = portBanks.map(banks => Cat(banks.reverse.map(_.io.dpo)))
    Mux(listQueryBanks(port), bankData(1), bankData(0))
  }

  when(listFindState === ListFindState.idle && io.listFindStart) {
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
      listFindState := ListFindState.dataResolve
    }
  }.elsewhen(listFindState === ListFindState.nextMemory && io.listFindMemResponse.valid) {
    listFindNext := io.listFindMemResponse.bits
    when(listFindInfoHit) {
      listFindQueryAddress := listFindInfo
      listFindState := ListFindState.dataLookup
    }.otherwise {
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
    listFindState := ListFindState.idle
  }

  val listFindNextRequest = listFindState === ListFindState.nodeResolve && !listFindNextHit
  val listFindInfoRequest = (listFindState === ListFindState.nodeResolve && listFindNextHit && !listFindInfoHit) ||
    listFindState === ListFindState.infoRequest
  val listFindDataRequest = listFindState === ListFindState.dataResolve && !listFindDataHit
  io.listFindRequest := listFindNextRequest || listFindInfoRequest || listFindDataRequest
  io.listFindRequestAddress := Mux(listFindNextRequest, listFindCurrent,
    Mux(listFindInfoRequest, listFindQueryAddressB, listFindQueryAddress))
  io.listFindDone := listFindState === ListFindState.done
  io.listFindResult := listFindResult

  // Register the ordinary store port at the cache boundary. The external store
  // has already committed its request, while this local copy updates the cache
  // one cycle later. This prevents EXU valid/decode from directly driving every
  // mirrored distributed-memory write enable.
  val storeUpdate = RegNext(io.storeUpdate, false.B)
  val storeFull   = RegEnable(io.storeFull, io.storeUpdate)
  val storeIndex  = RegEnable(io.queryIndex, io.storeUpdate)
  val storeTag    = RegEnable(io.queryTag, io.storeUpdate)
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

  lateDataMem.zipWithIndex.foreach { case (banks, bankIndex) =>
    banks.zipWithIndex.foreach { case (bank, byte) =>
      bank.io.clk := clock
      bank.io.we  := dataWrite && dataWriteBank === bankIndex.U && dataWriteMask(byte)
      bank.io.a   := dataWriteAddr
      bank.io.d   := dataWriteData(8 * byte + 7, 8 * byte)
    }
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
