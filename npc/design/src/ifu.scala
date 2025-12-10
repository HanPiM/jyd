package cpu
import chisel3._
import chisel3.util._
import common_def._
import busfsm._

import memory._
import axi4._

class IFU extends Module {
  val io = IO(new Bundle {
    val pc  = Flipped(Decoupled(Types.UWord))
    val mem = AXI4IO.Master
    val out = Decoupled(new Inst)
  })

  val fsm = Module(new OneMasterOneSlaveFSM)
  fsm.connectMaster(io.pc)
  fsm.connectSlave(io.out)

  val lastPC = RegNext(io.pc.bits)
  val code = Reg(Types.UWord)
  val fetchDone = Reg(Bool())
  val arSent = Reg(Bool())
  val pcChanged = io.pc.bits =/= lastPC

  dontTouch(code)
  dontTouch(fetchDone)
  dontTouch(io)

  val memIO = io.mem

  io.mem.dontCareAW()
  io.mem.dontCareW()
  io.mem.dontCareB()

  memIO.arvalid := io.pc.valid && (!fetchDone) && (!arSent)
  memIO.araddr  := io.pc.bits

  // not use now
  io.mem.dontCareNonLiteAR()

  when(memIO.arvalid && memIO.arready) {
    arSent := true.B
  }


  when(memIO.rvalid && !fetchDone) {
    code := memIO.rdata
    fetchDone := true.B
  }
  memIO.rready := true.B

  when(!fsm.io.master_valid || pcChanged) {
    fetchDone := false.B
    arSent := false.B
  }

  fsm.io.self_finished := fetchDone

  // NOTICE: dpi function auto generated with void return
  // see https://github.com/llvm/circt/blob/main/docs/Dialects/FIRRTL/FIRRTLIntrinsics.md#dpi-intrinsic-abi
  io.out.bits.code := code
  io.out.bits.pc   := io.pc.bits
}


