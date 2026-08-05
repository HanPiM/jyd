package btb

import chisel3._
import chisel3.util._

import common_def._

object BTBParameters {
  val ENTRY_NUM   = 256
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

  // Keep the fetch query in one asynchronous LUTRAM copy.  The update port
  // only needs the old tag/type/counter, kept in a distributed-memory shadow
  // so expanding the BTB does not add hundreds of resettable flops.  The
  // 256-entry capacity reduces index aliasing in CoreMark's hot loops.
  val queryMem       = Mem(BTBParameters.ENTRY_NUM, new BTBEntry)
  val updateStateMem = Mem(BTBParameters.ENTRY_NUM, new BTBUpdateState)
  val validMask      = RegInit(0.U(BTBParameters.ENTRY_NUM.W))

  // Query logic
  val queryTag   = BTBParameters.extractTag(io.query.addr)
  val queryIndex = BTBParameters.extractIndex(io.query.addr)
  val queryEntry = queryMem(queryIndex)

  io.query.hit    := validMask(queryIndex) && queryEntry.valid && (queryEntry.tag === queryTag)
  io.query.target := queryEntry.target.get
  io.query.isJAL  := queryEntry.isJAL
  io.query.isBranch := queryEntry.isBranch
  io.query.isReturn := queryEntry.isReturn
  io.query.directionTaken := queryEntry.directionCounter(1)
  io.query.isBackward := queryEntry.isBackward

  // Update logic
  val updateTag      = BTBParameters.extractTag(io.update.addr)
  val updateIndex    = BTBParameters.extractIndex(io.update.addr)
  val oldUpdateState = updateStateMem(updateIndex)
  val oldDirection   = oldUpdateState.directionCounter
  val entryMatches   = validMask(updateIndex) && oldUpdateState.tag === updateTag && oldUpdateState.isBranch
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
  nextUpdateState.tag              := updateTag
  nextUpdateState.isBranch         := io.update.isBranch
  nextUpdateState.directionCounter := nextDirection

  // A previously unseen not-taken conditional branch is already predicted
  // correctly without a BTB entry. Do not let it evict a useful target at the
  // same index; once the branch has an entry, keep training it normally.
  val skipConflictingNotTakenBranch =
    io.update.isBranch && !io.update.actualTaken && !entryMatches
  val updateEn =
    io.update.en && !reset.asBool && !skipConflictingNotTakenBranch

  when(updateEn) {
    queryMem.write(updateIndex, nextEntry)
    validMask := validMask | UIntToOH(updateIndex, BTBParameters.ENTRY_NUM)
    updateStateMem.write(updateIndex, nextUpdateState)
  }
}
