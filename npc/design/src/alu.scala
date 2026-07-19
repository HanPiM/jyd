package cpu.alu

import chisel3._
import chisel3.util._
import common_def._
import busfsm._

class ALUInput extends Bundle {
  val is_imm    = Bool()
  val func3t    = UInt(3.W)
  val func7t    = UInt(7.W)
  val bExtValid = Bool()
  val src1      = Types.UWord
  val src2      = Types.UWord
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

class DividerInput extends Bundle {
  val src1   = Types.UWord
  val src2   = Types.UWord
  val func3t = UInt(3.W)
}

object MultiplierConfig {
  val latency     = 4
  val fastLatency = 3
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
    s"""module mult_gen_0(
      |  input         CLK,
      |  input  [32:0] A,
      |  input  [32:0] B,
      |  output [65:0] P
      |);
      |  reg [65:0] pipe [0:${MultiplierConfig.latency - 1}];
      |  integer i;
      |  wire signed [32:0] a_signed = A;
      |  wire signed [32:0] b_signed = B;
      |  wire signed [65:0] product = a_signed * b_signed;
      |
      |  initial begin
      |    for (i = 0; i < ${MultiplierConfig.latency}; i = i + 1)
      |      pipe[i] = 66'd0;
      |  end
      |
      |  always @(posedge CLK) begin
      |    pipe[0] <= product;
      |    for (i = 1; i < ${MultiplierConfig.latency}; i = i + 1)
      |      pipe[i] <= pipe[i - 1];
      |  end
      |
      |  assign P = pipe[${MultiplierConfig.latency - 1}];
      |endmodule
      |""".stripMargin
  )
}

class mult_gen_mul32_fast extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val CLK = Input(Clock())
    val A   = Input(UInt(32.W))
    val B   = Input(UInt(32.W))
    val P   = Output(UInt(32.W))
  })

  setInline(
    "mult_gen_mul32_fast.sv",
    s"""module mult_gen_mul32_fast(
      |  input         CLK,
      |  input  [31:0] A,
      |  input  [31:0] B,
      |  output [31:0] P
      |);
      |  reg [31:0] pipe [0:${MultiplierConfig.fastLatency - 1}];
      |  integer i;
      |  wire [63:0] product = A * B;
      |
      |  initial begin
      |    for (i = 0; i < ${MultiplierConfig.fastLatency}; i = i + 1)
      |      pipe[i] = 32'd0;
      |  end
      |
      |  always @(posedge CLK) begin
      |    pipe[0] <= product[31:0];
      |    for (i = 1; i < ${MultiplierConfig.fastLatency}; i = i + 1)
      |      pipe[i] <= pipe[i - 1];
      |  end
      |
      |  assign P = pipe[${MultiplierConfig.fastLatency - 1}];
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

  val isFastReg     = Reg(Bool())
  val resultReg     = Reg(Types.UWord)
  val slowValidPipe = RegInit(0.U(MultiplierConfig.latency.W))
  val fastValidPipe = RegInit(0.U(MultiplierConfig.fastLatency.W))
  val multiplier    = Module(new mult_gen_0)
  val fastMultiplier = Module(new mult_gen_mul32_fast)

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
  fastMultiplier.io.CLK := clock
  fastMultiplier.io.A   := io.in.bits.src1
  fastMultiplier.io.B   := io.in.bits.src2

  val product = multiplier.io.P
  val result = Mux(isFastReg, fastMultiplier.io.P, product(63, 32))
  val resultValid = Mux(
    isFastReg,
    fastValidPipe(MultiplierConfig.fastLatency - 1),
    slowValidPipe(MultiplierConfig.latency - 1)
  )

  io.in.ready  := state === State.idle
  io.out.valid := (state === State.done) || ((state === State.busy) && resultValid)
  io.out.bits  := Mux(state === State.done, resultReg, result)

  switch(state) {
    is(State.idle) {
      when(io.in.fire) {
        isFastReg := io.in.bits.func3t === 0.U
        slowValidPipe := Mux(io.in.bits.func3t === 0.U, 0.U, 1.U)
        fastValidPipe := Mux(io.in.bits.func3t === 0.U, 1.U, 0.U)
        state := State.busy
      }
    }
    is(State.busy) {
      slowValidPipe := slowValidPipe << 1
      fastValidPipe := fastValidPipe << 1
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

class Divider extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new DividerInput))
    val out = Decoupled(Types.UWord)
  })

  object State extends ChiselEnum {
    val idle, busy, done = Value
  }
  val state = RegInit(State.idle)

  val resultIsRemReg = Reg(Bool())
  val resultNegReg   = Reg(Bool())
  val resultReg      = Reg(Types.UWord)
  val divisorReg     = Reg(Types.UWord)
  val quotientReg    = Reg(Types.UWord)
  val remainderReg   = Reg(UInt(33.W))
  val iterationReg   = Reg(UInt(5.W))

  val inputFunc3t = io.in.bits.func3t
  val inputIsRem = inputFunc3t(1)
  val inputIsSigned = !inputFunc3t(0)
  val inputDividendNeg = inputIsSigned && io.in.bits.src1(31)
  val inputDivisorNeg = inputIsSigned && io.in.bits.src2(31)
  val inputDividendAbs = Mux(inputDividendNeg, (~io.in.bits.src1).asUInt + 1.U, io.in.bits.src1)
  val inputDivisorAbs = Mux(inputDivisorNeg, (~io.in.bits.src2).asUInt + 1.U, io.in.bits.src2)
  val inputDivideByZero = io.in.bits.src2 === 0.U
  val inputSignedOverflow = inputIsSigned && (io.in.bits.src1 === "h80000000".U) && (io.in.bits.src2 === "hffffffff".U)
  val inputSpecial = inputDivideByZero || inputSignedOverflow
  val inputDivideByZeroResult = Mux(inputIsRem, io.in.bits.src1, "hffffffff".U)
  val inputOverflowResult = Mux(inputIsRem, 0.U, "h80000000".U)
  val inputSpecialResult = Mux(inputDivideByZero, inputDivideByZeroResult, inputOverflowResult)

  val shiftedRemainder = Cat(remainderReg(31, 0), quotientReg(31))
  val subtractResult = shiftedRemainder - Cat(0.U(1.W), divisorReg)
  val canSubtract = !subtractResult(32)
  val nextRemainder = Mux(canSubtract, subtractResult, shiftedRemainder)
  val nextQuotient = Cat(quotientReg(30, 0), canSubtract)
  val unsignedResult = Mux(resultIsRemReg, nextRemainder(31, 0), nextQuotient)
  val correctedResult = Mux(resultNegReg, (~unsignedResult).asUInt + 1.U, unsignedResult)

  io.in.ready  := state === State.idle
  io.out.valid := state === State.done
  io.out.bits  := resultReg

  switch(state) {
    is(State.idle) {
      when(io.in.fire) {
        resultIsRemReg  := inputIsRem
        resultNegReg    := Mux(inputIsRem, inputDividendNeg, inputDividendNeg ^ inputDivisorNeg)
        when(inputSpecial) {
          resultReg := inputSpecialResult
          state     := State.done
        }.otherwise {
          divisorReg   := inputDivisorAbs
          quotientReg  := inputDividendAbs
          remainderReg := 0.U
          iterationReg := 0.U
          state        := State.busy
        }
      }
    }
    is(State.busy) {
      when(iterationReg === 31.U) {
        resultReg := correctedResult
        state     := State.done
      }.otherwise {
        quotientReg  := nextQuotient
        remainderReg := nextRemainder
        iterationReg := iterationReg + 1.U
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
    val in        = Flipped(Decoupled(new ALUInput))
    val out       = Decoupled(Types.UWord)
    val addResult = Output(Types.UWord)
    val singleCycleResult = Output(Types.UWord)
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

  val addResult = src1 + src2
  val add_sub_res = Mux(isAdd, addResult, src1 - src2)
  io.addResult := addResult

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
  val logic_or  = src1 | src2
  val isPack = !inbits.is_imm && inbits.func3t === "b100".U && inbits.func7t === "b0000100".U
  val packResult = Cat(src2(15, 0), src1(15, 0))

  val aluResult = Mux(
    isPack,
    packResult,
    MuxLookup(inbits.func3t, defaultRes)(
      Seq(
        0.U -> add_sub_res,  // 000: add/sub/addi
        1.U -> lShiftResult, // 001: sll/slli
        2.U -> slt_res,      // 010: slt/slti
        3.U -> sltu_res,     // 011: sltu/sltiu
        4.U -> logic_xor,    // 100: xor/xori
        5.U -> rShiftResult, // 101: srl/srli/sra/srai
        6.U -> logic_or,     // 110: or/ori
        7.U -> logic_and     // 111: and/andi
      )
    )
  )
  io.singleCycleResult := aluResult

  val isBExt = inbits.bExtValid

  val isMExt = !inbits.is_imm && inbits.func7t === "b0000001".U

  val isMulOp = isMExt && ~inbits.func3t(2)
  val isDivOp = isMExt && inbits.func3t(2)

  val bExtension = Module(new BExtensionUnit)
  bExtension.io.in.valid     := io.in.valid && isBExt
  bExtension.io.in.bits.isImm := inbits.is_imm
  bExtension.io.in.bits.func3t := inbits.func3t
  bExtension.io.in.bits.func7t := inbits.func7t
  bExtension.io.in.bits.src1 := src1
  bExtension.io.in.bits.src2 := src2
  bExtension.io.out.ready    := io.out.ready

  val multiplier = Module(new Multiplier)
  multiplier.io.in.valid       := io.in.valid && isMulOp
  multiplier.io.in.bits.src1   := src1
  multiplier.io.in.bits.src2   := src2
  multiplier.io.in.bits.func3t := func3t
  multiplier.io.out.ready      := io.out.ready

  val divider = Module(new Divider)
  divider.io.in.valid       := io.in.valid && isDivOp
  divider.io.in.bits.src1   := src1
  divider.io.in.bits.src2   := src2
  divider.io.in.bits.func3t := func3t
  divider.io.out.ready      := io.out.ready

  io.in.ready := Mux1H(
    Seq(
      isBExt -> bExtension.io.in.ready,
      isMulOp -> multiplier.io.in.ready,
      isDivOp -> divider.io.in.ready,
      (!isBExt && !isMExt) -> io.out.ready
    )
  )

  io.out.valid := Mux1H(
    Seq(
      isBExt -> bExtension.io.out.valid,
      isMulOp -> multiplier.io.out.valid,
      isDivOp -> divider.io.out.valid,
      (!isBExt && !isMExt) -> io.in.valid
    )
  )
  io.out.bits := Mux1H(
    Seq(
      isBExt -> bExtension.io.out.bits,
      isMulOp -> multiplier.io.out.bits,
      isDivOp -> divider.io.out.bits,
      (!isBExt && !isMExt) -> aluResult
    )
  )
}
