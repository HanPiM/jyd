package cpu

import chisel3._
import chisel3.util.Cat
import jyd.{BlkMemGen2KB, DistMemGen512x8}

class DCache extends Module {
  val io = IO(new Bundle {
    val queryAddr = Input(UInt(32.W))
    val hit       = Output(Bool())
    val readData  = Output(UInt(32.W))
    val lateReadData = Output(UInt(32.W))

    val invalidate = Input(Bool())
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

  val queryIndex = io.queryAddr(10, 2)
  val queryTag   = io.queryAddr(17, 11)
  val tagEntry   = tagMem.io.dpo

  tagMem.io.dpra := queryIndex
  io.hit         := tagEntry(0) && tagEntry(7, 1) === queryTag

  dataMem.io.clkb  := clock
  dataMem.io.enb   := true.B
  dataMem.io.addrb := queryIndex
  io.readData      := dataMem.io.doutb

  lateDataMem.foreach { bank =>
    bank.io.dpra := queryIndex
  }
  io.lateReadData := Cat(lateDataMem.reverse.map(_.io.dpo))

  // A younger store invalidation wins over an older WBU refill/update.
  tagMem.io.clk := clock
  val queryTagData = Cat(queryTag, 1.U(1.W))
  val updateTagData = Cat(io.updateAddr(17, 11), 1.U(1.W))
  tagMem.io.we := io.invalidate || io.storeUpdate || io.update
  tagMem.io.a  := Mux(io.invalidate || io.storeUpdate, queryIndex, io.updateAddr(10, 2))
  tagMem.io.d  := Mux(io.invalidate, 0.U(8.W), Mux(io.storeUpdate, queryTagData, updateTagData))

  dataMem.io.clka  := clock
  val dataWrite = io.storeUpdate || io.update
  val dataWriteMask = Mux(io.storeUpdate, io.storeMask, Mux(io.update, io.updateMask, 0.U))
  val dataWriteAddr = Mux(io.storeUpdate, queryIndex, io.updateAddr(10, 2))
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
