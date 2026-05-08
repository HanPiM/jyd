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

class MultiplierInput extends Bundle {
  val src1   = Types.UWord
  val src2   = Types.UWord
  val func3t = UInt(3.W)
}

class mult_gen_0 extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val CLK  = Input(Clock())
    val A    = Input(UInt(33.W))
    val B    = Input(UInt(33.W))
    val P    = Output(UInt(66.W))
  })

  setInline(
    "mult_gen_0.sv",
    """module mult_gen_0(
      |  input         CLK,
      |  input  [32:0] A,
      |  input  [32:0] B,
      |  output [65:0] P
      |);
      |  reg [65:0] pipe [0:5];
      |  integer i;
      |  wire signed [32:0] a_signed = A;
      |  wire signed [32:0] b_signed = B;
      |  wire signed [65:0] product = a_signed * b_signed;
      |
      |  initial begin
      |    for (i = 0; i < 6; i = i + 1)
      |      pipe[i] = 66'd0;
      |  end
      |
      |  always @(posedge CLK) begin
      |    pipe[0] <= product;
      |    for (i = 1; i < 6; i = i + 1)
      |      pipe[i] <= pipe[i - 1];
      |  end
      |
      |  assign P = pipe[5];
      |endmodule
      |""".stripMargin
  )
}

class Multiplier extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new MultiplierInput))
    val out = Decoupled(Types.UWord)
  })

  object State extends ChiselEnum {
    val idle, busy, done = Value
  }
  val state = RegInit(State.idle)

  val func3tReg  = Reg(UInt(3.W))
  val resultReg  = Reg(Types.UWord)
  val validPipe  = RegInit(0.U(6.W))
  val multiplier = Module(new mult_gen_0)

  val inputFunc3t = io.in.bits.func3t
  val inputIsMulh = inputFunc3t === 1.U
  val inputIsMulhsu = inputFunc3t === 2.U
  val signedModeA = inputIsMulh || inputIsMulhsu
  val signedModeB = inputIsMulh

  val aExt = Cat(signedModeA && io.in.bits.src1(31), io.in.bits.src1)
  val bExt = Cat(signedModeB && io.in.bits.src2(31), io.in.bits.src2)

  multiplier.io.CLK := clock
  multiplier.io.A   := aExt
  multiplier.io.B   := bExt

  val product = multiplier.io.P
  val result = Mux(func3tReg === 0.U, product(31, 0), product(63, 32))
  val resultValid = validPipe(5)

  io.in.ready  := state === State.idle
  io.out.valid := (state === State.done) || ((state === State.busy) && resultValid)
  io.out.bits  := Mux(state === State.done, resultReg, result)

  switch(state) {
    is(State.idle) {
      when(io.in.fire) {
        func3tReg := io.in.bits.func3t
        validPipe := 1.U
        state     := State.busy
      }
    }
    is(State.busy) {
      validPipe := validPipe << 1
      when(resultValid) {
        when(io.out.ready) {
          state := State.idle
        }.otherwise {
          resultReg := result
          state := State.done
        }
      }
    }
    is(State.done) {
      when(io.out.fire) {
        state := State.idle
      }
    }
  }
}

class ALU extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new ALUInput))
    val out = Decoupled(Types.UWord)
  })

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

  val sltu_res = src1 < src2
  val slt_res = s_src1 < s_src2

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

  val aluResult = MuxLookup(inbits.func3t, defaultRes)(
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

  val isMulOp = !inbits.is_imm && (inbits.func7t === "b0000001".U) && (inbits.func3t <= 3.U)

  val multiplier = Module(new Multiplier)
  multiplier.io.in.valid       := io.in.valid && isMulOp
  multiplier.io.in.bits.src1   := src1
  multiplier.io.in.bits.src2   := src2
  multiplier.io.in.bits.func3t := func3t
  multiplier.io.out.ready      := io.out.ready

  io.in.ready  := Mux(isMulOp, multiplier.io.in.ready, io.out.ready)
  io.out.valid := Mux(isMulOp, multiplier.io.out.valid, io.in.valid)
  io.out.bits  := Mux(isMulOp, multiplier.io.out.bits, aluResult)
}
