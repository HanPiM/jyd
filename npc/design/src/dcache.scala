package cpu

import chisel3._
import chisel3.util.Cat
import jyd.{BlkMemGen2KB, DistMemGen512x8}

class DCache extends Module {
  val io = IO(new Bundle {
    val queryIndex = Input(UInt(10.W))
    val queryTag   = Input(UInt(7.W))
    val hit       = Output(Bool())
    val readData  = Output(UInt(32.W))
    val lateReadData = Output(UInt(32.W))

    val storeUpdate = Input(Bool())
    val storeData   = Input(UInt(32.W))
    val storeMask   = Input(UInt(4.W))
    val update     = Input(Bool())
    val updateAddr = Input(UInt(32.W))
    val updateData = Input(UInt(32.W))
    val updateMask = Input(UInt(4.W))
  })

  // Two 2KB banks indexed by address bits 10:2, selected by address bit 11.
  // The data and tag lookup stay fully asynchronous in the query cycle; the
  // BRAM data output is the usual one-cycle synchronous read.
  val queryBank = io.queryIndex(9)
  val queryIdx  = io.queryIndex(8, 0)

  val dataMem = Seq.fill(2)(Module(new BlkMemGen2KB))
  val tagMem  = Seq.fill(2)(Module(new DistMemGen512x8))
  // The normal LSU/WBU path keeps using the synchronous BRAM.  Four byte-wide
  // distributed memories mirror its writes and provide an asynchronous C0
  // lookup for the narrow late-load path.  EXU captures this value in its
  // registered LSU payload; it is never consumed directly by the C1 adder.
  val lateDataMem = Seq.tabulate(2, 4) { (_, _) => Module(new DistMemGen512x8) }

  val tagEntry   = Mux(queryBank, tagMem(1).io.dpo, tagMem(0).io.dpo)

  io.hit         := tagEntry(0) && tagEntry(7, 1) === io.queryTag

  dataMem.foreach { bank =>
    bank.io.clkb  := clock
    bank.io.enb   := true.B
    bank.io.addrb := queryIdx
  }
  // The BRAM data output is consumed one cycle after the query (in the
  // LSU/WBU capture), while the query index has already moved on to the next
  // instruction.  Register the bank select so the output mux still selects the
  // bank of the instruction that drove the synchronous read address.
  val readBank = RegNext(queryBank)
  io.readData := Mux(readBank, dataMem(1).io.doutb, dataMem(0).io.doutb)

  lateDataMem.foreach { bank =>
    bank.foreach { byteMem =>
      byteMem.io.dpra := queryIdx
    }
  }
  io.lateReadData := Mux(
    queryBank,
    Cat(lateDataMem(1).reverse.map(_.io.dpo)),
    Cat(lateDataMem(0).reverse.map(_.io.dpo))
  )

  // A store wins over an older WBU refill/update. Full-word stores allocate a
  // line. A narrow hit preserves it while a narrow miss leaves the queried tag
  // invalid. Every store writes the tag port unconditionally, so the async hit
  // only drives the valid data bit instead of the RAM write enable.
  val updateTagData = Cat(io.updateAddr(17, 11), 1.U(1.W))
  val storeTagValid = io.storeMask.andR || io.hit
  val storeTagData  = Cat(io.queryTag, storeTagValid)
  val writeEn       = io.storeUpdate || io.update
  val writeMask     = Mux(io.storeUpdate, io.storeMask, Mux(io.update, io.updateMask, 0.U))
  val writeIndex    = Mux(io.storeUpdate, io.queryIndex, io.updateAddr(11, 2))
  val writeBank     = writeIndex(9)
  val writeIdx      = writeIndex(8, 0)
  val writeData     = Mux(io.storeUpdate, io.storeData, io.updateData)

  tagMem.zipWithIndex.foreach { case (tag, bank) =>
    tag.io.clk := clock
    tag.io.dpra := queryIdx
    tag.io.we  := writeEn && writeBank === bank.U(1.W)
    tag.io.a   := writeIdx
    tag.io.d   := Mux(io.storeUpdate, storeTagData, updateTagData)
  }

  dataMem.zipWithIndex.foreach { case (mem, bank) =>
    mem.io.clka  := clock
    mem.io.ena   := writeEn && writeBank === bank.U(1.W)
    mem.io.wea   := writeMask
    mem.io.addra := writeIdx
    mem.io.dina  := writeData
  }

  lateDataMem.zipWithIndex.foreach { case (bank, bankIdx) =>
    bank.zipWithIndex.foreach { case (byteMem, byte) =>
      byteMem.io.clk := clock
      byteMem.io.we  := writeEn && writeBank === bankIdx.U(1.W) && writeMask(byte)
      byteMem.io.a   := writeIdx
      byteMem.io.d   := writeData(8 * byte + 7, 8 * byte)
    }
  }
}
