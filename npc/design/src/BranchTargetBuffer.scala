package btb

import chisel3._
import chisel3.util._

import common_def._

object BTBParameters {
  val ENTRY_NUM   = 16
  val INDEX_WIDTH = log2Ceil(ENTRY_NUM)
  // 2 for word-alignment
  // 8 for Hi 8 bit "80" since all instructions are in "80xxxxxx"
  // 2b for [012] hi 2 bit zero
  val TAG_WIDTH   = Types.BitWidth.word - INDEX_WIDTH - 2 - 10

  def extractTag(addr: UInt):   UInt = {
    addr(21, 22 - TAG_WIDTH)
  }
  def extractIndex(addr: UInt): UInt = {
    addr(22 - TAG_WIDTH - 1, 2)
  }
}

class BTBTarget extends Bundle {
  val bits = UInt(20.W) // 32 - 2 (word-alignment) - 10
  def get = Cat("h80".U(8.W), 0.U(2.W), bits, 0.U(2.W))
}
object BTBTarget {
  def apply(addr: UInt): BTBTarget = {
    val target = Wire(new BTBTarget)
    target.bits := addr(23, 2)
    target
  }
}

class BTBEntry extends Bundle {
  val valid  = Bool()
  val isJAL  = Bool()
  val isBackward = Bool()
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
      val isBackward = Output(Bool())
    }
    val update = new Bundle {
      val en     = Input(Bool())
      val addr   = Input(Types.UWord)
      val isJAL  = Input(Bool())
      val target = Input(Types.UWord)
      val isBackward = Input(Bool())
    }
  })

  val entries = RegInit(VecInit.fill(BTBParameters.ENTRY_NUM)(0.U.asTypeOf(new BTBEntry)))

  // Query logic
  val queryTag   = BTBParameters.extractTag(io.query.addr)
  val queryIndex = BTBParameters.extractIndex(io.query.addr)
  val queryEntry = entries(queryIndex)

  io.query.hit    := queryEntry.valid && (queryEntry.tag === queryTag)
  io.query.target := queryEntry.target.get
  io.query.isJAL  := queryEntry.isJAL
  io.query.isBackward := queryEntry.isBackward

  // Update logic
  when(io.update.en) {
    val updateTag   = BTBParameters.extractTag(io.update.addr)
    val updateIndex = BTBParameters.extractIndex(io.update.addr)

    entries(updateIndex).valid  := true.B
    entries(updateIndex).tag    := updateTag
    entries(updateIndex).target := BTBTarget(io.update.target)
    entries(updateIndex).isJAL  := io.update.isJAL
    entries(updateIndex).isBackward := io.update.isBackward
  }
}
