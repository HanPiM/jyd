package memory
import chisel3._
import chisel3.util._
import chisel3.util.circt.dpi._

import cpu.Types

object MemReqIO {

  // Mem always return 4 bytes at addr & ~3.U
  class _ReadRX extends Bundle {
    val addr = Input(Types.UWord)
    val data = Output(Types.UWord)
    val en   = Input(Bool())

    val respValid = Output(Bool())
  }

  // Mem always write begin at addr & ~3.U 4 bytes
  // Mask bits indicate which byte to write
  class _WriteRX extends Bundle {
    val addr = Input(Types.UWord)
    val data = Input(Types.UWord)
    val mask = Input(UInt(4.W))
    val en   = Input(Bool())


  }

  def ReadRX  = new _ReadRX
  def ReadTX  = Flipped(ReadRX)
  def WriteRX = new _WriteRX
  def WriteTX = Flipped(WriteRX)
}

class MemUnit extends Module {
  val io = IO(new Bundle {
    val read  = MemReqIO.ReadRX
    val write = MemReqIO.WriteRX
  })

  io.read.data := RawClockedNonVoidFunctionCall("pmem_read", Types.UWord)(
    clock,
    io.read.en,
    io.read.addr
  )

  io.read.respValid := io.read.en

  RawClockedVoidFunctionCall("pmem_write")(
    clock,
    io.write.en,
    io.write.addr,
    io.write.data,
    io.write.mask.pad(32)
  )

}
