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

trait HasRs {
  val rs1: UInt
  val rs2: UInt
}

object AddrSpace {
  val CLINT  = ("h02000000".U(32.W), "h0200ffff".U(32.W))
  val SPI    = ("h10001000".U(32.W), "h10002000".U(32.W))
  val SERIAL = ("h10000000".U(32.W), "h10001000".U(32.W))

  val SRAM = ("h0f000000".U(32.W), "h10000000".U(32.W))

  val VGA = ("h21000000".U(32.W), "h21200000".U(32.W))
  val PS2 = ("h10011000".U(32.W), "h10011007".U(32.W))

  val SOC_ExceptSRAM = ("h10000000".U(32.W), "hffffffff".U(32.W))

  val SOC = ("h0f000000".U(32.W), "hffffffff".U(32.W))

  val NPCMEM = ("h80000000".U(32.W), "h8fffffff".U(32.W))

  def inRng(addr: UInt, rng: (UInt, UInt)): Bool = {
    (addr >= rng._1) && (addr < rng._2)
  }

  def needSkipDifftestGroup = Seq(
    SERIAL,SPI,CLINT,VGA,PS2
  )
}

case class CPUParameters(
  gprAddrWidth: Int = 4,
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
  val branch, arithmetic, load, store, jalr, jal, lui, auipc, system, fencei = Value(nextValue)
  private def nextValue: UInt = (1 << (all.size)).U

  def hasSame(a: InstType.Type, b: InstType.Type): Bool = {
    (a.asUInt & b.asUInt).orR
    // a === b
  }
}

class Inst extends Bundle {
  val code            = Output(Types.UWord)
  val pc              = Output(Types.UWord)
  val iid             = Output(Types.InstID)
  val predictedNextPC = Output(Types.UWord)
}

class InstMetaInfo extends Bundle {
  val fmt = InstFmt()
  val typ = InstType()
}

class DecodedInstInfo(implicit p : CPUParameters) extends InstMetaInfo with HasRs {
  val imm = Types.UWord
  val rd  = p.GPRAddr
  val rs1 = p.GPRAddr
  val rs2 = p.GPRAddr

  val rdWrEn = Bool()

  val reg1 = Types.UWord
  val reg2 = Types.UWord

  val snpc = Types.UWord

  val pcAddImm = Types.UWord
  val reg1AddImm = Types.UWord

  val isECall = Bool()
  val isMRet  = Bool()

  val takeIfBranch = Bool()
}

class DecodedInst(implicit p : CPUParameters) extends Inst {
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
    thisOut: DecoupledIO[T2] = EmptyDecoupledData()
  ) = {

    val thisInReady = thisIn.ready

    val dataValid   = RegInit(false.B)
    val readyToPrev = (!dataValid) || thisInReady

    when(readyToPrev) {
      dataValid := prevOut.valid
    }
    prevOut.ready := readyToPrev

    thisIn.bits  := RegEnable(prevOut.bits, prevOut.fire)
    thisIn.valid := dataValid
  }
}
