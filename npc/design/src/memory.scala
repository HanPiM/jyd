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

    val done = Output(Bool())
  }

  def ReadRX  = new _ReadRX
  def ReadTX  = Flipped(ReadRX)
  def WriteRX = new _WriteRX
  def WriteTX = Flipped(WriteRX)
}

class MemUnitIO extends Bundle {
  val read  = MemReqIO.ReadRX
  val write = MemReqIO.WriteRX
}
class MemUnit extends Module {
  val io = IO(new MemUnitIO)

  when(io.read.en){
//    printf("(MemUnit) read enabled addr: 0x%x\n", io.read.addr)
  }
  //printf("(MemUnit) write en: %b addr: 0x%x data: 0x%x mask: 0b%b\n", io.write.en, io.write.addr, io.write.data, io.write.mask)



  val s_rd_idle :: s_rd_wait :: Nil = Enum(2)
  val rd_state = RegInit(s_rd_idle)
  rd_state := MuxCase(rd_state, Seq(
    (rd_state === s_rd_idle && io.read.en) -> s_rd_wait,
    (rd_state === s_rd_wait)                -> s_rd_idle
  ))

  io.read.respValid := (rd_state === s_rd_wait)

  val en_call = io.read.en && (rd_state === s_rd_idle)

  io.read.data := RawClockedNonVoidFunctionCall("pmem_read", Types.UWord)(
    clock,
    en_call&&(!reset.asBool),
    io.read.addr
  )

  val s_wr_idle :: s_wr_wait :: Nil = Enum(2)
  val wr_state = RegInit(s_wr_idle)
  wr_state := MuxLookup(wr_state,s_wr_idle)(Seq(
    s_wr_idle -> Mux(io.write.en, s_wr_wait, s_wr_idle),
    s_wr_wait -> s_wr_idle
  ))

  io.write.done := (wr_state === s_wr_wait)
  val en_write_call = io.write.en && (wr_state === s_wr_idle)

  when(en_write_call){
   // printf("(MemUnit) write enabled addr: 0x%x data: 0x%x mask: 0b%b\n", io.write.addr, io.write.data, io.write.mask)
  }

  RawClockedVoidFunctionCall("pmem_write")(
    clock,
    en_write_call&&(!reset.asBool),
    io.write.addr,
    io.write.data,
    io.write.mask.pad(32)
  )


}
