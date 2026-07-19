package cpu

import chisel3._
import chisel3.util.Cat
import jyd.{BlkMemGen2KB, DistMemGen512x8}

class DCache extends Module {
  val io = IO(new Bundle {
    val dataQueryIndex = Input(UInt(9.W))
    val tagQueryIndex  = Input(UInt(9.W))
    val queryTag       = Input(UInt(7.W))
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

  val dataMem = Module(new BlkMemGen2KB)
  val tagMem  = Module(new DistMemGen512x8)
  // The normal LSU/WBU path keeps using the synchronous BRAM.  Four byte-wide
  // distributed memories mirror its writes and provide an asynchronous C0
  // lookup for the narrow late-load path.  EXU captures this value in its
  // registered LSU payload; it is never consumed directly by the C1 adder.
  val lateDataMem = Seq.fill(4)(Module(new DistMemGen512x8))

  val tagEntry   = tagMem.io.dpo

  tagMem.io.dpra := io.tagQueryIndex
  io.hit         := tagEntry(0) && tagEntry(7, 1) === io.queryTag

  dataMem.io.clkb  := clock
  dataMem.io.enb   := true.B
  dataMem.io.addrb := io.dataQueryIndex
  io.readData      := dataMem.io.doutb

  lateDataMem.foreach { bank =>
    bank.io.dpra := io.dataQueryIndex
  }
  io.lateReadData := Cat(lateDataMem.reverse.map(_.io.dpo))

  // A store wins over an older WBU refill/update. Full-word stores allocate a
  // line. A narrow hit preserves it while a narrow miss leaves the queried tag
  // invalid. Every store writes the tag port unconditionally, so the async hit
  // only drives the valid data bit instead of the RAM write enable.
  tagMem.io.clk := clock
  val updateTagData = Cat(io.updateAddr(17, 11), 1.U(1.W))
  val storeTagValid = io.storeMask.andR || io.hit
  val storeTagData  = Cat(io.queryTag, storeTagValid)
  tagMem.io.we := io.storeUpdate || io.update
  tagMem.io.a  := Mux(io.storeUpdate, io.tagQueryIndex, io.updateAddr(10, 2))
  tagMem.io.d  := Mux(io.storeUpdate, storeTagData, updateTagData)

  dataMem.io.clka  := clock
  val dataWrite = io.storeUpdate || io.update
  val dataWriteMask = Mux(io.storeUpdate, io.storeMask, Mux(io.update, io.updateMask, 0.U))
  val dataWriteAddr = Mux(io.storeUpdate, io.dataQueryIndex, io.updateAddr(10, 2))
  val dataWriteData = Mux(io.storeUpdate, io.storeData, io.updateData)
  dataMem.io.ena   := dataWrite
  dataMem.io.wea   := dataWriteMask
  dataMem.io.addra := dataWriteAddr
  dataMem.io.dina  := dataWriteData

  lateDataMem.zipWithIndex.foreach { case (bank, byte) =>
    bank.io.clk := clock
    bank.io.we  := dataWrite && dataWriteMask(byte)
    bank.io.a   := dataWriteAddr
    bank.io.d   := dataWriteData(8 * byte + 7, 8 * byte)
  }
}
