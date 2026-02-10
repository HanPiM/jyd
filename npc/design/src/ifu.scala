package cpu
import chisel3._
import chisel3.util._
import common_def._
import busfsm._

import axi4._

// update reg when enable,
// and output the new value immediately
object RegEnableReadNew {
  def apply[T <: Data](nxt: T, en: Bool): T = {
    val reg = RegEnable(nxt, en)
    Mux(en, nxt, reg)
  }
}

class IFU extends Module {
  val io = IO(new Bundle {
    val pc  = Flipped(Decoupled(Types.UWord))
    val mem = AXI4IO.Master
    val out = Decoupled(new Inst)
  })

  object State extends ChiselEnum {
    val idle, waitAR, waitR, waitOut = Value
  }

  dontTouch(io)
  val memIO = io.mem
  io.mem.dontCareAW()
  io.mem.dontCareW()
  io.mem.dontCareB()
  io.mem.dontCareNonLiteAR()

  val pcReg = RegEnable(io.pc.bits, io.pc.fire)
  val pc    = Mux(io.pc.fire, io.pc.bits, pcReg)
  dontTouch(pc)
  val state = RegInit(State.idle)

  io.pc.ready   := (state === State.idle) && !reset.asBool
  memIO.arvalid := (state === State.waitAR) || (state === State.idle && io.pc.fire)
  memIO.araddr  := pc

  val inst = RegEnableReadNew(memIO.rdata, memIO.rvalid)
  memIO.rready     := io.out.ready
  io.out.bits.code := inst
  io.out.bits.pc   := pc
  io.out.valid     := (state === State.waitR && memIO.rvalid) || (state === State.idle && io.pc.fire && memIO.rvalid) || (state === State.waitOut)

  io.pc.ready := (state === State.idle) && !reset.asBool

  val nxtStateWhenWaitOut = Mux(io.out.ready, State.idle, State.waitOut)
  val nxtStateWhenWaitR   = Mux(memIO.rvalid, nxtStateWhenWaitOut, State.waitR)
  val nxtStateWhenWaitAR  = Mux(memIO.arready, nxtStateWhenWaitR, State.waitAR)

  state := MuxLookup(state, State.idle)(
    Seq(
      State.idle    -> Mux(io.pc.fire, nxtStateWhenWaitAR, State.idle),
      State.waitAR  -> nxtStateWhenWaitAR,
      State.waitR   -> nxtStateWhenWaitR,
      State.waitOut -> nxtStateWhenWaitOut
    )
  )
}

/*


  val state = RegInit(State.idle)
  state         := MuxLookup(state, State.idle)(
    Seq(
      State.idle   -> Mux(io.pc.fire, State.waitAR, State.idle),
      State.waitAR -> Mux(memIO.arready, Mux(memIO.rvalid,State.idle,State.waitR), State.waitAR),
      State.waitR  -> Mux(memIO.rvalid, Mux(io.pc.fire, State.waitAR, State.idle), State.waitR)
    )
  )
  val pcReg = Reg(Types.UWord)
  when(io.pc.fire) {
    pcReg := io.pc.bits
  }
  memIO.arvalid := (state === State.waitAR) || (state === State.idle && io.pc.fire)
  memIO.araddr  := Mux(io.pc.fire, io.pc.bits, pcReg)

  val instReg = Reg(Types.UWord)
  when(memIO.rvalid) {
    instReg := memIO.rdata
  }
  memIO.rready := (state === State.waitR) && io.out.ready

  io.out.valid := (state === State.waitR && memIO.rvalid) || (state === State.idle && io.pc.fire && memIO.rvalid)
  io.pc.ready

  io.out.bits.code := Mux(memIO.rvalid, memIO.rdata, instReg)
  io.out.bits.pc   := Mux(io.pc.fire, io.pc.bits, pcReg)
 * */
