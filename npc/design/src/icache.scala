package icache

import chisel3._
import chisel3.util._

import common_def._
import axi4._

class ICacheIO extends Bundle {
  val cpu = AXI4IO.Slave
  val mem = AXI4IO.Master
}

object ICacheParameters {
  val BLOCK_SIZE         = 8
  val BLOCK_NUM          = 16
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
    val idle, checkCache, sendFetch, waitMem = Value
  }
  val state = RegInit(State.idle)

  val rdAddr = Reg(Types.UWord)

  when(io.cpu.arvalid && io.cpu.arready) {
    rdAddr := io.cpu.araddr
  }

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

  io.cpu.rvalid := (state === State.waitMem && io.mem.rlast) || (state === State.checkCache && cacheHit)
  io.cpu.rresp  := AXI4IO.RResp.OKAY
  // TODO: support burst read
  io.cpu.rid    := io.mem.rid
  io.cpu.rlast  := true.B

  // 2^5 = 32
  val dataShift = (ICacheParameters.extractWordOffset(rdAddr) << 5)

  // when not hit, since rvaild at the end of waitMem, the data is from nxtCacheData
  val rdData      = Mux(io.mem.rvalid || (!cacheHit), nxtCacheData, rdCacheBlock.data)
  dontTouch(rdData)
  val shiftedData = rdData >> dataShift
  dontTouch(shiftedData)
  io.cpu.rdata := shiftedData(31, 0)

  state := MuxLookup(state, State.idle)(
    Seq(
      State.idle       -> Mux(io.cpu.arvalid && io.cpu.arready, State.checkCache, State.idle),
      State.checkCache -> Mux(cacheHit, State.idle, State.sendFetch),
      State.sendFetch  -> Mux(io.mem.arready, State.waitMem, State.sendFetch),
      State.waitMem    -> Mux(io.mem.rvalid && io.mem.rlast, State.idle, State.waitMem)
    )
  )

}
