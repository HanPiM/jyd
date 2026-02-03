package common_def

import chisel3._
import chisel3.util._

object Types {
  object BitWidth {
    // N_REG = 16
    val reg_addr = 4
    val csr_addr = 12
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

object DbgVal {
  val UNINITIALIZED = 0xcccccccc.UWord
  val BADCALL       = 0xbaddca11.UWord
}

object InstFmt  extends ChiselEnum {
  val imm, reg, store, upper, jump, branch = Value
  private def nextValue: UInt = (1 << (all.size)).U

  def hasSame(a: InstFmt.Type, b: InstFmt.Type): Bool = {
    // (a.asUInt & b.asUInt).orR
    a === b
  }
}
object InstType extends ChiselEnum {
  val branch, arithmetic, load, store, jalr, jal, lui, auipc, system = Value
  private def nextValue: UInt = (1 << (all.size)).U

  def hasSame(a: InstType.Type, b: InstType.Type): Bool = {
    // (a.asUInt & b.asUInt).orR
    a === b
  }
}

class Inst extends Bundle {
  val code = Output(Types.UWord)
  val pc   = Output(Types.UWord)
}

class InstMetaInfo extends Bundle {
  val fmt = InstFmt()
  val typ = InstType()
}

class DecodedInstInfo extends InstMetaInfo {
  val imm = Types.UWord
  val rd  = Types.RegAddr
  val rs1 = Types.RegAddr
  val rs2 = Types.RegAddr
}

class DecodedInst extends Inst {
  val info = new DecodedInstInfo
}
