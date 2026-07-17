package cpu

import chisel3._
import chisel3.util.Cat
import jyd.{BlkMemGen2KB, DistMemGen512x8}

class DCache extends Module {
  val io = IO(new Bundle {
    val queryAddr = Input(UInt(32.W))
    val hit       = Output(Bool())
    val readData  = Output(UInt(32.W))

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

  val queryIndex = io.queryAddr(10, 2)
  val queryTag   = io.queryAddr(17, 11)
  val tagEntry   = tagMem.io.dpo

  tagMem.io.dpra := queryIndex
  io.hit         := tagEntry(0) && tagEntry(7, 1) === queryTag

  dataMem.io.clkb  := clock
  dataMem.io.enb   := true.B
  dataMem.io.addrb := queryIndex
  io.readData      := dataMem.io.doutb

  // A younger store invalidation wins over an older WBU refill/update.
  tagMem.io.clk := clock
  val queryTagData = Cat(queryTag, 1.U(1.W))
  val updateTagData = Cat(io.updateAddr(17, 11), 1.U(1.W))
  tagMem.io.we := io.invalidate || io.storeUpdate || io.update
  tagMem.io.a  := Mux(io.invalidate || io.storeUpdate, queryIndex, io.updateAddr(10, 2))
  tagMem.io.d  := Mux(io.invalidate, 0.U(8.W), Mux(io.storeUpdate, queryTagData, updateTagData))

  dataMem.io.clka  := clock
  val dataWrite = io.storeUpdate || io.update
  dataMem.io.ena   := dataWrite
  dataMem.io.wea   := Mux(io.storeUpdate, io.storeMask, Mux(io.update, io.updateMask, 0.U))
  dataMem.io.addra := Mux(io.storeUpdate, queryIndex, io.updateAddr(10, 2))
  dataMem.io.dina  := Mux(io.storeUpdate, io.storeData, io.updateData)
}
