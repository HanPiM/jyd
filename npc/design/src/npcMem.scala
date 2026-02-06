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

  val rdAddrBegReg = Reg(Types.UWord)
  val rdAddrBeg    = Wire(Types.UWord)
  val rdAddr       = Wire(Types.UWord)
  dontTouch(rdAddr)
  dontTouch(rdAddrBeg)

  when(sio.arvalid && sio.arready) {
    rdAddrBeg := sio.araddr
  }

  val sARIdle :: sARWait :: Nil = Enum(2)
  val arState                   = RegInit(sARIdle)
  sio.arready := (arState === sARIdle) && (!reset.asBool)

  arState   := MuxLookup(arState, sARIdle)(
    Seq(
      sARIdle -> Mux(sio.arvalid, sARWait, sARIdle),
      sARWait -> Mux(sio.rvalid && sio.rlast, sARIdle, sARWait)
    )
  )
  rdAddrBegReg := Mux(sio.arvalid && sio.arready, sio.araddr, rdAddrBegReg)
  rdAddrBeg := Mux(arState === sARIdle, sio.araddr, rdAddrBegReg)

  val arLen        = Reg(UInt(8.W))
  val curReadCount = Wire(UInt(8.W))
  dontTouch(curReadCount)

  when(sio.arvalid && sio.arready) {
    arLen := sio.arlen
  }
  when(arLen =/= 0.U) {
    assert(sio.arsize === AXI4IO.SizeType.WORD, "Only support word size read now")
    assert(sio.arburst === AXI4IO.BurstType.INCR, "Only support INCR burst type now")
  }
  rdAddr := rdAddrBeg + (curReadCount << 2)

  // R
  // support burst read

  object RState extends ChiselEnum {
    val idle, waitMem, sendData = Value
  }
  val rState = RegInit(RState.idle)

  val rdFIFO = Module(new Queue(Types.UWord, 16))
  curReadCount := rdFIFO.io.count

  sio.rvalid          := (rState === RState.sendData) && rdFIFO.io.deq.valid
  rdFIFO.io.deq.ready := sio.rready && sio.rvalid
  sio.rdata           := rdFIFO.io.deq.bits
  sio.rresp           := AXI4IO.RResp.OKAY
  sio.rid             := 0.U
  sio.rlast           := (curReadCount === 1.U)

  rState := MuxLookup(rState, RState.idle)(
    Seq(
      RState.idle     -> Mux(sio.arvalid, RState.waitMem, RState.idle),
      RState.waitMem  -> Mux(curReadCount === arLen, RState.sendData, RState.waitMem),
      RState.sendData -> Mux(curReadCount === 0.U, Mux(sio.arvalid,RState.waitMem,RState.idle), RState.sendData)
    )
  )

  val enRdDataCall = WireDefault((rState === RState.waitMem) || (rState === RState.idle && sio.arvalid))
  dontTouch(enRdDataCall)
  when(rState === RState.waitMem) {
    val rdData = RawUnclockedNonVoidFunctionCall("pmem_read", UInt(32.W))(
      (!reset.asBool) && enRdDataCall,
      rdAddr
    )
    rdFIFO.io.enq.bits  := rdData
    rdFIFO.io.enq.valid := true.B
  }.otherwise {
    rdFIFO.io.enq.bits  := 0.U
    rdFIFO.io.enq.valid := false.B
  }

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

  sio.bid := 0.U

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
