package cpu
import chisel3._
import chisel3.util._
import common_def._
import busfsm._

import axi4._

class IFU extends Module {
  val io = IO(new Bundle {
    val pc  = Flipped(Decoupled(Types.UWord))
    val mem = AXI4IO.Master
    val out = Decoupled(new Inst)
  })

  val fsm = InnerBusCtrl(io.pc, io.out)

  dontTouch(io)

  val memIO = io.mem

  io.mem.dontCareAW()
  io.mem.dontCareW()
  io.mem.dontCareB()
  io.mem.dontCareNonLiteAR()

  object State extends ChiselEnum {
    val idle, waitAR, waitR = Value
  }

  val state = RegInit(State.idle)
  state         := MuxLookup(state, State.idle)(
    Seq(
      State.idle   -> Mux(io.pc.fire, State.waitAR, State.idle),
      State.waitAR -> Mux(memIO.arready, State.waitR, State.waitAR),
      State.waitR  -> Mux(memIO.rvalid, Mux(io.pc.fire, State.waitAR, State.idle), State.waitR)
    )
  )
  val pcReg = Reg(Types.UWord)
  when(io.pc.fire) {
    pcReg := io.pc.bits
  }
  memIO.arvalid := (state === State.waitAR)
  memIO.araddr  := pcReg

  val instReg = Reg(Types.UWord)
  when(memIO.rvalid) {
    instReg := memIO.rdata
  }
  memIO.rready := (state === State.waitR) && io.out.ready

  fsm.io.self_finished := ((state === State.waitR) && memIO.rvalid) || (state === State.idle)

  io.out.bits.code := Mux(memIO.rvalid, memIO.rdata, instReg)
  io.out.bits.pc   := pcReg
}
