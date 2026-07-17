package cpu

import chisel3._
import chisel3.util.{Cat, log2Ceil}
import common_def.CPUParameters
import jyd.{DCacheDataMem, DCacheTagMem}

class DCache(implicit p: CPUParameters) extends Module {
  require(p.dcacheWords >= 512 && p.dcacheWords <= 8192, "DCache size must be between 512 and 8192 words")
  require((p.dcacheWords & (p.dcacheWords - 1)) == 0, "DCache size must be a power of two")

  private val indexWidth = log2Ceil(p.dcacheWords)
  private val tagWidth   = 16 - indexWidth
  private val bankedTags = p.dcacheWords == 4096

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

  val dataMem    = Module(new DCacheDataMem(p.dcacheWords, indexWidth))
  val queryIndex = io.queryAddr(indexWidth + 1, 2)

  dataMem.io.clkb  := clock
  dataMem.io.enb   := true.B
  dataMem.io.addrb := queryIndex
  io.readData      := dataMem.io.doutb

  if (bankedTags) {
    // A 4096-word data RAM uses two 2048-entry word-tag banks. The data index
    // is addr[13:2], each bank index is addr[13:3], the tag is addr[17:14],
    // and addr[2] selects the bank. Each entry is Cat(tag, valid).
    val bankWords      = p.dcacheWords / 2
    val bankIndexWidth = indexWidth - 1
    val bankTagWidth   = tagWidth
    val entryWidth     = bankTagWidth + 1
    require(indexWidth == 12 && bankIndexWidth == 11 && bankTagWidth == 4)
    val tagBank0 = Module(new DCacheTagMem(bankWords, bankIndexWidth, entryWidth))
    val tagBank1 = Module(new DCacheTagMem(bankWords, bankIndexWidth, entryWidth))

    val queryTagIndex = io.queryAddr(indexWidth + 1, 3)
    val queryTag      = io.queryAddr(17, indexWidth + 2)
    val queryWord     = io.queryAddr(2)
    tagBank0.io.dpra := queryTagIndex
    tagBank1.io.dpra := queryTagIndex
    val queryEntry = Mux(queryWord, tagBank1.io.dpo, tagBank0.io.dpo)
    io.hit := queryEntry(0) && queryEntry(entryWidth - 1, 1) === queryTag

    // A younger EXU store operation wins over an older WBU refill/update.
    val queryWrite = io.invalidate || io.storeUpdate
    val writeAddr  = Mux(queryWrite, io.queryAddr, io.updateAddr)
    val writeIndex = writeAddr(indexWidth + 1, 3)
    val writeTag   = writeAddr(17, indexWidth + 2)
    val writeWord  = writeAddr(2)
    val nextEntry  = Mux(io.invalidate, 0.U(entryWidth.W), Cat(writeTag, 1.U(1.W)))
    val tagWrite   = io.invalidate || io.storeUpdate || io.update

    for (tagMem <- Seq(tagBank0, tagBank1)) {
      tagMem.io.clk := clock
      tagMem.io.a   := writeIndex
      tagMem.io.d   := nextEntry
    }
    tagBank0.io.we := tagWrite && !writeWord
    tagBank1.io.we := tagWrite && writeWord
  } else {
    val tagMem    = Module(new DCacheTagMem(p.dcacheWords, indexWidth, tagWidth + 1))
    val queryTag  = io.queryAddr(17, indexWidth + 2)
    val tagEntry  = tagMem.io.dpo
    tagMem.io.dpra := queryIndex
    io.hit := tagEntry(0) && tagEntry(tagWidth, 1) === queryTag

    // A younger store invalidation wins over an older WBU refill/update.
    tagMem.io.clk := clock
    val queryTagData  = Cat(queryTag, 1.U(1.W))
    val updateTagData = Cat(io.updateAddr(17, indexWidth + 2), 1.U(1.W))
    tagMem.io.we := io.invalidate || io.storeUpdate || io.update
    tagMem.io.a  := Mux(io.invalidate || io.storeUpdate, queryIndex, io.updateAddr(indexWidth + 1, 2))
    tagMem.io.d  := Mux(io.invalidate, 0.U((tagWidth + 1).W), Mux(io.storeUpdate, queryTagData, updateTagData))
  }

  dataMem.io.clka  := clock
  val dataWrite = io.storeUpdate || io.update
  dataMem.io.ena   := dataWrite
  dataMem.io.wea   := Mux(io.storeUpdate, io.storeMask, Mux(io.update, io.updateMask, 0.U))
  dataMem.io.addra := Mux(io.storeUpdate, queryIndex, io.updateAddr(indexWidth + 1, 2))
  dataMem.io.dina  := Mux(io.storeUpdate, io.storeData, io.updateData)
}
