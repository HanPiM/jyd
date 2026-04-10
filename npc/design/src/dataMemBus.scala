package cpu

import chisel3._
import chisel3.util._

import common_def._
import simplebus._

class MemReq extends Bundle {
  val addr  = Types.UWord
  val size  = UInt(3.W)
  val wen   = Bool()
  val wdata = Types.UWord
  val wmask = UInt(4.W)
}

class DataMemBusCombiner extends Module {
  val io = IO(new Bundle {
    val exuMemReq = Flipped(Decoupled(new MemReq))
    val lsuResp   = Valid(Types.UWord)
    val out       = SimpleBusIO.Master
  })

  io.out.dontCareReq()

  io.out.req_valid := io.exuMemReq.valid
  io.out.addr      := io.exuMemReq.bits.addr
  io.out.size      := io.exuMemReq.bits.size
  io.out.wdata     := io.exuMemReq.bits.wdata
  io.out.wmask     := io.exuMemReq.bits.wmask
  io.out.wen       := io.exuMemReq.bits.wen

  io.exuMemReq.ready := io.out.req_ready

  io.lsuResp.valid := io.out.resp_valid
  io.lsuResp.bits  := io.out.rdata
}
