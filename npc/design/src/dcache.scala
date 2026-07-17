package cpu

import chisel3._
import chisel3.util.{Cat, RegEnable, log2Ceil}
import common_def.CPUParameters
import jyd.{DCacheDataMem, DCacheSyncTagMem}

class DCache(implicit p: CPUParameters) extends Module {
  require(p.dcacheWords >= 512 && p.dcacheWords <= 8192, "DCache size must be between 512 and 8192 words")
  require((p.dcacheWords & (p.dcacheWords - 1)) == 0, "DCache size must be a power of two")

  private val indexWidth = log2Ceil(p.dcacheWords)
  private val tagWidth   = 16 - indexWidth
  private val entryWidth = tagWidth + 1

  val io = IO(new Bundle {
    // The tag lookup is launched as an IDU instruction enters EXU. The tag
    // BRAM's one-cycle read latency therefore aligns its output with EXU.
    val tagReadAddr = Input(UInt(32.W))
    val tagReadEn   = Input(Bool())
    val lookupHeld  = Input(Bool())
    val lookupAddr  = Output(UInt(32.W))
    val lookupValid = Output(Bool())
    val hit         = Output(Bool())

    // Data remains EXU-addressed so its synchronous output aligns with LSU
    // and with the following cycle's late-load consumer.
    val accessAddr = Input(UInt(32.W))
    val readData   = Output(UInt(32.W))

    val invalidate = Input(Bool())
    val storeUpdate = Input(Bool())
    val storeAddr   = Input(UInt(32.W))
    val storeData   = Input(UInt(32.W))
    val storeMask   = Input(UInt(4.W))
    val update     = Input(Bool())
    val updateAddr = Input(UInt(32.W))
    val updateData = Input(UInt(32.W))
    val updateMask = Input(UInt(4.W))
  })

  val accessIndex = io.accessAddr(indexWidth + 1, 2)
  val dataMem     = Module(new DCacheDataMem(p.dcacheWords, indexWidth))
  dataMem.io.clkb  := clock
  dataMem.io.enb   := true.B
  dataMem.io.addrb := accessIndex
  io.readData      := dataMem.io.doutb

  // A younger EXU store/invalidation wins over an older WBU refill.
  val storeWrite = io.invalidate || io.storeUpdate
  val tagWrite   = storeWrite || io.update
  val writeAddr  = Mux(storeWrite, io.storeAddr, io.updateAddr)
  val writeIndex = writeAddr(indexWidth + 1, 2)
  val writeTag   = writeAddr(17, indexWidth + 2)
  val writeEntry = Mux(io.invalidate, 0.U(entryWidth.W), Cat(writeTag, 1.U(1.W)))

  val tagMem = Module(new DCacheSyncTagMem(p.dcacheWords, indexWidth, entryWidth))
  tagMem.io.clka  := clock
  tagMem.io.ena   := tagWrite
  tagMem.io.wea   := tagWrite
  tagMem.io.addra := writeIndex
  tagMem.io.dina  := writeEntry
  tagMem.io.clkb  := clock
  tagMem.io.enb   := io.tagReadEn
  tagMem.io.addrb := io.tagReadAddr(indexWidth + 1, 2)

  val lookupAddrReg  = RegEnable(io.tagReadAddr, io.tagReadEn)
  val lookupValidReg = RegInit(false.B)
  when(io.tagReadEn) {
    lookupValidReg := true.B
  }
  io.lookupAddr  := lookupAddrReg
  io.lookupValid := lookupValidReg

  // A newly accepted lookup and a held EXU lookup have separate forwarding
  // state. In particular, a tag write while EXU is held must not feed back
  // through IDU fire and the BRAM read-address input.
  val normalCollision = tagWrite && io.tagReadAddr(indexWidth + 1, 2) === writeIndex
  val normalCollisionReg = RegEnable(normalCollision, io.tagReadEn)
  val normalForwardedEntryReg = RegEnable(writeEntry, io.tagReadEn)

  val heldOverrideValid = RegInit(false.B)
  val heldOverrideEntry = Reg(UInt(entryWidth.W))
  when(io.tagReadEn) {
    heldOverrideValid := false.B
  }.elsewhen(io.lookupHeld && tagWrite && io.accessAddr(indexWidth + 1, 2) === writeIndex) {
    heldOverrideValid := true.B
    heldOverrideEntry := writeEntry
  }

  val normalTagEntry = Mux(normalCollisionReg, normalForwardedEntryReg, tagMem.io.doutb)
  val tagEntry       = Mux(heldOverrideValid, heldOverrideEntry, normalTagEntry)
  val lookupTag = lookupAddrReg(17, indexWidth + 2)
  io.hit := lookupValidReg && tagEntry(0) && tagEntry(entryWidth - 1, 1) === lookupTag

  dataMem.io.clka := clock
  val dataWrite = io.storeUpdate || io.update
  dataMem.io.ena   := dataWrite
  dataMem.io.wea   := Mux(io.storeUpdate, io.storeMask, Mux(io.update, io.updateMask, 0.U))
  dataMem.io.addra := Mux(io.storeUpdate, io.storeAddr(indexWidth + 1, 2), io.updateAddr(indexWidth + 1, 2))
  dataMem.io.dina  := Mux(io.storeUpdate, io.storeData, io.updateData)
}
