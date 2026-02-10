package cpu

import chisel3._
import chisel3.util._

import chisel3.experimental.dataview._

import common_def._
import busfsm._
import regfile._

class InstInfoDecoder extends Module {
  val io = IO(new Bundle {
    val opcode = Input(UInt(7.W))
    val valid  = Output(Bool())
    val out    = Output(new InstMetaInfo())
  })

  // opcode[1:0] should always be 11 for 32bit
  io.valid := io.opcode(1, 0).andR
  val opcu = io.opcode(6, 2)

  val lut = Seq(
    "b00000".U -> (InstFmt.imm, InstType.load),
    "b00100".U -> (InstFmt.imm, InstType.arithmetic),
    "b11001".U -> (InstFmt.imm, InstType.jalr),
    "b11100".U -> (InstFmt.imm, InstType.system),
    "b01100".U -> (InstFmt.reg, InstType.arithmetic),
    "b01000".U -> (InstFmt.store, InstType.store),
    "b01101".U -> (InstFmt.upper, InstType.lui),
    "b00101".U -> (InstFmt.upper, InstType.auipc),
    "b11011".U -> (InstFmt.jump, InstType.jal),
    "b11000".U -> (InstFmt.branch, InstType.branch),
    "b00011".U -> (InstFmt.imm, InstType.fencei)
  ).map { case (key, (fmt, typ)) =>
    key -> {
      val info = Wire(new InstMetaInfo)
      info.fmt := fmt
      info.typ := typ
      info
    }
  }

  val dontcare = Wire(new InstMetaInfo)
  dontcare := DontCare
  io.out   := MuxLookup(opcu, dontcare)(lut)
}

class IDU extends Module {
  val io = IO(new Bundle {
    val in   = Flipped(Decoupled(new Inst))
    val rvec = GPRegReqIO.TX.VecRead(2)
    val out  = Decoupled(new DecodedInst)
  })

  dontTouch(io)

  // val inReg = RegEnable(io.in.bits, io.in.fire)
  val inReg = io.in.bits

  // TODO: handle invalid instruction

  val fsm = InnerBusCtrl(io.in, io.out, alwaysComb = true)

  io.out.bits.viewAsSupertype(new Inst) := inReg

  // alias
  val res  = io.out.bits.info
  val inst = inReg.code

  val iinfo_dec = Module(new InstInfoDecoder())
  iinfo_dec.io.opcode                   := inst(6, 0)
  res.viewAsSupertype(new InstMetaInfo) := iinfo_dec.io.out

  res.rd  := inst(11, 7)
  res.rs1 := inst(19, 15)
  res.rs2 := inst(24, 20)

  io.rvec.en      := true.B
  io.rvec.addr(0) := res.rs1
  io.rvec.addr(1) := res.rs2

  res.reg1 := io.rvec.data(0)
  res.reg2 := io.rvec.data(1)

  // fetch IMM
  val immI = Cat(Fill(21, inst(31)), inst(30, 20))
  val immS = Cat(immI(31, 5), inst(11, 8), inst(7))
  val immB = Cat(immI(31, 12), inst(7), immS(10, 1), 0.U(1.W))
  val immU = Cat(inst(31, 12), 0.U(12.W))
  val immJ = Cat(immI(31, 20), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W))

  res.imm := MuxLookup(iinfo_dec.io.out.fmt, "hBAADF00D".U)(
    Seq(
      InstFmt.imm    -> immI,
      InstFmt.jump   -> immJ,
      InstFmt.store  -> immS,
      InstFmt.branch -> immB,
      InstFmt.upper  -> immU
    )
  )

}
