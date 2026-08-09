package common_def

import chisel3._
import chisel3.util._

import config._

import chisel3.layer._
object InlinePrintfLayer extends Layer(LayerConfig.Inline)

// generate printf inside module, unlike normal printf which will be
// generated at verification layer
object InlinePrintf {
  def apply(pable: Printable) = {
    layer.block(InlinePrintfLayer) {
      printf(pable)
    }
  }
}

object TrimmedPC {
  def Hi10b = "h80".U(8.W) ## 0.U(2.W)
  def Lo2b = 0.U(2.W)

  // {12'h800, 3'b000, 15'b valid addr, 2'b00}

  def expand(addr: UInt): UInt = {
    require(addr.getWidth == 15)
    "h800".U(12.W) ## 0.U(3.W) ## addr ## 0.U(2.W)
  }
  def trim(addr: UInt): UInt = {
    // require(addr.getWidth == 32)
    addr(16,2)
  }
}

trait HasRs {
  val rs1: UInt
  val rs2: UInt
}

object AddrSpace {
  val SERIAL = ("h80200200".U(32.W), "h80200300".U(32.W))
  val CLINT  = ("h80200300".U(32.W), "h80200400".U(32.W))

  val NPCMEM = ("h80000000".U(32.W), "h8fffffff".U(32.W))

  def inRng(addr: UInt, rng: (UInt, UInt)): Bool = {
    (addr >= rng._1) && (addr < rng._2)
  }

  def needSkipDifftestGroup = Seq(
    SERIAL,
    CLINT
  )
}

case class CPUParameters(
  gprAddrWidth:      Int = 4,
  enableDCache:      Boolean = false,
  skipDifftestAddrs: Seq[(UInt, UInt)] = AddrSpace.needSkipDifftestGroup
) {
  def GPRAddr = UInt(gprAddrWidth.W)
  def GPRNum  = 1 << gprAddrWidth
}

object Types {
  object BitWidth {
    val csr_addr = 12
    val word     = 32

    val inst_id = if (Config.genStageLog) 32 else 0
  }
  def UWord = UInt(BitWidth.word.W)
  // def RegAddr = UInt(BitWidth.reg_addr.W)

  def InstID = UInt(BitWidth.inst_id.W)

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

object DbgVal {
  val UNINITIALIZED = 0xcccccccc.UWord
  val BADCALL       = 0xbaddca11.UWord
}

object InstFmt  extends ChiselEnum {
  val imm, reg, store, upper, jump, branch = Value(nextValue)
  private def nextValue: UInt = (1 << (all.size)).U

  def hasSame(a: InstFmt.Type, b: InstFmt.Type): Bool = {
    (a.asUInt & b.asUInt).orR
    // a === b
  }
}
object InstType extends ChiselEnum {
  val branch, arithmetic, load, store, jalr, jal, lui, auipc, system = Value(nextValue)
  private def nextValue: UInt = (1 << (all.size)).U

  def hasSame(a: InstType.Type, b: InstType.Type): Bool = {
    (a.asUInt & b.asUInt).orR
    // a === b
  }
}

class PredBundle extends Bundle {
  val pc = Types.UWord
  val hit = Bool()
  val take = Bool()
}

class Inst extends Bundle {
  val code            = Output(Types.UWord)
  val pc              = Output(Types.UWord)
  val iid             = Output(Types.InstID)
  val pred = Output(new PredBundle)
}

class FetchedInst extends Inst

class InstMetaInfo extends Bundle {
  val fmt = InstFmt()
  val typ = InstType()
}

object InstInfoDecoder {
  def apply(opcode: UInt): InstMetaInfo = {
    val opcu = opcode(6, 2)

    val lut = Seq(
      "b00000".U -> (InstFmt.imm, InstType.load),
      "b00100".U -> (InstFmt.imm, InstType.arithmetic),
      "b00010".U -> (InstFmt.reg, InstType.arithmetic),
      "b11001".U -> (InstFmt.imm, InstType.jalr),
      "b11100".U -> (InstFmt.imm, InstType.system),
      "b01100".U -> (InstFmt.reg, InstType.arithmetic),
      "b01000".U -> (InstFmt.store, InstType.store),
      "b01101".U -> (InstFmt.upper, InstType.lui),
      "b00101".U -> (InstFmt.upper, InstType.auipc),
      "b11011".U -> (InstFmt.jump, InstType.jal),
      "b11000".U -> (InstFmt.branch, InstType.branch)
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
    MuxLookup(opcu, dontcare)(lut)
  }
}

class DecodedInstInfo(implicit p : CPUParameters) extends InstMetaInfo with HasRs {
  val imm = Types.UWord
  val rd  = p.GPRAddr
  val rs1 = p.GPRAddr
  val rs2 = p.GPRAddr

  val rdWrEn = Bool()

  val reg1 = Types.UWord
  val reg2 = Types.UWord
  val csrReadData = Types.UWord

  val staticNextPCOrCSRTarget = Types.UWord

  val pcAddImm = Types.UWord
  // JYD instruction and data addresses always have fixed bits [31:22] =
  // 0x800. Carry only the dynamic low bits across the IDU/EXU boundary and
  // reconstruct the architectural address locally in EXU.
  val reg1AddImm = UInt(22.W)

  // A compact supported consumer may enter EXU before a load result is
  // available. EXU resolves these operands from the registered LSU/WBU
  // producer stages and holds the instruction until the data is valid.
  val lateLoadRs1 = Bool()
  val lateLoadRs2 = Bool()

  // An immediately preceding ready EXU producer crosses into LSU on the
  // same edge as this consumer enters EXU. Carry only the dependency choice;
  // the registered producer data is selected locally in EXU.
  val prevExuFwdRs1 = Bool()
  val prevExuFwdRs2 = Bool()

  // IDU classifies every non-RV32I/non-M arithmetic encoding as a supported
  // multi-cycle B operation. The exact B operation is decoded locally after
  // the B unit has registered its compact input fields.
  val bExtValid = Bool()

  // CoreMark CRCU8 is a single-cycle custom-0 R-type operation.
  val crcValid = Bool()

  // CoreMark bit-extract multiply is a single-cycle custom-0 operation.
  val xbmulValid = Bool()

  // CoreMark matrix reduction is a blocking multi-cycle custom operation.
  val xmsumValid = Bool()

  // Predecode the ALU add/sub carry polarity before the IDU/EXU register so
  // the instruction-code bus does not drive the carry chain directly.
  val aluIsSub = Bool()

  // Short B operations select the extended ALU result. Ordinary RV32I
  // forwarding can otherwise use the base result without crossing that mux.
  val aluUseSpecialResult = Bool()

  val isECall = Bool()
  val isMRet  = Bool()

  val is_beq  = Bool()
  val is_bne  = Bool()
  val is_blt  = Bool()
  val is_bge  = Bool()
  val is_bltu = Bool()
  val is_bgeu = Bool()

  val notBranchPredWrong = Bool()

  val preMuxWrBackData = Types.UWord
}

class DecodedInst(implicit p : CPUParameters) extends Bundle {
  val code = Types.UWord
  val pc   = Types.UWord
  val iid  = Types.InstID

  // IDU consumes the predicted PC and BTB-hit flag when it resolves JALR.
  // EXU only needs the direction bit for conditional branches, so avoid
  // carrying the other 33 prediction bits across the IDU/EXU boundary.
  val predTake = Bool()
  val info = new DecodedInstInfo
}

// update reg when enable,
// and output the new value immediately
object RegEnableReadNew {
  def apply[T <: Data](nxt: T, en: Bool): T = {
    val reg = RegEnable(nxt, en)
    Mux(en, nxt, reg)
  }
}

object EmptyDecoupledData {
  def apply() = {
    val out = Wire(Decoupled(UInt(0.W)))
    out.ready := true.B
    out.valid := true.B
    out.bits  := DontCare
    out
  }
}

object pipelineConnect {
  def apply[T <: Data, T2 <: Data](
    prevOut: DecoupledIO[T],
    thisIn:  DecoupledIO[T],
    thisOut: DecoupledIO[T2] = EmptyDecoupledData(),
    kill:    Bool = false.B
  ) = {
    val payloadReg = Reg(chiselTypeOf(thisIn.bits))
    val validReg   = RegInit(false.B)
    val allowIn    = !validReg || thisIn.ready

    when(allowIn) {
      payloadReg := prevOut.bits
      validReg   := prevOut.valid && !kill
    }.elsewhen(kill) {
      validReg := false.B
    }

    prevOut.ready := allowIn
    thisIn.bits   := payloadReg
    thisIn.valid  := validReg
  }
}
