package cpu

import chisel3._
import chisel3.util.Cat
import jyd.{BlkMemGen2KB, DistMemGen512x8}

class DCache extends Module {
  val io = IO(new Bundle {
    val queryIndex = Input(UInt(10.W))
    val queryTag   = Input(UInt(5.W))
    val hit       = Output(Bool())
    val readData  = Output(UInt(32.W))
    val lateReadData = Output(UInt(32.W))

    val storeUpdate = Input(Bool())
    val storeFull   = Input(Bool())
    val storeAddr   = Input(UInt(32.W))
    val storeData   = Input(UInt(32.W))
    val storeMask   = Input(UInt(4.W))
    val update     = Input(Bool())
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

  val queryBank  = io.queryIndex(9)
  val queryAddr  = io.queryIndex(8, 0)
  val tagEntries = tagMem.map(_.io.dpo)
  val tagEntry   = Mux(queryBank, tagEntries(1), tagEntries(0))

  tagMem.foreach(_.io.dpra := queryAddr)
  io.hit := tagEntry(0) && tagEntry(5, 1) === io.queryTag

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

  lateDataMem.flatten.foreach { bank =>
    bank.io.dpra := queryAddr
  }
  val lateReadData = lateDataMem.map { banks => Cat(banks.reverse.map(_.io.dpo)) }
  io.lateReadData := Mux(queryBank, lateReadData(1), lateReadData(0))

  // A store wins over an older WBU refill/update. Full-word stores allocate a
  // line. A narrow hit preserves it while a narrow miss leaves the queried tag
  // invalid. Every store writes the tag port unconditionally, so the async hit
  // only drives the valid data bit instead of the RAM write enable.
  val updateTagData = Cat(0.U(2.W), io.updateAddr(15, 11), 1.U(1.W))
  val storeTagValid = io.storeFull || io.hit
  val storeTagData  = Cat(0.U(2.W), io.storeAddr(15, 11), storeTagValid)
  val tagWrite      = io.storeUpdate || io.update
  val tagWriteIndex = Mux(io.storeUpdate, io.storeAddr(11, 2), io.updateAddr(11, 2))
  val tagWriteBank  = tagWriteIndex(9)
  val tagWriteAddr  = tagWriteIndex(8, 0)
  val tagWriteData  = Mux(io.storeUpdate, storeTagData, updateTagData)
  tagMem.zipWithIndex.foreach { case (bank, index) =>
    bank.io.clk := clock
    bank.io.we  := tagWrite && tagWriteBank === index.U
    bank.io.a   := tagWriteAddr
    bank.io.d   := tagWriteData
  }

  val dataWrite = io.storeUpdate || io.update
  val dataWriteMask = Mux(io.storeUpdate, io.storeMask, Mux(io.update, io.updateMask, 0.U))
  val dataWriteIndex = Mux(io.storeUpdate, io.storeAddr(11, 2), io.updateAddr(11, 2))
  val dataWriteBank  = dataWriteIndex(9)
  val dataWriteAddr  = dataWriteIndex(8, 0)
  val dataWriteData = Mux(io.storeUpdate, io.storeData, io.updateData)
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
}
