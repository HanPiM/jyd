package simplebus

import chisel3._

object SimpleBusIO {
  class Imp extends Bundle {
    val req_ready  = Input(Bool())
    val req_valid  = Output(Bool())
    val addr       = Output(UInt(32.W))
    val size       = Output(UInt(3.W))
    val wdata      = Output(UInt(32.W))
    val wmask      = Output(UInt(4.W))
    val wen        = Output(Bool())
    val resp_valid = Input(Bool())
    val rdata      = Input(UInt(32.W))
  }

  class MasterT extends Imp {
    def dontCareReq() = {
      req_valid := false.B
      addr      := 0.U
      size      := 0.U
      wdata     := 0.U
      wmask     := 0.U
      wen       := false.B
    }

    def dontCareResp() = {
      // Requester has no response backpressure in v1.
    }
  }

  class SlaveT extends Imp {
    def dontCareReq() = {
      req_ready := false.B
    }

    def dontCareResp() = {
      resp_valid := false.B
      rdata      := 0.U
    }
  }

  def Master = new MasterT
  def Slave  = Flipped(new SlaveT)
}
