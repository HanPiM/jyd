package common_def

import chisel3._
import chisel3.util._

object Types {
  object BitWidth {
    val reg_addr = 5
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

object InstFmt  extends ChiselEnum {
  val imm, reg, store, upper, jump, branch = Value
}
object InstType extends ChiselEnum {
  val none, arithmetic, load, store, jalr, jal, lui, auipc, system = Value
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
