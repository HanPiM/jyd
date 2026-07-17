package btb

import chisel3._
import chisel3.util._

import common_def._
import jyd.DistMemGen32x32

object BTBParameters {
  val ENTRY_NUM   = 16
  val INDEX_WIDTH = log2Ceil(ENTRY_NUM)
  val TAG_WIDTH   = 15 - INDEX_WIDTH

  // 2 for word-alignment
  // 8 for Hi 8 bit "80" since all instructions are in "80xxxxxx"
  // 2b for [012] hi 2 bit zero
  //
  // Since BRAM max 256KB
  // 32bit address ->
  //   {8'h80, 2'b00, {2'b region}, 2'b00, {16'b valid bram addr}, 2'b00}
  // 
  // never jump to MMIO region 2'b10 => only need 17bit to store target addr
  //
  // 17bit Target : 
  //   { 1'b IROM/DRAM, 16'b valid bram addr }
  //
  // Tag only 17bit - INDEX_WIDTH
  //

  // New optmization
  // Inst Mem region is 0x8000_0000 - 0x8000_ffff
  //
  // only 64KB, 16bit addr, trim 2b since word-aligned, so only 14bit needed
  //
  // 32bit address ->
  //   {16'h8000, 14'b valid addr, 2'b00}
  //
  // 14bit Target : 14'b valid addr
  // Tag only 14bit - INDEX_WIDTH
  //
  // TODO: for contest use 14bit
  // NOW, for test large program, need 128KB, so 15bit

  def extractTag(addr: UInt):   UInt = {
    addr(16, 2 + INDEX_WIDTH)
  }
  def extractIndex(addr: UInt): UInt = {
    addr(2 + INDEX_WIDTH - 1, 2)
  }
}

class BTBTarget extends Bundle {
  val bits = UInt(15.W)
  def get = Cat("h800".U(12.W),0.U(3.W), bits, 0.U(2.W))
}
object BTBTarget {
  def apply(addr: UInt): BTBTarget = {
    val target = Wire(new BTBTarget)
    target.bits := addr(16, 2)
    target
  }
}

class BTBEntry extends Bundle {
  val valid  = Bool()
  val isJAL  = Bool()
  val isBranch = Bool()
  val isBackward = Bool()
  val directionCounter = UInt(2.W)
  val tag    = UInt(BTBParameters.TAG_WIDTH.W)
  val target = new BTBTarget()
}

class BranchTargetBuffer extends Module {
  val io = IO(new Bundle {
    val query  = new Bundle {
      val addr   = Input(Types.UWord)
      val hit    = Output(Bool())
      val target = Output(Types.UWord)
      val isJAL  = Output(Bool())
      val isBranch = Output(Bool())
      val directionTaken = Output(Bool())
      val isBackward = Output(Bool())
    }
    val update = new Bundle {
      val en     = Input(Bool())
      val addr   = Input(Types.UWord)
      val isJAL  = Input(Bool())
      val isBranch = Input(Bool())
      val actualTaken = Input(Bool())
      val target = Input(Types.UWord)
      val isBackward = Input(Bool())
    }
  })

  require((new BTBEntry).getWidth == 32, "BTB entry must match the 32-bit distributed-memory IP")

  // The memory IP has one asynchronous read port, so keep two coherent
  // replicas: one for the fetch query and one for read-modify-write updates of
  // the direction counter. The separate resettable valid mask prevents
  // uninitialized FPGA memory contents from producing hits after reset.
  val queryMem  = Module(new DistMemGen32x32)
  val updateMem = Module(new DistMemGen32x32)
  val validMask = RegInit(0.U(BTBParameters.ENTRY_NUM.W))

  // Query logic
  val queryTag   = BTBParameters.extractTag(io.query.addr)
  val queryIndex = BTBParameters.extractIndex(io.query.addr)
  queryMem.io.dpra := queryIndex.pad(5)
  val queryEntry = queryMem.io.dpo.asTypeOf(new BTBEntry)
  val queryValid = validMask(queryIndex)

  io.query.hit    := queryValid && queryEntry.valid && (queryEntry.tag === queryTag)
  io.query.target := queryEntry.target.get
  io.query.isJAL  := queryEntry.isJAL
  io.query.isBranch := queryEntry.isBranch
  io.query.directionTaken := queryEntry.directionCounter(1)
  io.query.isBackward := queryEntry.isBackward

  // Update logic
  val updateTag   = BTBParameters.extractTag(io.update.addr)
  val updateIndex = BTBParameters.extractIndex(io.update.addr)
  updateMem.io.dpra := updateIndex.pad(5)
  val oldUpdateEntry = updateMem.io.dpo.asTypeOf(new BTBEntry)
  val oldUpdateValid = validMask(updateIndex)

  val nextEntry = Wire(new BTBEntry)
  nextEntry.valid            := true.B
  nextEntry.tag              := updateTag
  nextEntry.target           := BTBTarget(io.update.target)
  nextEntry.isJAL            := io.update.isJAL
  nextEntry.isBranch         := io.update.isBranch
  nextEntry.isBackward       := io.update.isBackward
  nextEntry.directionCounter := 0.U

  when(io.update.isBranch) {
    val entryMatches = oldUpdateValid && oldUpdateEntry.valid && oldUpdateEntry.tag === updateTag &&
      oldUpdateEntry.isBranch
    when(!entryMatches) {
      nextEntry.directionCounter := Mux(io.update.actualTaken, 2.U, 1.U)
    }.elsewhen(io.update.actualTaken && oldUpdateEntry.directionCounter =/= 3.U) {
      nextEntry.directionCounter := oldUpdateEntry.directionCounter + 1.U
    }.elsewhen(!io.update.actualTaken && oldUpdateEntry.directionCounter =/= 0.U) {
      nextEntry.directionCounter := oldUpdateEntry.directionCounter - 1.U
    }.otherwise {
      nextEntry.directionCounter := oldUpdateEntry.directionCounter
    }
  }

  val updateEn = io.update.en && !reset.asBool
  queryMem.io.a   := updateIndex.pad(5)
  queryMem.io.d   := nextEntry.asUInt
  queryMem.io.clk := clock
  queryMem.io.we  := updateEn
  updateMem.io.a   := updateIndex.pad(5)
  updateMem.io.d   := nextEntry.asUInt
  updateMem.io.clk := clock
  updateMem.io.we  := updateEn

  when(updateEn) {
    validMask := validMask | UIntToOH(updateIndex, BTBParameters.ENTRY_NUM)
  }
}
