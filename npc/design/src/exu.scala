package cpu
import chisel3._
import chisel3.util._
import common_def._
import busfsm._

import regfile._
import cpu.alu._
import axi4._

class EXU extends Module {
  val io = IO(new Bundle {
    val in        = Flipped(Decoupled(new DecodedInst))
    val rvec      = GPRegReqIO.TX.VecRead(2)
    val csr_rvec  = CSRegReqIO.TX.SingleRead
    val jmpHappen = Output(Bool())
    val out       = Decoupled(new LSUInput)
  })

  val GARBAGE_UNINIT_VALUE = Wire(Types.UWord)
  GARBAGE_UNINIT_VALUE := DontCare

  val alu = Module(new ALU)

  alu.io.out.ready := io.out.ready
  alu.io.in.valid  := io.in.valid

  val alu_in = alu.io.in.bits
  val dinst  = io.in.bits
  val func3t = dinst.code(14, 12)
  val func7t = dinst.code(31, 25)

  val isFmtI = InstFmt.hasSame(dinst.info.fmt, InstFmt.imm)
  val isFmtB = InstFmt.hasSame(dinst.info.fmt, InstFmt.branch)

  val isTypSys        = InstType.hasSame(dinst.info.typ, InstType.system)
  val isTypLoad       = InstType.hasSame(dinst.info.typ, InstType.load)
  val isTypStore      = InstType.hasSame(dinst.info.typ, InstType.store)
  val isTypAUIPC      = InstType.hasSame(dinst.info.typ, InstType.auipc)
  val isTypJAL        = InstType.hasSame(dinst.info.typ, InstType.jal)
  val isTypJALR       = InstType.hasSame(dinst.info.typ, InstType.jalr)
  val isTypBranch     = InstType.hasSame(dinst.info.typ, InstType.branch)
  val isTypArithmetic = InstType.hasSame(dinst.info.typ, InstType.arithmetic)
  val isTypFencei     = InstType.hasSame(dinst.info.typ, InstType.fencei)

  alu_in.is_imm := isFmtI
  alu_in.func3t := Mux(isFmtB, func3t >> 1, func3t)
  alu_in.func7t := func7t

  val MS_fsm = Module(new OneMasterOneSlaveFSM)
  MS_fsm.connectMaster(io.in)
  MS_fsm.connectSlave(io.out)

  // reg

  io.rvec.en      := true.B
  io.rvec.addr(0) := dinst.info.rs1
  io.rvec.addr(1) := dinst.info.rs2
  val reg_v1 = io.rvec.data(0)
  val reg_v2 = io.rvec.data(1)

  // alu_in.src1 := reg_v1
  alu_in.src1 := reg_v1
  // when branch, src2 is reg_v2
  alu_in.src2 := Mux(isFmtI, dinst.info.imm, reg_v2)

  // lsu
  val lsuInfo = io.out.bits
  lsuInfo.destAddr  := reg_v1 + dinst.info.imm
  lsuInfo.isLoad    := isTypLoad
  lsuInfo.isStore   := isTypStore
  lsuInfo.func3t    := func3t
  lsuInfo.storeData := reg_v2
  val writeBackInfo = lsuInfo.exuWriteBack

  // csr

  val is_mret  = dinst.code === "h30200073".U
  val is_ecall = dinst.code === "h73".U

  writeBackInfo.csr_ecallflag := is_ecall

  val csrren    = io.csr_rvec.en
  val csr_raddr = io.csr_rvec.addr
  val csr_rdata = io.csr_rvec.data

  val csrwen    = writeBackInfo.csr.en
  val csr_waddr = writeBackInfo.csr.addr
  val csr_wdata = writeBackInfo.csr.data

  object CSROp {
    val csrrw = 1.U
    val csrrs = 2.U
  }

  when(isTypSys) {
    when(is_ecall) {
      csrren    := true.B
      csrwen    := false.B
      csr_waddr := CSRAddr.mepc
      csr_raddr := CSRAddr.mtvec
      // ecall: set mepc to pc
      // although wen = falase
      // is_ecall flag make csr to write wdata to mepc
      csr_wdata := dinst.pc
    }.elsewhen(is_mret) {
      csrren    := true.B
      csrwen    := false.B
      csr_raddr := CSRAddr.mepc
      csr_waddr := 0.U
      csr_wdata := 0.U
    }.otherwise {
      csrren    := MuxLookup(func3t, false.B)(
        Seq(
          CSROp.csrrw -> (dinst.info.rd =/= 0.U),
          CSROp.csrrs -> true.B
        )
      )
      csrwen    := MuxLookup(func3t, false.B)(
        Seq(
          CSROp.csrrw -> true.B,
          CSROp.csrrs -> (reg_v1 =/= 0.U)
        )
      )
      csr_raddr := dinst.code(31, 20)
      csr_waddr := csr_raddr
      csr_wdata := Mux1H(
        Seq(
          (func3t === CSROp.csrrw) -> reg_v1,
          (func3t === CSROp.csrrs) -> (csr_rdata | reg_v1)
        )
      )
    }
  }.otherwise {
    csrren    := false.B
    csrwen    := false.B
    csr_raddr := DontCare
    csr_waddr := DontCare
    csr_wdata := DontCare
  }

  MS_fsm.io.self_finished := alu.io.out.valid

  // wdata

  val nxt_pc   = Wire(Types.UWord)
  // need pc+imm:
  // auipc, jal(r), branch
  val pcAddImm = dinst.pc + dinst.info.imm
  val snpc     = dinst.pc + 4.U

  val isNoWrBackType = isTypStore || isTypBranch || isTypFencei

  // for now, system inst, ecall and mret has rd == 0
  // TODO: handle rd != 0 case
  writeBackInfo.gpr.en := (~isNoWrBackType)

  writeBackInfo.gpr.addr := dinst.info.rd
  val sysInstWrBackData = csr_rdata
  val gprDataMapping    = Seq(
    InstType.arithmetic -> alu.io.out.bits,
    InstType.lui        -> dinst.info.imm,
    InstType.auipc      -> pcAddImm,
    InstType.jalr       -> snpc,
    InstType.jal        -> snpc,
    InstType.load       -> GARBAGE_UNINIT_VALUE, // load data will be from lsu
    InstType.system     -> sysInstWrBackData,
    InstType.fencei     -> GARBAGE_UNINIT_VALUE
  )

  // io.out.bits.gpr.data := MuxLookup(dinst.info.typ, GARBAGE_UNINIT_VALUE)(gprDataMapping)
  writeBackInfo.gpr.data := Mux1H(
    gprDataMapping.map { case (typ, data) =>
      InstType.hasSame(dinst.info.typ, typ) -> data
    }
  )

  // nxt_pc
  val takeBranch = WireDefault(false.B)

  writeBackInfo.pc     := dinst.pc
  writeBackInfo.nxt_pc := nxt_pc
  writeBackInfo.is_ebreak := (dinst.code === "h00100073".U)

  // TODO: handle exception
  io.jmpHappen := takeBranch || isTypJALR || isTypJAL

  when(is_ecall || is_mret) {
    nxt_pc := csr_rdata
  }.otherwise {
    when(isTypJALR) {
      val r1AddImm = reg_v1 + dinst.info.imm
      nxt_pc := r1AddImm(31, 1) ## 0.U(1.W)
    }.elsewhen(isTypJAL) {
      nxt_pc := pcAddImm
    }.elsewhen(isFmtB) {
      //
      // reuse alu
      // branch func3t
      //
      // blt/bge 10x -> feed alu 010 -> slt
      // bltu/bgeu 11x -> feed alu 011 -> sltu
      //
      // only when func3t[2] == 0 -> eq/ne
      //
      val isLessThan = alu.io.out.bits(0)
      val branchCalc = Mux(func3t(2), isLessThan, (reg_v1 === reg_v2))
      takeBranch := Mux(func3t(0), ~branchCalc, branchCalc)

      nxt_pc := Mux(takeBranch, pcAddImm, snpc)
      // when(!BranchOp.isValidBranchOp(func3t)) {
      //   printf("(exu) UNKNOWN BRANCH func3t %d\n", func3t)
      // }
    }.otherwise {
      nxt_pc := snpc
    }
  }
}
