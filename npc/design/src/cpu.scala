package cpu

import chisel3._
import chisel3.util.{Cat, Decoupled, Enum, Fill, MuxLookup}
import chisel3.util.HasBlackBoxInline

// see https://www.chisel-lang.org/api/latest/chisel3/util/circt/dpi/index.html
// chisel has native dpi interface since
//  https://github.com/chipsalliance/chisel/pull/4158
import chisel3.util.circt.dpi.{
  RawClockedNonVoidFunctionCall,
  RawClockedVoidFunctionCall,
  RawUnclockedNonVoidFunctionCall
}

import chisel3.experimental.dataview._

import regfile._

object Types {
  object BitWidth {
    val reg_addr = 5
    val word     = 32
  }
  def UWord = UInt(BitWidth.word.W)
  def RegAddr = UInt(BitWidth.reg_addr.W)

  object Ops {
    implicit class StringOps(val s: String) extends AnyVal {
      def UWord = s.U(BitWidth.word.W)
    }
    implicit class IntOps(val s: Int)       extends AnyVal {
      def UWord = s.U(BitWidth.word.W)
    }
  }
}
import Types.Ops._

class Inst extends Bundle {
  val code = Output(Types.UWord)
  val pc   = Output(Types.UWord)
}

class IFU extends Module {
  val io                            = IO(new Bundle {
    val pc  = Input(Types.UWord)
    val out = Decoupled(new Inst)
  })
  val s_idle :: s_wait_ready :: Nil = Enum(2)
  val state                         = RegInit(s_idle)
  state            := MuxLookup(state, s_idle)(
    List(
      s_idle       -> Mux(io.out.valid, s_wait_ready, s_idle),
      s_wait_ready -> Mux(io.out.ready, s_idle, s_wait_ready)
    )
  )
  io.out.valid     := 1.B
  // NOTICE: dpi function auto generated with void return
  // see https://github.com/llvm/circt/blob/main/docs/Dialects/FIRRTL/FIRRTLIntrinsics.md#dpi-intrinsic-abi
  io.out.bits.code := RawClockedNonVoidFunctionCall("fetch_inst", Types.UWord)(clock, io.out.valid, io.pc)
  io.out.bits.pc   := io.pc
}

object InstFmt     extends ChiselEnum {
  val imm, reg, store, upper, jump, branch = Value
}
object InstType    extends ChiselEnum {
  val none, arithmetic, load, jalr, lui, auipc, system = Value
}
class InstMetaInfo extends Bundle     {
  val fmt = InstFmt()
  val typ = InstType()
}

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
    "b01100".U -> (InstFmt.reg, InstType.none),
    "b01000".U -> (InstFmt.store, InstType.none),
    "b01101".U -> (InstFmt.upper, InstType.lui),
    "b00101".U -> (InstFmt.upper, InstType.auipc),
    "b11011".U -> (InstFmt.jump, InstType.none),
    "b11000".U -> (InstFmt.branch, InstType.none)
  ).map { case (key, (fmt, typ)) =>
    key -> {
      val info = Wire(new InstMetaInfo)
      info.fmt := fmt
      info.typ := typ
      info
    }
  }

  io.out := MuxLookup(opcu, 0.U.asTypeOf(new InstMetaInfo()))(lut)
}

class DecodedInstInfo extends InstMetaInfo {
  val imm = Types.UWord
  val rd  = Types.RegAddr
  val rs1 = Types.RegAddr
  val rs2 = Types.RegAddr
}
class DecodedInst     extends Inst         {
  val info = new DecodedInstInfo
}

class IDU extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new Inst))
    val out = Decoupled(new DecodedInst)
  })

  val s_idle :: s_wait_ready :: Nil = Enum(2)
  val state                         = RegInit(s_idle)
  state        := MuxLookup(state, s_idle)(
    List(
      s_idle       -> Mux(io.in.valid, s_wait_ready, s_idle),
      s_wait_ready -> Mux(io.out.ready, s_idle, s_wait_ready)
    )
  )
  io.in.ready  := (state === s_idle)
  io.out.valid := (state === s_wait_ready)

  io.out.bits.viewAsSupertype(new Inst) := io.in.bits

  // alias
  val res  = io.out.bits.info
  val inst = io.in.bits.code

  val iinfo_dec = Module(new InstInfoDecoder())
  iinfo_dec.io.opcode                   := io.in.bits.code(6, 0)
  res.viewAsSupertype(new InstMetaInfo) := iinfo_dec.io.out

  res.rd  := inst(11, 7)
  res.rs1 := inst(19, 15)
  res.rs2 := inst(24, 20)

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

class ALUInput extends Bundle {
  val is_imm = Bool()
  val func3t = UInt(3.W)
  val func7t = UInt(7.W)
  val src1   = Types.UWord
  val src2   = Types.UWord
}

class ALU extends Module {
  val io               = IO(new Bundle {
    val in  = Flipped(Decoupled(new ALUInput))
    val out = Decoupled(Types.UWord)
  })
  val BADCALL_RESVALUE = "hBAADCA11".UWord

  io.in.ready  := 0.B
  io.out.valid := 0.B

  // alias
  val inbits = io.in.bits
  val src1   = inbits.src1
  val src2   = inbits.src2

  val s_src1 = src1.asSInt
  val s_src2 = src2.asSInt

  val shamt = src2(4, 0)

  val add_sub_res = Wire(Types.UWord)
  when(inbits.is_imm || inbits.func7t === 0.U) {
    add_sub_res := src1 + src2
  }.elsewhen(inbits.func7t === "b0100000".U) {
    add_sub_res := src1 - src2
  }.otherwise {
    add_sub_res := BADCALL_RESVALUE
    printf("(alu) UNKNOWN func7t %d", inbits.func7t)
  }

  val shift_res = Wire(Types.UWord)
  when(inbits.func7t === "b0100000".U) { // sra/srai
    shift_res := (s_src1 >> shamt).asUInt
  }.otherwise { // srl/srli
    shift_res := src1 >> shamt
  }

  io.out.bits := MuxLookup(inbits.func3t, BADCALL_RESVALUE)(
    Seq(
      0.U -> add_sub_res,                    // 000: add/sub/addi
      1.U -> (src1 << shamt),                // 001: sll/slli
      2.U -> Mux(s_src1 < s_src2, 1.U, 0.U), // 010: slt/slti
      3.U -> Mux(src1 < src2, 1.U, 0.U),     // 011: sltu/sltui
      4.U -> (src1 ^ src2),                  // 100: xor/xori
      5.U -> shift_res,                      // 101: srl/srli/sra/srai
      6.U -> (src1 | src2),                  // 110: or/ori
      7.U -> (src1 & src2)                   // 111: and/andi
    )
  )
}

class WriteBackInfo extends Bundle {
  val addr = Types.RegAddr
  val data = Types.UWord
}

class EXU extends Module {
  val io = IO(new Bundle {
    val dinst = Flipped(Decoupled(new DecodedInst))
    val rvec  = Flipped(new RegReadBundle(2))
    val out   = Decoupled(new WriteBackInfo)
  })

  val alu = Module(new ALU)

  val alu_in = alu.io.in.bits
  val dinst  = io.dinst.bits
  alu_in.is_imm := (dinst.info.fmt === InstFmt.imm)
  alu_in.func3t := dinst.code(14, 12)
  alu_in.func7t := dinst.code(31, 25)

  // reg
  io.rvec.addr(0) := dinst.info.rs1
  io.rvec.addr(1) := dinst.info.rs2
  val reg_v1 = io.rvec.data(0)
  val reg_v2 = io.rvec.data(1)

  alu_in.src1 := reg_v1
  alu_in.src2 := Mux(dinst.info.fmt === InstFmt.reg, reg_v2, dinst.info.imm)

}
