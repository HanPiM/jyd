package cpu

import chisel3._
import chisel3.util.Cat
import jyd.{BlkMemGen2KB, DistMemGen512x8}

class DCache extends Module {
  val io = IO(new Bundle {
    val queryIndex = Input(UInt(9.W))
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

  val dataMem = Module(new BlkMemGen2KB)
  val tagMem  = Module(new DistMemGen512x8)
  // The normal LSU/WBU path keeps using the synchronous BRAM.  Four byte-wide
  // distributed memories mirror its writes and provide an asynchronous C0
  // lookup for the narrow late-load path.  EXU captures this value in its
  // registered LSU payload; it is never consumed directly by the C1 adder.
  val lateDataMem = Seq.fill(4)(Module(new DistMemGen512x8))

  val tagEntry   = tagMem.io.dpo

  tagMem.io.dpra := io.queryIndex
  io.hit         := tagEntry(0) && tagEntry(7, 1) === io.queryTag

  dataMem.io.clkb  := clock
  dataMem.io.enb   := true.B
  dataMem.io.addrb := io.queryIndex
  io.readData      := dataMem.io.doutb

  lateDataMem.foreach { bank =>
    bank.io.dpra := io.queryIndex
  }
  io.lateReadData := Cat(lateDataMem.reverse.map(_.io.dpo))

  // A store wins over an older WBU refill/update. Full-word stores allocate a
  // line. A narrow hit updates only the known bytes and leaves the matching tag
  // untouched. A narrow miss leaves the whole shadow entry untouched: the
  // write-through backing memory still receives the store, while preserving an
  // unrelated line at the same direct-mapped index is both coherent and avoids
  // feeding the asynchronous tag lookup back into the tag-memory write port.
  tagMem.io.clk := clock
  val updateTagData = Cat(io.updateAddr(17, 11), 1.U(1.W))
  val fullStore      = io.storeUpdate && io.storeMask.andR
  val narrowStoreHit = io.storeUpdate && !io.storeMask.andR && io.hit
  val storeTagData   = Cat(io.queryTag, true.B)
  tagMem.io.we := fullStore || io.update
  tagMem.io.a  := Mux(fullStore, io.queryIndex, io.updateAddr(10, 2))
  tagMem.io.d  := Mux(fullStore, storeTagData, updateTagData)

  dataMem.io.clka  := clock
  val storeShadowWrite = fullStore || narrowStoreHit
  val dataWrite = storeShadowWrite || io.update
  val dataWriteMask = Mux(storeShadowWrite, io.storeMask, Mux(io.update, io.updateMask, 0.U))
  val dataWriteAddr = Mux(storeShadowWrite, io.queryIndex, io.updateAddr(10, 2))
  val dataWriteData = Mux(storeShadowWrite, io.storeData, io.updateData)
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
