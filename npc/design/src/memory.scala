package memory
import chisel3._
import chisel3.util._
import chisel3.util.circt.dpi._

import common_def._

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
class MemUnit   extends Module {
  val io = IO(new MemUnitIO)

  val sRdIdle :: sRdWait :: Nil = Enum(2)
  val rdState                   = RegInit(sRdIdle)
  rdState := MuxCase(
    rdState,
    Seq(
      (rdState === sRdIdle && io.read.en) -> sRdWait,
      (rdState === sRdWait)               -> sRdIdle
    )
  )

  io.read.respValid := (rdState === sRdWait)

  val enRdCall = io.read.en && (rdState === sRdIdle)

  io.read.data := RawClockedNonVoidFunctionCall("pmem_read", Types.UWord)(
    clock,
    enRdCall && (!reset.asBool),
    io.read.addr
  )

  val sWrIdle :: sWrWait :: Nil = Enum(2)
  val WrState                   = RegInit(sWrIdle)
  WrState := MuxLookup(WrState, sWrIdle)(
    Seq(
      sWrIdle -> Mux(io.write.en, sWrWait, sWrIdle),
      sWrWait -> sWrIdle
    )
  )

  io.write.done := (WrState === sWrWait)
  val enWrCall = io.write.en && (WrState === sWrIdle)

  RawClockedVoidFunctionCall("pmem_write")(
    clock,
    enWrCall && (!reset.asBool),
    io.write.addr,
    io.write.data,
    io.write.mask.pad(32)
  )

}

class MemReadFSM extends Module {
  val io = IO(new Bundle {
    val memTX = MemReqIO.ReadTX
    val need  = Input(Bool())
    val addr  = Input(Types.UWord)
    val data  = Output(Types.UWord)
    val valid = Output(Bool())
  })

  val sNoneed :: sWaitResp :: sDone :: Nil = Enum(3)
  val state                                = RegInit(sNoneed)
  state := MuxLookup(state, sNoneed)(
    Seq(
      sNoneed   -> Mux(io.need, sWaitResp, sNoneed),
      sWaitResp -> Mux(io.memTX.respValid, sDone, sWaitResp),
      sDone     -> Mux(io.need, sDone, sNoneed)
    )
  )

  // only send 1 read request
  // when in sNoneed and need
  io.memTX.en := (state === sNoneed) && io.need

  io.memTX.addr := io.addr
  io.data       := io.memTX.data
  io.valid      := (state === sDone)

}

class LoadStoreFSM extends Module {
  val io = IO(new Bundle {
    val memRd     = MemReqIO.ReadTX
    val memWr     = MemReqIO.WriteTX
    val reqValid  = Input(Bool())
    val addr      = Input(Types.UWord)
    val wen       = Input(Bool())
    val wdata     = Input(Types.UWord)
    val wmask     = Input(UInt(4.W))
    val rdata     = Output(Types.UWord)
    val respValid = Output(Bool())
  })

  val sNoneed :: sWaitMem :: sDone :: Nil = Enum(3)
  val state                               = RegInit(sNoneed)
  state := MuxLookup(state, sNoneed)(
    Seq(
      sNoneed  -> Mux(io.reqValid, sWaitMem, sNoneed),
      sWaitMem -> Mux(io.respValid, sDone, sWaitMem),
      sDone    -> Mux(io.reqValid, sDone, sNoneed)
    )
  )
  val isLoad = !io.wen
  val isStore = io.wen

  io.memRd.en   := (state === sNoneed) && io.reqValid && isLoad
  io.memRd.addr := io.addr

  io.memWr.en   := (state === sNoneed) && io.reqValid && isStore
  io.memWr.addr := io.addr
  io.memWr.data := io.wdata
  io.memWr.mask := io.wmask

  io.rdata     := io.memRd.data
  io.respValid := MuxCase(
    false.B,
    Seq(
      isLoad  -> io.memRd.respValid,
      isStore -> io.memWr.done
    )
  ) || (state === sDone)

}
