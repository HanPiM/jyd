package npcMem
import chisel3._
import chisel3.util._

// see https://www.chisel-lang.org/api/latest/chisel3/util/circt/dpi/index.html
// chisel has native dpi interface since
//  https://github.com/chipsalliance/chisel/pull/4158
import chisel3.util.circt.dpi._

import common_def._

import axi4._

class AXI4MemUnit extends Module {
  val io = IO(AXI4IO.Slave)

  val sio = io

  // AR

  val rdAddr = Reg(Types.UWord)

  when(sio.arvalid && sio.arready) {
    rdAddr := sio.araddr
  }

  val sARIdle :: sARWait :: Nil = Enum(2)
  val arState                   = RegInit(sARIdle)
  sio.arready := (arState === sARIdle) && (!reset.asBool)

  arState := MuxLookup(arState, sARIdle)(
    Seq(
      sARIdle -> Mux(sio.arvalid, sARWait, sARIdle),
      sARWait -> Mux(sio.rvalid, sARIdle, sARWait)
    )
  )

  // R

  val rdData = Reg(Types.UWord)

  val sRIdle :: sRWaitMem :: sRWaitRdy :: Nil = Enum(3)
  val rState                                  = RegInit(sRIdle)
  sio.rvalid := (rState === sRWaitRdy)
  sio.rresp  := AXI4IO.RResp.OKAY
  sio.rdata  := rdData

  sio.rlast  := true.B
  sio.rid    := 0.U

  val memReadFinished = Wire(Bool())
  val memReadPrepared = (arState === sARWait)

  rState := MuxLookup(rState, sRIdle)(
    Seq(
      sRIdle    -> Mux(memReadPrepared, sRWaitMem, sRIdle),
      sRWaitMem -> Mux(memReadFinished, sRWaitRdy, sRWaitMem),
      sRWaitRdy -> Mux(sio.rready, sRIdle, sRWaitRdy)
    )
  )

  when(rState === sRWaitMem) {
    rdData := RawClockedNonVoidFunctionCall("pmem_read", Types.UWord)(
      clock,
      (!memReadFinished) && (!reset.asBool),
      rdAddr
    )
  }

  // for now mem read always finish in one cycle
  memReadFinished := RegNext(rState === sRWaitMem)

  // AW

  val wrAddr = Reg(Types.UWord)

  when(sio.awvalid && sio.awready) {
    wrAddr := sio.awaddr
  }

  val sAWIdle :: sAWWait :: Nil = Enum(2)
  val awState                   = RegInit(sAWIdle)
  sio.awready := (awState === sAWIdle)

  awState := MuxLookup(awState, sAWIdle)(
    Seq(
      sAWIdle -> Mux(sio.awvalid, sAWWait, sAWIdle),
      sAWWait -> Mux(sio.bvalid, sAWIdle, sAWWait)
    )
  )

  // W

  val wrData = Reg(Types.UWord)
  val wrMask = Reg(UInt(4.W))
  when(sio.wvalid && sio.wready) {
    wrData := sio.wdata
    wrMask := sio.wstrb
  }

  val sWIdle :: sWWait :: Nil = Enum(2)
  val wState                  = RegInit(sWIdle)
  sio.wready := (wState === sWIdle)
  wState     := MuxLookup(wState, sWIdle)(
    Seq(
      sWIdle -> Mux(sio.wvalid, sWWait, sWIdle),
      sWWait -> Mux(sio.bvalid, sWIdle, sWWait)
    )
  )

  // B

  val sBIdle :: sBWaitAddrOrData :: sBWaitMem :: sBWaitRdy :: Nil = Enum(4)

  val bState = RegInit(sBIdle)
  sio.bvalid := (bState === sBWaitRdy)
  sio.bresp  := AXI4IO.BResp.OKAY

  sio.bid    := 0.U

  val memWriteFinished = Wire(Bool())
  val memWritePrepared = (awState === sAWWait) && (wState === sWWait)

  bState := MuxLookup(bState, sBIdle)(
    Seq(
      sBIdle           -> Mux(sio.awvalid || sio.wvalid, sBWaitAddrOrData, sBIdle),
      sBWaitAddrOrData -> Mux(memWritePrepared, sBWaitMem, sBWaitAddrOrData),
      sBWaitMem        -> Mux(memWriteFinished, sBWaitRdy, sBWaitMem),
      sBWaitRdy        -> Mux(sio.bready, sBIdle, sBWaitRdy)
    )
  )

  when(bState === sBWaitMem) {
    RawClockedVoidFunctionCall("pmem_write")(
      clock,
      (!reset.asBool),
      wrAddr,
      wrData,
      wrMask.pad(32)
    )
  }
  // for now mem write always finish in one cycle
  memWriteFinished := RegNext(bState === sBWaitMem)

}
