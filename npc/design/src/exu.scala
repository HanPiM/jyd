package cpu
import chisel3._
import chisel3.util._
import common_def._
import busfsm._

import regfile._
import cpu.alu._
import axi4._
import dpiwrap._
import dpiwrap.ClockedCallVoidDPIC

class EXU(implicit p:CPUParameters) extends Module {
  val io = IO(new Bundle {
    val in        = Flipped(Decoupled(new DecodedInst))
    val jmpHappen = Output(Bool())
    val isJAL     = Output(Bool())

    val predWrong = Output(Bool())

    val branchTarget = Output(Types.UWord)
    val branchBackward = Output(Bool())

    val pc        = Output(Types.UWord)
    val nxtPC     = Output(Types.UWord)

    val fwd = Output(new WrBackForwardInfo)

    val memReq = Decoupled(new MemReq)
    val out    = Decoupled(new LSUInput)
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

  val reg_v1     = dinst.info.reg1
  val reg_v2     = dinst.info.reg2
  // val reg1AddImm = reg_v1 + dinst.info.imm
  // val pcAddImm   = dinst.pc + dinst.info.imm
  val pcAddImm   = dinst.info.pcAddImm
  val reg1AddImm = dinst.info.reg1AddImm

  io.branchTarget := pcAddImm
  io.branchBackward := dinst.info.imm(31)

  alu_in.src1   := reg_v1
  // alu_in.src2   := Mux(isFmtI, dinst.info.imm, reg_v2)
  alu_in.src2 := reg_v2
  alu_in.is_imm := isFmtI
  alu_in.func3t := func3t
  alu_in.func7t := func7t

  val aluOut = alu.io.out.bits

  // --- CSR ---
  val is_mret  = dinst.info.isMRet
  val is_ecall = dinst.info.isECall

  val csr_raddr = dinst.code(31, 20)
  val csr_rdata = dinst.info.csrReadData

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

  csrwen := isCSRRW || (isCSRRS && (reg_v1 =/= 0.U))

  when(isTypSys) {
    when(is_ecall) {
      csr_waddr := CSRAddr.mepc
      // ecall: set mepc to pc
      // !!!note:
      // although wen = false
      // is_ecall flag makes csr to write wdata to mepc
      csr_wdata := dinst.pc
    }.elsewhen(is_mret) {
      csr_waddr := DontCare
      csr_wdata := DontCare
    }.otherwise {
      csr_waddr := csr_raddr
      csr_wdata := Mux(
        isCSRRW,
        reg_v1,
        (csr_rdata | reg_v1)
      )
    }
  }.otherwise {
    csr_waddr := DontCare
    csr_wdata := DontCare
  }

  writeBackInfo.csr_ecallflag := is_ecall

  // --- Inst type decode ---
  val isTypLoad       = InstType.hasSame(dinst.info.typ, InstType.load)
  val isTypStore      = InstType.hasSame(dinst.info.typ, InstType.store)
  val isTypAUIPC      = InstType.hasSame(dinst.info.typ, InstType.auipc)
  val isTypJAL        = InstType.hasSame(dinst.info.typ, InstType.jal)
  val isTypJALR       = InstType.hasSame(dinst.info.typ, InstType.jalr)
  val isTypBranch     = InstType.hasSame(dinst.info.typ, InstType.branch)
  val isTypArithmetic = InstType.hasSame(dinst.info.typ, InstType.arithmetic)
  val isTypLUI        = InstType.hasSame(dinst.info.typ, InstType.lui)
  val isExtMemReq     = isTypLoad || isTypStore
  val memReqFire      = io.memReq.valid && io.memReq.ready

  val isFmtB          = InstFmt.hasSame(dinst.info.fmt, InstFmt.branch)

  val isEqual = reg_v1 === reg_v2
  val isLessThan = reg_v1.asSInt < reg_v2.asSInt
  val isLessThanU = reg_v1 < reg_v2

  // val isEqual = dinst.info.isEqual
  // val isLessThan = dinst.info.isLessThan
  // val isLessThanU = dinst.info.isLessThanU

  val takeBranch      = MuxLookup(func3t, false.B)(
    Seq(
      "b000".U ->  isEqual,
      "b001".U -> !isEqual,
      "b100".U ->  isLessThan,
      "b101".U -> !isLessThan,
      "b110".U ->  isLessThanU,
      "b111".U -> !isLessThanU
    )
  )

  // --- LSU input ---
  val lsuInfo = io.out.bits
  lsuInfo.destAddr  := reg1AddImm
  lsuInfo.isLoad    := isTypLoad
  lsuInfo.isStore   := isTypStore
  lsuInfo.func3t    := dinst.code(14, 12)
  lsuInfo.storeData := dinst.info.reg2

  val snpc = dinst.info.staticNextPCOrCSRTarget

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

  // Fill in LSU stage
  writeBackInfo.isLoad        := false.B
  writeBackInfo.lsuResult     := 0.U
  writeBackInfo.lsuFunc3t     := 0.U
  writeBackInfo.lsuAddrOffset := 0.U

  val isMemOP = isTypLoad || isTypStore
  io.fwd := WrBackForwardInfo(io.in.valid, dinst, ~isMemOP, writeBackInfo.gpr.data)
  val memOpIsWord    = func3t(1)
  val memOpIsHalf    = (~func3t(1)) && func3t(0)
  val memOpIsByte    = (~func3t(1)) && (~func3t(0))
  val wByteMask = MuxLookup(reg1AddImm(1, 0), 0.U(4.W))(
    Seq(
      0.U -> "b0001".U(4.W),
      1.U -> "b0010".U(4.W),
      2.U -> "b0100".U(4.W),
      3.U -> "b1000".U(4.W)
    )
  )
  val wByteMaskHalf = MuxLookup(reg1AddImm(1, 0), 0.U(4.W))(
    Seq(
      0.U -> "b0011".U(4.W),
      1.U -> "b0110".U(4.W),
      2.U -> "b1100".U(4.W)
    )
  )
  val memWMask = Mux1H(
    Seq(
      memOpIsByte -> wByteMask,
      memOpIsHalf -> wByteMaskHalf,
      memOpIsWord -> "b1111".U(4.W)
    )
  )
  val memWData = MuxLookup(reg1AddImm(1, 0), 0.U(32.W))(
    Seq(
      0.U -> reg_v2,
      1.U -> Cat(reg_v2(23, 0), 0.U(8.W)),
      2.U -> Cat(reg_v2(15, 0), 0.U(16.W)),
      3.U -> Cat(reg_v2(7, 0), 0.U(24.W))
    )
  )

  io.memReq.valid      := isExtMemReq && io.in.valid && io.out.ready
  io.memReq.bits.addr  := reg1AddImm
  io.memReq.bits.size  := func3t(1, 0)
  io.memReq.bits.wen   := isTypStore
  io.memReq.bits.wdata := memWData
  io.memReq.bits.wmask := memWMask

  io.in.ready  := memReqFire || (io.out.ready && !isExtMemReq)
  io.out.valid := (io.in.valid && !isExtMemReq) || memReqFire

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
  nxtPC    := normalNxtPC
  io.nxtPC := nxtPC
  io.pc    := dinst.pc

  io.jmpHappen := willJmp
  io.isJAL     := isTypJAL
  // io.predWrong := (normalNxtPC =/= dinst.pred.pc) || isJmpCsr
  io.predWrong := isTypJALR || isJmpCsr || (isFmtB && (takeBranch ^ dinst.pred.take)) || (isTypJAL && (~dinst.pred.hit))

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
