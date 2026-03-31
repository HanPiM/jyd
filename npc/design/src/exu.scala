package cpu
import chisel3._
import chisel3.util._
import common_def._
import busfsm._

import regfile._
import cpu.alu._
import axi4._
import dpiwrap._

class EXU(implicit p:CPUParameters) extends Module {
  val io = IO(new Bundle {
    val in        = Flipped(Decoupled(new DecodedInst))
    val csr_rvec  = CSRegReqIO.TX.SingleRead
    val jmpHappen = Output(Bool())
    val isJAL     = Output(Bool())

    val predWrong = Output(Bool())
    val pc        = Output(Types.UWord)
    val nxtPC     = Output(Types.UWord)

    val fencei = Output(Bool())

    val fwd = Output(new WrBackForwardInfo)

    val out = Decoupled(new LSUInput)
  })

  val alu = Module(new ALU)

  alu.io.out.ready := io.out.ready
  alu.io.in.valid  := io.in.valid

  val alu_in = alu.io.in.bits
  val dinst  = io.in.bits
  val func3t = dinst.code(14, 12)
  val func7t = dinst.code(31, 25)

  val isFmtI   = InstFmt.hasSame(dinst.info.fmt, InstFmt.imm)
  val isTypSys = InstType.hasSame(dinst.info.typ, InstType.system)

  io.in.ready  := io.out.ready
  io.out.valid := io.in.valid

  val pcAddImm   = dinst.pc + dinst.info.imm
  val reg_v1     = dinst.info.reg1
  val reg_v2     = dinst.info.reg2
  val reg1AddImm = reg_v1 + dinst.info.imm

  alu_in.src1   := reg_v1
  alu_in.src2   := Mux(isFmtI, dinst.info.imm, reg_v2)
  alu_in.is_imm := isFmtI
  alu_in.func3t := func3t
  alu_in.func7t := func7t

  val aluOut = alu.io.out.bits

  // --- CSR ---
  val is_mret  = dinst.code === "h30200073".U
  val is_ecall = dinst.code === "h73".U

  val csrren    = io.csr_rvec.en
  val csr_raddr = io.csr_rvec.addr
  val csr_rdata = io.csr_rvec.data

  val writeBackInfo = io.out.bits.exuWriteBack
  val csrwen    = writeBackInfo.csr.en
  val csr_waddr = writeBackInfo.csr.addr
  val csr_wdata = writeBackInfo.csr.data

  object CSROp {
    val csrrw = 1.U
    val csrrs = 2.U
  }

  val isCSRRW = (func3t === CSROp.csrrw) && isTypSys
  val isCSRRS = (func3t === CSROp.csrrs) && isTypSys

  csrren := isCSRRS || (isCSRRW && (dinst.info.rd =/= 0.U)) || is_ecall || is_mret
  csrwen := isCSRRW || (isCSRRS && (reg_v1 =/= 0.U))

  when(isTypSys) {
    when(is_ecall) {
      csr_waddr := CSRAddr.mepc
      csr_raddr := CSRAddr.mtvec
      // ecall: set mepc to pc
      // !!!note:
      // although wen = false
      // is_ecall flag makes csr to write wdata to mepc
      csr_wdata := dinst.pc
    }.elsewhen(is_mret) {
      csr_raddr := CSRAddr.mepc
      csr_waddr := DontCare
      csr_wdata := DontCare
    }.otherwise {
      csr_raddr := dinst.code(31, 20)
      csr_waddr := csr_raddr
      csr_wdata := Mux(
        isCSRRW,
        reg_v1,
        (csr_rdata | reg_v1)
      )
    }
  }.otherwise {
    csr_raddr := DontCare
    csr_waddr := DontCare
    csr_wdata := DontCare
  }

  writeBackInfo.csr_ecallflag := is_ecall

  // --- Branch ---
  // blt/bge 10x
  // bltu/bgeu 11x
  //
  // only when func3t[2] == 0 -> eq/ne
  val isLessThanU = reg_v1 < reg_v2
  val isLessThanS = (reg_v1.asSInt < reg_v2.asSInt)
  val isLessThan  = Mux(func3t(1), isLessThanU, isLessThanS)
  val branchCalc  = Mux(func3t(2), isLessThan, (reg_v1 === reg_v2))
  val takeBranch  = Mux(func3t(0), ~branchCalc, branchCalc)

  // --- Inst type decode ---
  val isTypLoad       = InstType.hasSame(dinst.info.typ, InstType.load)
  val isTypStore      = InstType.hasSame(dinst.info.typ, InstType.store)
  val isTypAUIPC      = InstType.hasSame(dinst.info.typ, InstType.auipc)
  val isTypJAL        = InstType.hasSame(dinst.info.typ, InstType.jal)
  val isTypJALR       = InstType.hasSame(dinst.info.typ, InstType.jalr)
  val isTypBranch     = InstType.hasSame(dinst.info.typ, InstType.branch)
  val isTypArithmetic = InstType.hasSame(dinst.info.typ, InstType.arithmetic)
  val isTypLUI        = InstType.hasSame(dinst.info.typ, InstType.lui)
  val isFenceI        = InstType.hasSame(dinst.info.typ, InstType.fencei)
  val isFmtB          = InstFmt.hasSame(dinst.info.fmt, InstFmt.branch)

  // --- LSU input ---
  val lsuInfo = io.out.bits
  lsuInfo.destAddr  := reg1AddImm
  lsuInfo.isLoad    := isTypLoad
  lsuInfo.isStore   := isTypStore
  lsuInfo.func3t    := dinst.code(14, 12)
  lsuInfo.storeData := dinst.info.reg2

  val snpc = dinst.info.snpc

  writeBackInfo.gpr.en   := dinst.info.rdWrEn
  writeBackInfo.gpr.addr := dinst.info.rd

  writeBackInfo.gpr.data := Mux1H(
    Seq(
      isTypArithmetic         -> aluOut,
      isTypLUI                -> dinst.info.imm,
      isTypAUIPC              -> pcAddImm,
      (isTypJALR || isTypJAL) -> snpc,
      isTypSys                -> csr_rdata
    )
  )

  val isMemOP = isTypLoad || isTypStore
  io.fwd := WrBackForwardInfo(io.in.valid, dinst, ~isMemOP, writeBackInfo.gpr.data)

  writeBackInfo.iid := dinst.iid

  // --- Next PC ---
  val isJmpCsr = is_ecall || is_mret
  val willJmp  = (isTypBranch && takeBranch) || isTypJALR || isTypJAL || isJmpCsr

  val normalNxtPC = Wire(Types.UWord)
  val nxtPC       = Wire(Types.UWord)

  normalNxtPC := Mux(
    isTypJALR,
    (reg1AddImm(31, 1) ## 0.U(1.W)),
    Mux(
      isTypJAL || (isFmtB && takeBranch),
      pcAddImm,
      snpc
    )
  )
  nxtPC    := Mux(isJmpCsr, csr_rdata, normalNxtPC)
  io.nxtPC := nxtPC
  io.pc    := dinst.pc

  io.jmpHappen := willJmp
  io.isJAL     := isTypJAL
  io.fencei    := isFenceI && io.in.valid
  io.predWrong := (normalNxtPC =/= dinst.predictedNextPC) || isJmpCsr || isFenceI

  StageLogger(
    clock,
    StageLogConst.Event.stage,
    StageLogConst.Stage.exu,
    io.in.fire,
    dinst.iid
  )

  val dbgIsBranch = WireDefault(isTypBranch)
  val dbgIsJALR   = WireDefault(isTypJALR)
  val dbgIsJAL    = WireDefault(isTypJAL)
  val dbgIsCSRJmp = WireDefault(isJmpCsr)
  dontTouch(dbgIsBranch)
  dontTouch(dbgIsJALR)
  dontTouch(dbgIsJAL)
  dontTouch(dbgIsCSRJmp)
}

class EXUForDifftest(implicit p:CPUParameters) extends Module {
  val io = IO(new Bundle {
    val in     = Flipped(Decoupled(new DecodedInst))
    val actual = new Bundle {
      val inReady  = Input(Bool())
      val pc       = Input(Types.UWord)
      val nxtPC    = Input(Types.UWord)
      val memAddr  = Input(Types.UWord)
      val outValid = Input(Bool())
    }
    val out    = Decoupled(new LSUInputForDifftest)
  })
  io.in.ready := io.actual.inReady
  io.out.valid := io.actual.outValid

  val outInfo = io.out.bits
  outInfo.isLoad   := InstType.hasSame(io.in.bits.info.typ, InstType.load)
  outInfo.isStore  := InstType.hasSame(io.in.bits.info.typ, InstType.store)
  outInfo.pc       := io.actual.pc
  outInfo.nxtPC    := io.actual.nxtPC
  outInfo.isEBreak := io.in.bits.code === "h00100073".U
  outInfo.destAddr := io.actual.memAddr
}
