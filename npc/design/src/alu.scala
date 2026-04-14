package cpu.alu

import chisel3._
import chisel3.util._
import common_def._
import busfsm._

class ALUInput extends Bundle {
  val is_imm = Bool()
  val func3t = UInt(3.W)
  val func7t = UInt(7.W)
  val src1   = Types.UWord
  val src2   = Types.UWord

  val isLessThan = Bool()
  val isLessThanU = Bool()
}

class ALU_foo extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new ALUInput))
    val out = Decoupled(Types.UWord)
  })

  io.out.valid := io.in.valid
  io.in.ready := io.out.ready

  // do some foo op for test
  io.out.bits := io.in.bits.src1 + io.in.bits.src2 + io.in.bits.func3t
}

class ALU extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new ALUInput))
    val out = Decoupled(Types.UWord)
  })

  val fsm = InnerBusCtrl(io.in, io.out, alwaysComb = true)

  // alias
  val inbits = io.in.bits
  val src1   = inbits.src1
  val src2   = inbits.src2

  val func3t = inbits.func3t

  val s_src1 = src1.asSInt
  val s_src2 = src2.asSInt

  val shamt = src2(4, 0)

  val isOpAlt = inbits.func7t(5)

  val isAdd = ((~isOpAlt) || inbits.is_imm) //&& (~inbits.func3t(1))

  val add_sub_res = Wire(Types.UWord)

  add_sub_res := Mux(isAdd, src1 + src2, src1 - src2)

  val sltu_res = inbits.isLessThanU

  val sign_src1 = src1(31)
  val sign_src2 = src2(31)
  val sign_res  = add_sub_res(31)

  val slt_res = inbits.isLessThan

  val rShiftResult = Wire(Types.UWord)
  val lShiftResult = Wire(Types.UWord)

  rShiftResult := Mux(isOpAlt, (s_src1 >> shamt).asUInt, src1 >> shamt)
  lShiftResult := src1 << shamt


  val defaultRes = Wire(Types.UWord)
  defaultRes := DontCare

  // left shift here
  // expilcitly tell chisel that width is 32
  // to avoid use 64-bit as result leads to big case
  //
  // can make alu alone module area smaller
  // but when considering whole cpu module
  // seems no difference ???
  // val leftShiftRes = Wire(Types.UWord)
  // leftShiftRes := src1 << shamt

  val logic_and = src1 & src2
  val logic_xor = src1 ^ src2
  // Optimize or to use and/xor result
  // 23445 -> 23282
  val logic_or  = logic_and | logic_xor

  // val func3t2HighResult = Mux(
  //   func3t(0),
  //   Mux(func3t(1), rShiftResult, sltu_res),
  //   Mux(func3t(1), logic_or, logic_xor)
  // )
  //
  // val func3t2LowResult = Mux(
  //   func3t(1),
  //   Mux(func3t(0), sltu_res, slt_res),
  //   Mux(func3t(0), Reverse(rShiftResult), add_sub_res)
  // )
  //
  // io.out.bits := Mux(func3t(2), func3t2HighResult, func3t2LowResult)

  io.out.bits := MuxLookup(inbits.func3t, defaultRes)(
    Seq(
      0.U -> add_sub_res,        // 000: add/sub/addi
      1.U -> lShiftResult, // 001: sll/slli
      2.U -> slt_res,            // 010: slt/slti
      3.U -> sltu_res,           // 011: sltu/sltiu
      4.U -> logic_xor,          // 100: xor/xori
      5.U -> rShiftResult,          // 101: srl/srli/sra/srai
      6.U -> logic_or,           // 110: or/ori
      7.U -> logic_and           // 111: and/andi
    )
  )
}
