package cpu

import chisel3._
import chisel3.util._

import chisel3.experimental.dataview._

import common_def._
import busfsm._
import regfile._
import dpiwrap._

import cpu.WriteBackInfo
import common_def.Types.Ops.StringOps

class WrBackForwardInfo(
  implicit p: CPUParameters)
    extends Bundle {
  val addr      = p.GPRAddr
  val enWr      = Bool()
  val dataVaild = Bool()
  val data      = Types.UWord
}

object WrBackForwardInfo {
  def apply(
    infoValid:  Bool,
    dinstInfo:  DecodedInst,
    dataVaild:  Bool,
    data:       UInt
  )(
    implicit p: CPUParameters
  ): WrBackForwardInfo = {
    val res = Wire(new WrBackForwardInfo)
    res.addr      := dinstInfo.info.rd
    res.enWr      := dinstInfo.info.rdWrEn && infoValid
    res.dataVaild := dataVaild
    res.data      := data
    res
  }
  def apply(
    dinst:      DecoupledIO[DecodedInst],
    dataVaild:  Bool,
    data:       UInt
  )(
    implicit p: CPUParameters
  ): WrBackForwardInfo = {
    apply(dinst.valid, dinst.bits, dataVaild, data)
  }
  def apply(
    dinst:      DecoupledIO[DecodedInst]
  )(
    implicit p: CPUParameters
  ): WrBackForwardInfo = {
    val foo = Wire(Types.UWord)
    foo := DontCare
    apply(dinst, false.B, foo)
  }
}

class WrBackInfoGroup(
  implicit p: CPUParameters)
    extends Bundle {
  val exu = new WrBackForwardInfo
  val lsu = new WrBackForwardInfo
  val wbu = new WrBackForwardInfo
}

class ByPassMux(
  implicit p: CPUParameters)
    extends Module {
  val io = IO(new Bundle {
    val rs1      = Input(p.GPRAddr)
    val rs2      = Input(p.GPRAddr)
    val regData1 = Input(Types.UWord)
    val regData2 = Input(Types.UWord)

    val wrBackInfo = Input(new WrBackInfoGroup)
    val needStall  = Output(Bool())

    val outData1 = Output(Types.UWord)
    val outData2 = Output(Types.UWord)
  })

  def conflict(rs: UInt, rd: UInt, en: Bool): Bool = (rs === rd) && (rd =/= 0.U) && en

  def conflict(rs: UInt, wrBack: WrBackForwardInfo): Bool = conflict(rs, wrBack.addr, wrBack.enWr)

  def oneConflictWithWrBack(wrBack: WrBackForwardInfo): Bool = {
    conflict(io.rs1, wrBack) || conflict(io.rs2, wrBack)
  }

  val isRs1ConflictEXU = conflict(io.rs1, io.wrBackInfo.exu)
  val isRs2ConflictEXU = conflict(io.rs2, io.wrBackInfo.exu)

  val isConflictWithEXU = WireDefault(isRs1ConflictEXU || isRs2ConflictEXU)
  dontTouch(isConflictWithEXU)

  val isConflictWithLSU = Wire(Bool())
  // lsu may have valid bypass data, but it immediately forward the data
  // to wbu after the memory access, for now, dont use it, just check conflict
  isConflictWithLSU := oneConflictWithWrBack(io.wrBackInfo.lsu)

  val isRs1ConflictWithWBU = conflict(io.rs1, io.wrBackInfo.wbu)
  val isRs2ConflictWithWBU = conflict(io.rs2, io.wrBackInfo.wbu)

  val isConflictWithWBU = WireDefault(isRs1ConflictWithWBU || isRs2ConflictWithWBU)
  dontTouch(isConflictWithLSU)
  dontTouch(isConflictWithWBU)

  val isRdAfterWr = Wire(Bool())
  isRdAfterWr := isConflictWithEXU || isConflictWithLSU || isConflictWithWBU
  dontTouch(isRdAfterWr)

  val exuWrBackDataVaild = io.wrBackInfo.exu.dataVaild

  val canRs1Bypass =
    Mux(isRs1ConflictEXU, exuWrBackDataVaild, isRs1ConflictWithWBU)
  val canRs2Bypass =
    Mux(isRs2ConflictEXU, exuWrBackDataVaild, isRs2ConflictWithWBU)

  val needStallForRs1 = (isRs1ConflictEXU || isRs1ConflictWithWBU) && (!canRs1Bypass)
  val needStallForRs2 = (isRs2ConflictEXU || isRs2ConflictWithWBU) && (!canRs2Bypass)

  val needStall = needStallForRs1 || needStallForRs2 || isConflictWithLSU

  val isStall = WireDefault(needStall)
  dontTouch(isStall)

  val r1UseBypass = canRs1Bypass
  val r2UseBypass = canRs2Bypass

  val r1BypassData = Mux(isRs1ConflictEXU, io.wrBackInfo.exu.data, io.wrBackInfo.wbu.data)
  val r2BypassData = Mux(isRs2ConflictEXU, io.wrBackInfo.exu.data, io.wrBackInfo.wbu.data)

  io.outData1 := Mux(r1UseBypass, r1BypassData, io.regData1)
  io.outData2 := Mux(r2UseBypass, r2BypassData, io.regData2)

  io.needStall := needStall
}

class IDU(
  implicit p: CPUParameters)
    extends Module {
  val io = IO(new Bundle {
    val in   = Flipped(Decoupled(new FetchedInst))
    val rvec = GPRegReqIO.ReadVecTX(2)
    val csrRead = CSRegReqIO.TX.SingleRead
    val csrJmpTarget = Input(new Bundle {
      val mepc  = Types.UWord
      val mtvec = Types.UWord
    })

    val flush = Input(Bool())

    val wrBackInfo = Input(new WrBackInfoGroup)

    val out = Decoupled(new DecodedInst)
  })

  dontTouch(io)

  // TODO: handle invalid instruction

  // val fsm = InnerBusCtrl(io.in, io.out, alwaysComb = true)

  io.out.bits.viewAsSupertype(new Inst) := io.in.bits.viewAsSupertype(new Inst)

  // alias
  val res  = io.out.bits.info
  val inst = io.in.bits.code

  res.viewAsSupertype(new InstMetaInfo) := InstInfoDecoder(inst(6, 0))

  res.rd  := inst(11, 7)
  res.rs1 := inst(19, 15)
  res.rs2 := inst(24, 20)

  val isTypStore     = InstType.hasSame(res.typ, InstType.store)
  val isTypBranch    = InstType.hasSame(res.typ, InstType.branch)
  val isTypFencei    = InstType.hasSame(res.typ, InstType.fencei)
  // for now, system inst, ecall and mret has rd == 0
  // TODO: handle rd != 0 case
  val isNoWrBackType = isTypStore || isTypBranch || isTypFencei
  res.rdWrEn := ~isNoWrBackType

  // io.rvec.en      := true.B
  io.rvec.addr(0) := res.rs1
  io.rvec.addr(1) := res.rs2
  io.csrRead.en   := io.in.valid
  io.csrRead.addr := inst(31, 20)

  val immI = Cat(Fill(21, inst(31)), inst(30, 20))
  val immS = Cat(immI(31, 5), inst(11, 8), inst(7))
  val immB = Cat(immI(31, 12), inst(7), immS(10, 1), 0.U(1.W))
  val immU = Cat(inst(31, 12), 0.U(12.W))
  val immJ = Cat(immI(31, 20), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W))
  val addrImm = Mux(isTypStore, immS, immI)

  val dontcareImm = Wire(Types.UWord)
  dontcareImm := DontCare
  res.imm := MuxLookup(res.fmt, dontcareImm)(
    Seq(
      InstFmt.imm    -> immI,
      InstFmt.jump   -> immJ,
      InstFmt.store  -> immS,
      InstFmt.branch -> immB,
      InstFmt.upper  -> immU
    )
  )

  val bypassMux = Module(new ByPassMux())
  bypassMux.io.rs1        := res.rs1
  bypassMux.io.rs2        := res.rs2
  bypassMux.io.regData1   := io.rvec.data(0)
  bypassMux.io.regData2   := io.rvec.data(1)
  bypassMux.io.wrBackInfo := io.wrBackInfo
  res.reg1                := bypassMux.io.outData1
  res.reg2                := bypassMux.io.outData2
  res.csrReadData         := io.csrRead.data

  val needStall = bypassMux.io.needStall

  res.snpc := io.in.bits.pc + 4.U
  res.pcAddImm := io.in.bits.pc + res.imm
  // Keep address generation off the generic fmt->imm path. reg1AddImm is
  // only consumed by load/store/JALR style address calculations in EXU.
  res.reg1AddImm := res.reg1 + addrImm

  res.isECall := inst === "h73".U
  res.isMRet  := inst === "h30200073".U
  res.csrJmpTarget := Mux(
    res.isECall,
    io.csrJmpTarget.mtvec,
    Mux(res.isMRet, io.csrJmpTarget.mepc, 0.U)
  )

  // Precompute branch comparisons on bypassed operands and let EXU
  // combine them with func3 to decide the final branch direction.
  res.isLessThan  := res.reg1.asSInt < res.reg2.asSInt
  res.isLessThanU := res.reg1 < res.reg2
  res.isEqual     := res.reg1 === res.reg2

  io.in.ready  := (io.out.ready && !needStall) || io.flush
  io.out.valid := io.in.valid && !needStall && !io.flush

  StageLogger(
    clock,
    StageLogConst.Event.stage,
    StageLogConst.Stage.idu,
    io.in.fire && !needStall && !io.flush,
    io.in.bits.iid
  )
}
