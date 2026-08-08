package btb

import chisel3._
import chisel3.util._

import common_def._

object BTBParameters {
  val ENTRY_NUM   = 512
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
  // The physical query memory is 32 bits wide.  Keep the bits freed by the
  // wider index explicit instead of changing the target encoding.
  val reserved = UInt(1.W)
  val isReturn = Bool()
  val valid  = Bool()
  val isJAL  = Bool()
  val isBranch = Bool()
  val isBackward = Bool()
  val directionCounter = UInt(2.W)
  val tag    = UInt(BTBParameters.TAG_WIDTH.W)
  val target = new BTBTarget()
}

class BTBUpdateState extends Bundle {
  val valid            = Bool()
  val tag              = UInt(BTBParameters.TAG_WIDTH.W)
  val isBranch         = Bool()
  val directionCounter = UInt(2.W)
}

class BranchTargetBuffer extends Module {
  val io = IO(new Bundle {
    val query  = new Bundle {
      val addr   = Input(Types.UWord)
      val hit    = Output(Bool())
      val target = Output(Types.UWord)
      val isJAL  = Output(Bool())
      val isBranch = Output(Bool())
      val isReturn = Output(Bool())
      val directionTaken = Output(Bool())
      val isBackward = Output(Bool())
    }
    val update = new Bundle {
      val en     = Input(Bool())
      val addr   = Input(Types.UWord)
      val isJAL  = Input(Bool())
      val isBranch = Input(Bool())
      val isReturn = Input(Bool())
      val actualTaken = Input(Bool())
      val target = Input(Types.UWord)
      val isBackward = Input(Bool())
    }
  })

  // Keep the fetch query in two asynchronous LUTRAM banks of 256 entries so
  // the read depth stays one level shallower than a single 512-entry memory.
  // The update port only needs the old tag/type/counter, kept in a matching
  // distributed-memory shadow.
  val numBanks = 2
  val bankAddrWidth = BTBParameters.INDEX_WIDTH - log2Ceil(numBanks)
  val queryMem       = Seq.fill(numBanks)(Mem(1 << bankAddrWidth, new BTBEntry))
  val updateStateMem = Seq.fill(numBanks)(Mem(1 << bankAddrWidth, new BTBUpdateState))

  // Zero every entry during reset-time initialization so no uninitialized
  // LUTRAM bit is ever read as a valid prediction.  Until initDone both
  // memories are being zeroed: force query miss and gate updates.  This
  // removes the wide validMask register and its dynamic shift from both the
  // fetch query and the BTB update paths.
  val initCount = RegInit(0.U(BTBParameters.INDEX_WIDTH.W))
  val initDone  = RegInit(false.B)
  val initPhase = !initDone
  when(initPhase) {
    initCount := initCount + 1.U
    when(initCount === (BTBParameters.ENTRY_NUM - 1).U) {
      initDone := true.B
    }
  }

  // Query logic
  val queryTag   = BTBParameters.extractTag(io.query.addr)
  val queryIndex = BTBParameters.extractIndex(io.query.addr)
  val queryBank  = queryIndex(BTBParameters.INDEX_WIDTH - 1)
  val queryIdx   = queryIndex(bankAddrWidth - 1, 0)
  val queryEntry = Mux(queryBank, queryMem(1)(queryIdx), queryMem(0)(queryIdx))

  io.query.hit    := Mux(initDone, queryEntry.valid && (queryEntry.tag === queryTag), false.B)
  io.query.target := queryEntry.target.get
  io.query.isJAL  := queryEntry.isJAL
  io.query.isBranch := queryEntry.isBranch
  io.query.isReturn := queryEntry.isReturn
  io.query.directionTaken := queryEntry.directionCounter(1)
  io.query.isBackward := queryEntry.isBackward

  // Update logic
  val updateTag      = BTBParameters.extractTag(io.update.addr)
  val updateIndex    = BTBParameters.extractIndex(io.update.addr)
  val updateBank     = updateIndex(BTBParameters.INDEX_WIDTH - 1)
  val updateIdx      = updateIndex(bankAddrWidth - 1, 0)
  val oldUpdateState = Mux(updateBank, updateStateMem(1)(updateIdx), updateStateMem(0)(updateIdx))
  val oldDirection   = oldUpdateState.directionCounter
  val entryMatches = Mux(
    initDone,
    oldUpdateState.valid && oldUpdateState.tag === updateTag && oldUpdateState.isBranch,
    false.B
  )
  val nextDirection = WireDefault(0.U(2.W))

  when(io.update.isBranch) {
    when(!entryMatches) {
      nextDirection := Mux(io.update.actualTaken, 2.U, 1.U)
    }.elsewhen(io.update.actualTaken && oldDirection =/= 3.U) {
      nextDirection := oldDirection + 1.U
    }.elsewhen(!io.update.actualTaken && oldDirection =/= 0.U) {
      nextDirection := oldDirection - 1.U
    }.otherwise {
      nextDirection := oldDirection
    }
  }

  val nextEntry = Wire(new BTBEntry)
  nextEntry.reserved         := 0.U
  nextEntry.isReturn         := io.update.isReturn
  nextEntry.valid            := true.B
  nextEntry.tag              := updateTag
  nextEntry.target           := BTBTarget(io.update.target)
  nextEntry.isJAL            := io.update.isJAL
  nextEntry.isBranch         := io.update.isBranch
  nextEntry.isBackward       := io.update.isBackward
  nextEntry.directionCounter := nextDirection

  val nextUpdateState = Wire(new BTBUpdateState)
  nextUpdateState.valid             := true.B
  nextUpdateState.tag              := updateTag
  nextUpdateState.isBranch         := io.update.isBranch
  nextUpdateState.directionCounter := nextDirection

  // A previously unseen not-taken conditional branch is already predicted
  // correctly without a BTB entry. Do not let it evict a useful target at the
  // same index; once the branch has an entry, keep training it normally.
  val skipConflictingNotTakenBranch =
    io.update.isBranch && !io.update.actualTaken && !entryMatches
  val updateEn =
    io.update.en && initDone && !reset.asBool && !skipConflictingNotTakenBranch

  // Pipeline the whole update write one more cycle so the long
  // address/tag/direction cone ends at registers instead of distributed-RAM
  // write-enable pins.  The registered payload is written on the next edge.
  val updateEnReg        = RegNext(updateEn)
  val updateIndexReg     = RegNext(updateIndex)
  val nextEntryReg       = RegNext(nextEntry)
  val nextUpdateStateReg = RegNext(nextUpdateState)

  // Single write port per memory bank: mux the init/update address, data, and
  // enable so synthesis can still infer distributed RAM instead of registers.
  val writeBank = Mux(initPhase, initCount(BTBParameters.INDEX_WIDTH - 1), updateIndexReg(BTBParameters.INDEX_WIDTH - 1))
  val writeIdx  = Mux(initPhase, initCount(bankAddrWidth - 1, 0), updateIndexReg(bankAddrWidth - 1, 0))
  queryMem.zipWithIndex.foreach { case (mem, bank) =>
    val writeEn = (initPhase || updateEnReg) && writeBank === bank.U
    val writeData = Mux(initPhase, 0.U.asTypeOf(new BTBEntry), nextEntryReg)
    when(writeEn) {
      mem.write(writeIdx, writeData)
    }
  }
  updateStateMem.zipWithIndex.foreach { case (mem, bank) =>
    val writeEn = (initPhase || updateEnReg) && writeBank === bank.U
    val writeData = Mux(initPhase, 0.U.asTypeOf(new BTBUpdateState), nextUpdateStateReg)
    when(writeEn) {
      mem.write(writeIdx, writeData)
    }
  }
}
