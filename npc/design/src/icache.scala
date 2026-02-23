package icache

import chisel3._
import chisel3.util._

import common_def._
import axi4._

import xbar._
import cpu.EXUIFU_MemVisitArbiter

class ICacheIO extends Bundle {
  val cpu = AXI4IO.Slave
  val mem = AXI4IO.Master

  val flush = Input(Bool())
}

object ICacheParameters {
  val BLOCK_SIZE         = 16
  val BLOCK_NUM          = 4
  val BLOCK_SIZE_INBITS  = BLOCK_SIZE * 8
  val BLOCK_SIZE_INWORDS = BLOCK_SIZE / 4

  val ARLEN = BLOCK_SIZE_INWORDS - 1

  require(BLOCK_SIZE % 4 == 0)
  require(isPow2(BLOCK_SIZE))
  require(isPow2(BLOCK_NUM))

  val BLOCK_INDEX_WIDTH = log2Ceil(BLOCK_NUM)
  val BLOCK_TAG_WIDTH   = 32 - log2Ceil(BLOCK_SIZE) - BLOCK_INDEX_WIDTH

  def extractTag(addr: UInt):        UInt = {
    addr(31, 32 - BLOCK_TAG_WIDTH)
  }
  def extractIndex(addr: UInt):      UInt = {
    addr(32 - BLOCK_TAG_WIDTH - 1, 32 - BLOCK_TAG_WIDTH - BLOCK_INDEX_WIDTH)
  }
  def extractOffset(addr: UInt):     UInt = {
    addr(log2Ceil(BLOCK_SIZE) - 1, 0)
  }
  def extractWordOffset(addr: UInt): UInt = {
    if (BLOCK_SIZE_INWORDS == 1) 0.U
    else addr(log2Ceil(BLOCK_SIZE) - 1, 2)
  }
  def alignToBlock(addr: UInt):      UInt = {
    addr(31, log2Ceil(BLOCK_SIZE)) ## 0.U(log2Ceil(BLOCK_SIZE).W)
  }
}

class ICacheBlock extends Bundle {
  val valid = Bool()
  val tag   = UInt(ICacheParameters.BLOCK_TAG_WIDTH.W)
  val data  = UInt(ICacheParameters.BLOCK_SIZE_INBITS.W)

  // Assume index and offset are correct
  def matchAddr(addr: UInt): Bool = {
    tag === ICacheParameters.extractTag(addr) && valid
  }
}

class ICache extends Module {
  val io = IO(new ICacheIO)

  io.cpu.dontCareAW()
  io.cpu.dontCareW()
  io.cpu.dontCareB()

  io.mem.dontCareAW()
  io.mem.dontCareW()
  io.mem.dontCareB()

  val blocks = RegInit(VecInit(Seq.fill(ICacheParameters.BLOCK_NUM)(0.U.asTypeOf(new ICacheBlock))))

  object State extends ChiselEnum {
    val idle, sendFetch, waitMem = Value
  }
  val state = RegInit(State.idle)

  val rdAddrReg = Reg(Types.UWord)

  when(io.cpu.arvalid && io.cpu.arready) {
    rdAddrReg := io.cpu.araddr
  }
  val rdAddr = Mux(io.cpu.arvalid && io.cpu.arready, io.cpu.araddr, rdAddrReg)

  val rdIdx        = ICacheParameters.extractIndex(rdAddr)
  val rdCacheBlock = blocks(rdIdx)
  val cacheHit     = rdCacheBlock.matchAddr(rdAddr)

  val dbgRdCacheBlock = WireDefault(rdCacheBlock)
  dontTouch(dbgRdCacheBlock)
  dontTouch(rdIdx)
  dontTouch(cacheHit)

  io.cpu.arready := (state === State.idle) && (!reset.asBool)
  io.mem.arvalid := (state === State.sendFetch)
  // AXI4IO.noShakeConnectAR(io.cpu, io.mem)

  io.mem.arid    := io.cpu.arid
  io.mem.araddr  := ICacheParameters.alignToBlock(rdAddr)
  io.mem.arlen   := ICacheParameters.ARLEN.U
  io.mem.arsize  := AXI4IO.SizeType.WORD
  io.mem.arburst := AXI4IO.BurstType.INCR

  io.mem.rready := (state === State.waitMem)

  val nxtCacheData = Wire(UInt(ICacheParameters.BLOCK_SIZE_INBITS.W))
  dontTouch(nxtCacheData)

  nxtCacheData := {
    if (ICacheParameters.BLOCK_SIZE_INBITS > 32)
      Cat(io.mem.rdata, rdCacheBlock.data(ICacheParameters.BLOCK_SIZE_INBITS - 1, 32))
    else
      io.mem.rdata
  }

  when(state === State.waitMem && io.mem.rvalid) {
    // fill cache
    rdCacheBlock.valid := true.B
    rdCacheBlock.tag   := ICacheParameters.extractTag(rdAddr)
    rdCacheBlock.data  := nxtCacheData
  }
  when(io.flush) {
    for (i <- 0 until ICacheParameters.BLOCK_NUM) {
      blocks(i).valid := false.B
    }
  }

  val rdCnt = RegInit(0.U(log2Ceil(ICacheParameters.BLOCK_SIZE_INWORDS).W))
  when(state === State.waitMem && io.mem.rvalid) {
    rdCnt := rdCnt + 1.U
  }.otherwise {
    rdCnt := 0.U
  }

  io.cpu.rvalid := (state === State.waitMem && io.mem.rlast && io.mem.rvalid) || (state === State.idle && cacheHit && io.cpu.arvalid && io.cpu.arready)
  val wordOffset = ICacheParameters.extractWordOffset(rdAddr)
  // dontTouch(wordOffset)
  // val retShiftedData = cacheHit && state === State.idle && io.cpu.arvalid && io.cpu.arready
  // io.cpu.rvalid := (retShiftedData || (state === State.waitMem && rdCnt === wordOffset && io.mem.rvalid))

  io.cpu.rresp := AXI4IO.RResp.OKAY
  // TODO: support burst read
  io.cpu.rid   := io.mem.rid
  io.cpu.rlast := true.B

  // 2^5 = 32
  val dataShift = (wordOffset << 5)

  // when not hit, since rvaild at the end of waitMem, the data is from nxtCacheData
  val rdData      = Mux(io.mem.rvalid || (!cacheHit), nxtCacheData, rdCacheBlock.data)
  dontTouch(rdData)
  val shiftedData = rdData >> dataShift
  dontTouch(shiftedData)
  // io.cpu.rdata := Mux(retShiftedData, shiftedData(31, 0), io.mem.rdata)
  io.cpu.rdata := shiftedData(31, 0)

  state := MuxLookup(state, State.idle)(
    Seq(
      State.idle      -> Mux(io.cpu.arvalid && io.cpu.arready && (!cacheHit), State.sendFetch, State.idle),
      State.sendFetch -> Mux(io.mem.arready, State.waitMem, State.sendFetch),
      State.waitMem   -> Mux(io.mem.rvalid && io.mem.rlast, State.idle, State.waitMem)
    )
  )

}

class ICacheWithDirectVisit extends Module {
  val io = IO(new ICacheIO)

  val cache = Module(new ICache)
  cache.io.flush := io.flush

  val directWire         = Wire(AXI4IO.Slave)

  val xbar = Module(
    new AXI4LiteXBar(
      Seq(
        AddrSpace.SRAM -> directWire,
        AddrSpace.SOC_ExceptSRAM  -> cache.io.cpu
      )
    )
  )

  xbar.io.in <> io.cpu
  xbar.connect()

  val memArbiter = Module(new EXUIFU_MemVisitArbiter)
  memArbiter.io.ifu <> directWire
  memArbiter.io.exu <> cache.io.mem

  io.mem <> memArbiter.io.out


  // val isDirectVisitAddr = AddrSpace.inRng(io.cpu.araddr, AddrSpace.SRAM)
  //
  // object State extends ChiselEnum {
  //   val idle, connCache, connMem = Value
  // }
  //
  // val state = RegInit(State.idle)
  //
  // state := MuxLookup(state, State.idle)(
  //   Seq(
  //     State.idle      -> Mux(io.cpu.arvalid, Mux(isDirectVisitAddr, State.connMem, State.connCache), State.idle),
  //     State.connCache -> Mux(io.cpu.rvalid && io.cpu.rlast, State.idle, State.connCache),
  //     State.connMem   -> Mux(io.cpu.rvalid && io.cpu.rlast, State.idle, State.connMem)
  //   )
  // )
  //
  // val isDirectVisit = state === State.connMem
  // val isCacheVisit  = state === State.connCache
  //
  // val cache = Module(new ICache)
  // cache.io.flush := io.flush
  //
  // AXI4IO.noShakeConnectAR(io.cpu, cache.io.cpu)
  // AXI4IO.noShakeConnectAR(io.cpu, io.mem)
  //
  // io.cpu.dontCareAW()
  // io.cpu.dontCareW()
  // io.cpu.dontCareB()
  //
  // io.mem.dontCareAW()
  // io.mem.dontCareW()
  // io.mem.dontCareB()
  //
  // cache.io.mem.wready  := DontCare
  // cache.io.mem.bready  := DontCare
  // cache.io.mem.bvalid  := DontCare
  // cache.io.mem.bresp   := DontCare
  // cache.io.mem.bid     := DontCare
  // cache.io.mem.awready := DontCare
  //
  // cache.io.cpu.awvalid := DontCare
  // cache.io.cpu.wvalid  := DontCare
  // cache.io.cpu.wlast   := DontCare
  // cache.io.cpu.wdata   := DontCare
  // cache.io.cpu.wstrb   := DontCare
  // cache.io.cpu.bready  := DontCare
  // cache.io.cpu.awaddr  := DontCare
  // cache.io.cpu.awid    := DontCare
  // cache.io.cpu.awlen   := DontCare
  // cache.io.cpu.awsize  := DontCare
  // cache.io.cpu.awburst := DontCare
  //
  //
  // io.cpu.arready := Mux(isDirectVisit, io.mem.arready, cache.io.cpu.arready)
  // io.mem.arvalid := Mux(isDirectVisit, io.cpu.arvalid, cache.io.mem.arvalid)
  //
  // io.cpu.rvalid := Mux(isDirectVisit, io.mem.rvalid, cache.io.cpu.rvalid)
  // io.cpu.rresp  := Mux(isDirectVisit, io.mem.rresp, cache.io.cpu.rresp)
  // io.cpu.rid    := Mux(isDirectVisit, io.mem.rid, cache.io.cpu.rid)
  // io.cpu.rdata  := Mux(isDirectVisit, io.mem.rdata, cache.io.cpu.rdata)
  // io.cpu.rlast  := Mux(isDirectVisit, io.mem.rlast, cache.io.cpu.rlast)
  //
  // io.mem.rready := Mux(isDirectVisit, io.cpu.rready, cache.io.mem.rready)
  // cache.io.cpu.rready := isCacheVisit && io.cpu.rready
  //
  // cache.io.cpu.arvalid := io.cpu.arvalid && isCacheVisit
  //
  // cache.io.mem.rvalid := io.mem.rvalid && isCacheVisit
  // cache.io.mem.arready := io.mem.arready && isCacheVisit
  // AXI4IO.noShakeConnectR(io.mem, cache.io.mem)
}
