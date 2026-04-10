package cpu

import chisel3._
import chisel3.util._

import common_def._
import simplebus._

class MemReadReq extends Bundle {
  val addr = Types.UWord
  val size = UInt(3.W)
}

class MemWriteReq extends Bundle {
  val addr  = Types.UWord
  val size  = UInt(3.W)
  val wdata = Types.UWord
  val wmask = UInt(4.W)
}

class DataMemBusCombiner extends Module {
  val io = IO(new Bundle {
    val exuLoadReq  = Flipped(Decoupled(new MemReadReq))
    val lsuStoreReq = Flipped(Decoupled(new MemWriteReq))
    val lsuResp     = Valid(Types.UWord)
    val out         = SimpleBusIO.Master
  })

  io.out.dontCareReq()

  val takeStore = io.lsuStoreReq.valid

  io.out.req_valid := io.exuLoadReq.valid || io.lsuStoreReq.valid
  io.out.addr      := Mux(takeStore, io.lsuStoreReq.bits.addr, io.exuLoadReq.bits.addr)
  io.out.size      := Mux(takeStore, io.lsuStoreReq.bits.size, io.exuLoadReq.bits.size)
  io.out.wdata     := Mux(takeStore, io.lsuStoreReq.bits.wdata, 0.U)
  io.out.wmask     := Mux(takeStore, io.lsuStoreReq.bits.wmask, 0.U)
  io.out.wen       := takeStore

  io.lsuStoreReq.ready := io.out.req_ready && takeStore
  io.exuLoadReq.ready  := io.out.req_ready && !takeStore && io.exuLoadReq.valid

  io.lsuResp.valid := io.out.resp_valid
  io.lsuResp.bits  := io.out.rdata

  when(io.exuLoadReq.valid && io.lsuStoreReq.valid) {
    assert(false.B, "EXU load request and LSU store request must not overlap")
  }
}
