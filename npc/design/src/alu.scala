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

  val s_src1 = src1.asSInt
  val s_src2 = src2.asSInt

  val shamt = src2(4, 0)

  val isOpAlt = inbits.func7t(5)

  // when func3[1] == 1, less than need sub result
  val isAdd = ((~isOpAlt) || inbits.is_imm) && (~inbits.func3t(1))

  val add_sub_res = Wire(Types.UWord)

  // this optimize can make alu alone module area bigger
  // but the whole module area smaller
  // 23866 -> 23850
  // ??? I don't understand ???
  val op2_inv = Mux(isAdd, src2, ~src2)
  val cin     = !isAdd
  // add_sub_res := src1 + op2_inv + cin
  // add_sub_res := Mux(isAdd, src1 + src2, src1 - src2)

  val full_add_res = src1 +& op2_inv + cin
  add_sub_res := full_add_res(31, 0)
  val carry_out = full_add_res(32)

  // By using carry out to determine slt/sltu
  // Optimize 23504 -> 23445

  // For unsigned less than a + (-b) sign bit is carry out
  val sltu_res = !carry_out

  val sign_src1 = src1(31)
  val sign_src2 = src2(31)
  val sign_res  = add_sub_res(31)

  // only meaningful when sub mode (~isAdd)
  val overflow = (sign_src1 =/= sign_src2) && (sign_src1 =/= sign_res)

  // when overflow:
  //   result sign positive -> (a:-...) - (b: +...) overflow -> slt should be true
  // when no overflow:
  //   result sign negative -> less than -> slt should be true
  val slt_res = sign_res ^ overflow

  val shift_res = Wire(Types.UWord)
  // shift_res := Mux(isOpAlt, (s_src1 >> shamt).asUInt, src1 >> shamt)

  // Optimize make L/R shift use same shifter
  //
  // 23850 -> 23504
  val extedSrc1    = Wire(UInt(64.W))
  val isRightShift = inbits.func3t(2)
  val shiftedSrc1  = Mux(isRightShift, src1, Reverse(src1))
  extedSrc1 := Cat(Fill(32, shiftedSrc1(31) & isOpAlt), shiftedSrc1)
  shift_res := extedSrc1 >> shamt

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

  io.out.bits := MuxLookup(inbits.func3t, defaultRes)(
    Seq(
      0.U -> add_sub_res,        // 000: add/sub/addi
      1.U -> Reverse(shift_res), // 001: sll/slli
      2.U -> slt_res,            // 010: slt/slti
      3.U -> sltu_res,           // 011: sltu/sltiu
      4.U -> logic_xor,          // 100: xor/xori
      5.U -> shift_res,          // 101: srl/srli/sra/srai
      6.U -> logic_or,           // 110: or/ori
      7.U -> logic_and           // 111: and/andi
    )
  )
}
