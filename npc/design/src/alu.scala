package cpu.alu

import chisel3._
import chisel3.util._
import common_def._
import busfsm._

class ALUInput extends Bundle {
  val is_imm    = Bool()
  val isSub     = Bool()
  val func3t    = UInt(3.W)
  val func7t    = UInt(7.W)
  val bExtValid = Bool()
  val src1      = Types.UWord
  val src2      = Types.UWord
  val mulRawSrc1 = Types.UWord
  val mulPrevData = Types.UWord
  val mulPrevRs1 = Bool()
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
  val rawSrc1 = Types.UWord
  val prevData = Types.UWord
  val prevRs1 = Bool()
  val func3t = UInt(3.W)
}

class DividerInput extends Bundle {
  val src1   = Types.UWord
  val src2   = Types.UWord
  val func3t = UInt(3.W)
}

object MultiplierConfig {
  val latency       = 4
  val fastLatency   = 3
  val narrowLatency = 1
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

/** Two-stage low-word multiplier used only when both unsigned operands fit in 16 bits.
  *
  * The dedicated narrow IP keeps the 16x16 DSP mapping separate from the regular
  * 32x32 MUL datapath.  MULH-family operations always use the regular path.
  */
class mult_gen_mul16_fast extends BlackBox with HasBlackBoxInline {
  val io = IO(new Bundle {
    val CLK = Input(Clock())
    val A   = Input(UInt(16.W))
    val B   = Input(UInt(16.W))
    val P   = Output(UInt(32.W))
  })

  setInline(
    "mult_gen_mul16_fast.sv",
    s"""module mult_gen_mul16_fast(
      |  input         CLK,
      |  input  [15:0] A,
      |  input  [15:0] B,
      |  output [31:0] P
      |);
      |  reg [31:0] pipe [0:${MultiplierConfig.narrowLatency - 1}];
      |  integer i;
      |  wire [31:0] product = A * B;
      |
      |  initial begin
      |    for (i = 0; i < ${MultiplierConfig.narrowLatency}; i = i + 1)
      |      pipe[i] = 32'd0;
      |  end
      |
      |  always @(posedge CLK) begin
      |    pipe[0] <= product;
      |    for (i = 1; i < ${MultiplierConfig.narrowLatency}; i = i + 1)
      |      pipe[i] <= pipe[i - 1];
      |  end
      |
      |  assign P = pipe[${MultiplierConfig.narrowLatency - 1}];
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

  val isFastReg       = Reg(Bool())
  val isNarrowFastReg = Reg(Bool())
  val narrowSelectReg = Reg(Bool())
  val resultReg       = Reg(Types.UWord)
  val slowValidPipe   = RegInit(0.U(MultiplierConfig.latency.W))
  val fastValidPipe   = RegInit(0.U(MultiplierConfig.fastLatency.W))
  val narrowValidPipe = RegInit(0.U(MultiplierConfig.narrowLatency.W))
  val multiplier      = Module(new mult_gen_0)
  val fastMultiplier = Module(new mult_gen_mul32_fast)
  val narrowMultiplier = Seq.fill(2)(Module(new mult_gen_mul16_fast))

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
  narrowMultiplier.foreach(_.io.CLK := clock)
  narrowMultiplier(0).io.A := io.in.bits.rawSrc1(15, 0)
  narrowMultiplier(0).io.B := io.in.bits.src2(15, 0)
  narrowMultiplier(1).io.A := io.in.bits.prevData(15, 0)
  narrowMultiplier(1).io.B := io.in.bits.src2(15, 0)

  val product = multiplier.io.P
  val narrowProduct = Mux(narrowSelectReg, narrowMultiplier(1).io.P, narrowMultiplier(0).io.P)
  val result = Mux(isNarrowFastReg, narrowProduct, Mux(isFastReg, fastMultiplier.io.P, product(63, 32)))
  val resultValid = Mux(isNarrowFastReg, narrowValidPipe(MultiplierConfig.narrowLatency - 1), Mux(
    isFastReg,
    fastValidPipe(MultiplierConfig.fastLatency - 1),
    slowValidPipe(MultiplierConfig.latency - 1)
  ))

  io.in.ready  := state === State.idle
  io.out.valid := (state === State.done) || ((state === State.busy) && resultValid)
  io.out.bits  := Mux(state === State.done, resultReg, result)

  switch(state) {
    is(State.idle) {
      when(io.in.fire) {
        val isMul = io.in.bits.func3t === 0.U
        val isNarrowFast = isMul && io.in.bits.src1(31, 16) === 0.U && io.in.bits.src2(31, 16) === 0.U
        isFastReg := isMul
        isNarrowFastReg := isNarrowFast
        narrowSelectReg := io.in.bits.prevRs1
        slowValidPipe := Mux(isMul, 0.U, 1.U)
        fastValidPipe := Mux(isMul, 1.U, 0.U)
        narrowValidPipe := Mux(isNarrowFast, 1.U, 0.U)
        state := State.busy
      }
    }
    is(State.busy) {
      slowValidPipe := slowValidPipe << 1
      fastValidPipe := fastValidPipe << 1
      narrowValidPipe := narrowValidPipe << 1
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
    val idle, busy, correct, done = Value
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
  // The iterative algorithm naturally produces the architecturally required
  // divide-by-zero and INT_MIN / -1 results. Keeping both on the ordinary path
  // avoids data-dependent clock enables on the iterative datapath registers.

  val shiftedRemainder = Cat(remainderReg(31, 0), quotientReg(31))
  val subtractResult = shiftedRemainder - Cat(0.U(1.W), divisorReg)
  val canSubtract = !subtractResult(32)
  val nextRemainder = Mux(canSubtract, subtractResult, shiftedRemainder)
  val nextQuotient = Cat(quotientReg(30, 0), canSubtract)
  val unsignedResult = Mux(resultIsRemReg, nextRemainder(31, 0), nextQuotient)
  val correctedResult = Mux(resultNegReg, (~resultReg).asUInt + 1.U, resultReg)

  io.in.ready  := state === State.idle
  io.out.valid := state === State.done
  io.out.bits  := resultReg

  switch(state) {
    is(State.idle) {
      when(io.in.fire) {
        resultIsRemReg  := inputIsRem
        resultNegReg := Mux(
          inputIsRem,
          inputDividendNeg,
          !inputDivideByZero && (inputDividendNeg ^ inputDivisorNeg)
        )
        divisorReg   := inputDivisorAbs
        quotientReg  := inputDividendAbs
        remainderReg := 0.U
        iterationReg := 0.U
        state        := State.busy
      }
    }
    is(State.busy) {
      when(iterationReg === 31.U) {
        // Register the final unsigned result before optional sign correction.
        // Keeping the iterative 33-bit subtract and 32-bit two's-complement
        // carry chains in separate cycles removes their former serial path.
        resultReg := unsignedResult
        state     := State.correct
      }.otherwise {
        quotientReg  := nextQuotient
        remainderReg := nextRemainder
        iterationReg := iterationReg + 1.U
      }
    }
    is(State.correct) {
      resultReg := correctedResult
      state     := State.done
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
    val baseResult = Output(Types.UWord)
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

  // Share one carry chain between ADD and SUB.  The dedicated address-forward
  // output is valid only for ADD/ADDI, so it can reuse the selected result
  // instead of keeping a second unconditional src1 + src2 cone alive.
  val addSubSrc2 = Mux(inbits.isSub, ~src2, src2)
  val add_sub_res = src1 + addSubSrc2 + inbits.isSub
  io.addResult := add_sub_res

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

  val baseAluResult = MuxLookup(inbits.func3t, defaultRes)(
    Seq(
      0.U -> add_sub_res,                        // 000: add/sub/addi
      1.U -> lShiftResult,                       // 001: sll/slli
      2.U -> slt_res,                            // 010: slt/slti
      3.U -> sltu_res,                           // 011: sltu/sltiu
      4.U -> Mux(isPack, packResult, logic_xor), // 100: pack/xor/xori
      5.U -> rShiftResult,                       // 101: srl/srli/sra/srai
      6.U -> logic_or,                           // 110: or/ori
      7.U -> logic_and                           // 111: and/andi
    )
  )
  io.baseResult := baseAluResult

  val isSh1Add = !inbits.is_imm && inbits.func7t === "b0010000".U && inbits.func3t === "b010".U
  val isSh2Add = !inbits.is_imm && inbits.func7t === "b0010000".U && inbits.func3t === "b100".U
  val isSh3Add = !inbits.is_imm && inbits.func7t === "b0010000".U && inbits.func3t === "b110".U
  val isSextB = inbits.is_imm && inbits.func7t === "b0110000".U && inbits.func3t === "b001".U && src2(4, 0) === 4.U
  val isSextH = inbits.is_imm && inbits.func7t === "b0110000".U && inbits.func3t === "b001".U && src2(4, 0) === 5.U
  val isMinu = !inbits.is_imm && inbits.func7t === "b0000101".U && inbits.func3t === "b101".U
  val isBext = inbits.func7t === "b0100100".U && inbits.func3t === "b101".U

  val sh1AddResult = src2 + (src1 << 1)
  val sh2AddResult = src2 + (src1 << 2)
  val sh3AddResult = src2 + (src1 << 3)
  val sextBResult = Cat(Fill(24, src1(7)), src1(7, 0))
  val sextHResult = Cat(Fill(16, src1(15)), src1(15, 0))
  val minuResult = Mux(src1 < src2, src1, src2)
  val bextResult = Cat(0.U(31.W), (src1 >> src2(4, 0))(0))

  val aluResult = MuxCase(
    baseAluResult,
    Seq(
      isSh1Add -> sh1AddResult,
      isSh2Add -> sh2AddResult,
      isSh3Add -> sh3AddResult,
      isSextB  -> sextBResult,
      isSextH  -> sextHResult,
      isMinu   -> minuResult,
      isBext   -> bextResult
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
  multiplier.io.in.bits.rawSrc1 := inbits.mulRawSrc1
  multiplier.io.in.bits.prevData := inbits.mulPrevData
  multiplier.io.in.bits.prevRs1 := inbits.mulPrevRs1
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
