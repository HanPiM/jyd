package cpu

import chisel3._
import chisel3.util.{Cat, Decoupled, DecoupledIO, Enum, Fill, MuxLookup}



import chisel3.experimental.dataview._

import regfile._
import common_def._
import busfsm._

class WriteBackInfo extends Bundle {
  val gpr = GPRegReqIO.TX.Write

  val csr           = CSRegReqIO.TX.Write
  val csr_ecallflag = Bool()

  val nxt_pc = Types.UWord
}


class WBU extends Module {
  val io = IO(new Bundle {
    val in     = Flipped(Decoupled(new WriteBackInfo))
    val gpr      = GPRegReqIO.TX.Write
    val csr      = CSRegReqIO.TX.Write
    val is_ecall = Output(Bool())
    val done     = Output(Bool())
  })

  val wbinfo = io.in.bits
  val valid  = io.in.valid

  io.in.ready := valid

  // printf("(wbu) write back gpr en %b addr %d data 0x%x\n", wbinfo.gpr.en, wbinfo.gpr.addr, wbinfo.gpr.data)
  // printf("(wbu) valid %b\n", valid)

  io.gpr.en   := wbinfo.gpr.en && valid
  io.gpr.addr := wbinfo.gpr.addr
  io.gpr.data := wbinfo.gpr.data

  io.csr.en   := wbinfo.csr.en && valid
  io.csr.addr := wbinfo.csr.addr
  io.csr.data := wbinfo.csr.data
  io.is_ecall := wbinfo.csr_ecallflag && valid

  io.done := valid
}
