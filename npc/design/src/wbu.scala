package cpu

import chisel3._
import chisel3.util.{Cat, Decoupled, DecoupledIO, Enum, Fill, MuxLookup}

import chisel3.experimental.dataview._

import regfile._
import common_def._
import dpiwrap._
import busfsm._

import chisel3.util.circt.dpi._

class WriteBackInfo extends Bundle {
  val gpr = GPRegReqIO.TX.Write

  val csr           = CSRegReqIO.TX.Write
  val csr_ecallflag = Bool()

  val is_ebreak = Bool()

  val skipDifftest = Bool()

  val pc     = Types.UWord
  val nxt_pc = Types.UWord

  val iid = Types.InstID
}

object ExtractFwdInfoFromWrBack {
  def apply(info: DecoupledIO[WriteBackInfo]): WrBackForwardInfo = {
    val wrBack = info.bits

    val out = Wire(new WrBackForwardInfo)
    out.addr      := wrBack.gpr.addr
    out.enWr      := wrBack.gpr.en && info.valid
    out.dataVaild := info.valid
    out.data      := wrBack.gpr.data
    out
  }
}

class WBU extends Module {
  val io = IO(new Bundle {
    val in       = Flipped(Decoupled(new WriteBackInfo))
    val gpr      = GPRegReqIO.TX.Write
    val csr      = CSRegReqIO.TX.Write
    val is_ecall = Output(Bool())
    val done     = Output(Bool())
  })

  val wbinfo = io.in.bits
  val valid  = io.in.valid

  io.in.ready := true.B

  val halted    = RegInit(false.B)
  val is_ebreak = wbinfo.is_ebreak && valid

  when(is_ebreak && !halted) {
    ClockedCallVoidDPIC("raise_ebreak")(clock, is_ebreak)
    halted := true.B
    stop()
  }

  when(valid && (!is_ebreak)) {
    ClockedCallVoidDPIC("pc_upd")(clock, valid && !is_ebreak, wbinfo.pc, wbinfo.nxt_pc)
  }

  SkipDifftestRef(clock, valid && wbinfo.skipDifftest)

  io.gpr.en   := wbinfo.gpr.en && valid
  io.gpr.addr := wbinfo.gpr.addr
  io.gpr.data := wbinfo.gpr.data

  io.csr.en   := wbinfo.csr.en && valid
  io.csr.addr := wbinfo.csr.addr
  io.csr.data := wbinfo.csr.data
  io.is_ecall := wbinfo.csr_ecallflag && valid

  io.done := valid

  dontTouch(io)
}
