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

object GenMemWMaskTrivialRef {
  def apply(offset: UInt, func3t: UInt): UInt = {
    val memOpIsWord     = func3t(1)
    val memOpIsHalf     = (~func3t(1)) && func3t(0)
    val memOpIsByte     = (~func3t(1)) && (~func3t(0))
    val wByteMask       = MuxLookup(offset(1, 0), 0.U(4.W))(
      Seq(
        0.U -> "b0001".U(4.W),
        1.U -> "b0010".U(4.W),
        2.U -> "b0100".U(4.W),
        3.U -> "b1000".U(4.W)
      )
    )
    // half word must be aligned to 2 bytes, so only two cases
    val wByteMaskHalf   = Mux(offset(1), "b1100".U(4.W), "b0011".U(4.W))
    // val wByteMaskHalf = MuxLookup(reg1AddImm(1, 0), 0.U(4.W))(
    //   Seq(
    //     0.U -> "b0011".U(4.W),
    //     1.U -> "b0110".U(4.W),
    //     2.U -> "b1100".U(4.W)
    //   )
    // )
    val memWMaskCorrect = Mux1H(
      Seq(
        memOpIsByte -> wByteMask,
        memOpIsHalf -> wByteMaskHalf,
        memOpIsWord -> "b1111".U(4.W)
      )
    )
    memWMaskCorrect
  }
}

object GenMemWMask {
  def apply(offset: UInt, func3t: UInt): UInt = {
    val memOpIsWord = func3t(1)
    val memOpIsHalf = (~func3t(1)) && func3t(0)
    val memOpIsByte = (~func3t(1)) && (~func3t(0))

    val isLW = memOpIsWord

    // lw : always
    // lh : when offset==0, is lo half
    // lb : when offset==0, is b[0]
    val memWMaskB0 = (offset(1, 0) === 0.U)
    // lh : when offset==0
    // lb : when offset==1
    //
    // offset can be 0 or 1
    val memWMaskB1 = (~offset(1) && Mux(memOpIsByte, offset(0), ~offset(0)))

    // lh : when offset==2, is hi half
    // lb : when offset==2
    val memWMaskB2 = (offset(1, 0) === 2.U)

    // lh : when offset==2
    // lb : when offset==3
    // offset can be 2 or 3
    val memWMaskB3 = (offset(1) && Mux(memOpIsByte, offset(0), ~offset(0)))

    Cat(memWMaskB3, memWMaskB2, memWMaskB1, memWMaskB0) | Fill(4, isLW)
  }
}

object GenMemWData {
  def apply(offset: UInt, data: UInt): UInt = {
    val memWData = MuxLookup(offset(1, 0), 0.U(32.W))(
      Seq(
        0.U -> data,
        // only byte align case can store to odd byte
        // so only need to shift lo 8 bits
        1.U -> 0.U(16.W) ## data(7, 0) ## 0.U(8.W),
        2.U -> Cat(data(15, 0), 0.U(16.W)),
        3.U -> Cat(data(7, 0), 0.U(24.W))
      )
    )
    memWData
  }
}

class EXU(
  implicit p: CPUParameters)
    extends Module {
  val io = IO(new Bundle {
    val in          = Flipped(Decoupled(new DecodedInst))
    val jmpHappen   = Output(Bool())
    val isJAL       = Output(Bool())
    val isBranch    = Output(Bool())
    val branchTaken = Output(Bool())
    val btbUpdateEn = Output(Bool())

    val predWrong = Output(Bool())

    val branchTarget   = Output(Types.UWord)
    val branchBackward = Output(Bool())

    val pc    = Output(Types.UWord)
    val nxtPC = Output(Types.UWord)

    val fwd = Output(new WrBackForwardInfo)
    val addFwd = Output(new AddForwardInfo)
    val lateLoadProducer = Output(new LateLoadProducerInfo)
    val lateLoadLSU = Input(new LateLoadSourceInfo)
    val lateLoadWBU = Input(new LateLoadSourceInfo)
    val lateAddFwd = Output(new LateAddForwardInfo)

    val dcache = new Bundle {
      val hit        = Input(Bool())
      val lateReadData = Input(Types.UWord)
      val storeEpoch = Input(UInt(8.W))
      val queryAddr  = Output(Types.UWord)
      val invalidate = Output(Bool())
      val storeUpdate = Output(Bool())
      val storeData   = Output(Types.UWord)
      val storeMask   = Output(UInt(4.W))
    }

    val memReq = Decoupled(new MemReq)
    val out    = Decoupled(new LSUInput)
  })

  val alu = Module(new ALU)

  alu.io.out.ready := io.out.ready

  val alu_in = alu.io.in.bits
  val dinst  = io.in.bits
  val func3t = dinst.code(14, 12)
  val func7t = dinst.code(31, 25)

  val isFmtI          = InstFmt.hasSame(dinst.info.fmt, InstFmt.imm)
  val isTypSys        = InstType.hasSame(dinst.info.typ, InstType.system)
  val isTypLoad       = InstType.hasSame(dinst.info.typ, InstType.load)
  val isTypStore      = InstType.hasSame(dinst.info.typ, InstType.store)
  val isTypAUIPC      = InstType.hasSame(dinst.info.typ, InstType.auipc)
  val isTypJAL        = InstType.hasSame(dinst.info.typ, InstType.jal)
  val isTypJALR       = InstType.hasSame(dinst.info.typ, InstType.jalr)
  val isTypBranch     = InstType.hasSame(dinst.info.typ, InstType.branch)
  val isTypArithmetic = InstType.hasSame(dinst.info.typ, InstType.arithmetic)
  val isTypLUI        = InstType.hasSame(dinst.info.typ, InstType.lui)

  val isAdd = isTypArithmetic && func3t === 0.U && (isFmtI || func7t === 0.U)

  alu.io.in.valid := io.in.valid && isTypArithmetic

  // A late-load operand first looks at LSU. This priority is required when an
  // older WBU instruction happens to target the same register. A miss keeps
  // the LSU match selected but not ready; the existing IDU/EXU payload
  // register then holds the consumer until its producer reaches WBU.
  def resolveLateLoadOperand(late: Bool, normalData: UInt): (Bool, UInt) = {
    val lsuMatch = late && io.lateLoadLSU.valid
    val wbuMatch = late && !lsuMatch && io.lateLoadWBU.valid
    val ready = !late || (lsuMatch && io.lateLoadLSU.dataValid) || (wbuMatch && io.lateLoadWBU.dataValid)
    val data = Mux(lsuMatch, io.lateLoadLSU.data, Mux(wbuMatch, io.lateLoadWBU.data, normalData))
    (ready, data)
  }

  val (lateRs1Ready, lateRegV1) =
    resolveLateLoadOperand(dinst.info.lateLoadRs1, dinst.info.reg1)
  val (lateRs2Ready, lateRegV2) =
    resolveLateLoadOperand(dinst.info.lateLoadRs2, dinst.info.reg2)
  val hasLateLoadOperand = dinst.info.lateLoadRs1 || dinst.info.lateLoadRs2
  val lateDataReady = lateRs1Ready && lateRs2Ready

  // Keep late load data out of the generic ALU and every control/address
  // path. IDU marks only ADD/ADDI, which uses this compact duplicate adder.
  val lateAddResult = lateRegV1 + lateRegV2
  val reg_v1       = dinst.info.reg1
  val reg_v2       = dinst.info.reg2
  // val pcAddImm   = dinst.pc + dinst.info.imm
  val pcAddImm   = dinst.info.pcAddImm
  val reg1AddImm = dinst.info.reg1AddImm

  io.branchTarget   := pcAddImm
  io.branchBackward := dinst.info.imm(31)

  alu_in.src1   := reg_v1
  // alu_in.src2   := Mux(isFmtI, dinst.info.imm, reg_v2)
  alu_in.src2   := reg_v2
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

  val csrWrEnable = writeBackInfo.csr.en
  val csrWrAddr   = writeBackInfo.csr.addr
  val csrWrData   = writeBackInfo.csr.data

  object CSROp {
    val RW = 1.U
    val RS = 2.U
    val RC = 3.U
  }

  val csrUIMM = dinst.code(19, 15).pad(32)

  // val isCSRRW = (func3t === CSROp.RW) && isTypSys
  // val isCSRRS = (func3t === CSROp.RS) && isTypSys

  csrWrEnable := isTypSys && func3t(1, 0) =/= 0.U

  when(isTypSys) {

    val isRW = func3t(1, 0) === CSROp.RW
    val isRS = func3t(1, 0) === CSROp.RS
    val isRC = func3t(1, 0) === CSROp.RC

    val csrOpMask = Mux(func3t(2), csrUIMM, reg_v1)

    when(is_ecall) {
      csrWrAddr := CSRAddr.mepc
      // ecall: set mepc to pc
      // !!!note:
      // although wen = false
      // is_ecall flag makes csr to write wdata to mepc
      csrWrData := dinst.pc
    }.otherwise {
      csrWrAddr := csr_raddr
      csrWrData := Mux(
        isRC,
        csr_rdata & (~csrOpMask),
        Mux(isRS, csr_rdata | csrOpMask, csrOpMask)
      )
    }
  }.otherwise {
    csrWrAddr := DontCare
    csrWrData := DontCare
  }

  writeBackInfo.csr_ecallflag := is_ecall

  // --- Inst type decode ---
  val needMemReq = isTypLoad || isTypStore
  val memReqFire = io.memReq.valid && io.memReq.ready

  val isFmtB = InstFmt.hasSame(dinst.info.fmt, InstFmt.branch)

  val isEqual     = reg_v1 === reg_v2
  val isLessThan  = reg_v1.asSInt < reg_v2.asSInt
  val isLessThanU = reg_v1 < reg_v2

  // val isEqual = dinst.info.isEqual
  // val isLessThan = dinst.info.isLessThan
  // val isLessThanU = dinst.info.isLessThanU

  val takeBranch = Mux1H(
    Seq(
      dinst.info.is_beq  -> isEqual,
      dinst.info.is_bne  -> !isEqual,
      dinst.info.is_blt  -> isLessThan,
      dinst.info.is_bge  -> !isLessThan,
      dinst.info.is_bltu -> isLessThanU,
      dinst.info.is_bgeu -> !isLessThanU
    )
  )

  // --- LSU input ---
  val lsuInfo = io.out.bits
  lsuInfo.destAddr  := reg1AddImm
  lsuInfo.isLoad    := isTypLoad
  lsuInfo.isStore   := isTypStore
  lsuInfo.func3t    := dinst.code(14, 12)
  lsuInfo.cacheableLw := isTypLoad && func3t === "b010".U && reg1AddImm(1, 0) === 0.U &&
    reg1AddImm(21, 20) === "b01".U
  lsuInfo.dcacheHit := lsuInfo.cacheableLw && io.dcache.hit
  // The distributed-memory lookup is asynchronous in C0, but this field is
  // part of the existing EXU-to-LSU payload register.  A miss ignores it and
  // retains the normal WBU/memory-response path.
  lsuInfo.lateLoadData := io.dcache.lateReadData
  lsuInfo.dcacheStoreEpoch := io.dcache.storeEpoch

  val snpc = dinst.info.staticNextPCOrCSRTarget

  writeBackInfo.gpr.en   := dinst.info.rdWrEn
  writeBackInfo.gpr.addr := dinst.info.rd

  // writeBackInfo.gpr.data := Mux1H(
  //   Seq(
  //     isTypArithmetic         -> aluOut,
  //     isTypLUI                -> dinst.info.imm,
  //     isTypAUIPC              -> pcAddImm,
  //     (isTypJALR || isTypJAL) -> snpc,
  //     isTypSys                -> csr_rdata
  //   )
  // )

  val arithmeticResult = Mux(hasLateLoadOperand, lateAddResult, aluOut)
  writeBackInfo.gpr.data := Mux(isTypArithmetic, arithmeticResult, dinst.info.preMuxWrBackData)

  // Fill in LSU stage
  writeBackInfo.isLoad        := false.B
  writeBackInfo.isMemOp       := false.B
  writeBackInfo.lsuResult     := 0.U
  writeBackInfo.lsuFunc3t     := 0.U
  writeBackInfo.lsuAddrOffset := 0.U
  writeBackInfo.memAddr       := 0.U
  writeBackInfo.cacheableLw   := false.B
  writeBackInfo.dcacheHit     := false.B
  writeBackInfo.dcacheStoreEpoch := 0.U

  val isMemOP        = isTypLoad || isTypStore
  val exuResultValid = !isTypArithmetic || (alu.io.out.valid && (!hasLateLoadOperand || lateDataReady))
  // Every ordinary single-cycle ALU instruction can bypass the M/D output
  // mux. Multi-cycle M-extension results retain the generic result path.
  val isMExt = !isFmtI && func7t(0)
  val useSingleCycleForward = isTypArithmetic && !isMExt && !hasLateLoadOperand
  val fastForwardData = Mux(useSingleCycleForward, alu.io.singleCycleResult, writeBackInfo.gpr.data)
  // Keep lateAddResult out of the generic M/D forwarding mux. Its compact
  // dedicated channel preserves same-cycle forwarding without another rd
  // comparison; sequential single issue supplies producer identity.
  val exuForwardDataValid = !isMemOP && exuResultValid && !hasLateLoadOperand
  io.fwd := WrBackForwardInfo(io.in.valid, dinst, exuForwardDataValid, fastForwardData, csrWrEnable)
  io.lateAddFwd.valid :=
    io.in.valid && dinst.info.rdWrEn && dinst.info.rd =/= 0.U && hasLateLoadOperand && exuResultValid
  io.lateAddFwd.data := lateAddResult
  // A held late-load ADD must remain visible through generic forwarding so
  // dependent consumers stall, but it must never drive the special address-
  // generation bypass.  Keeping data fixed at the normal ALU output also
  // removes lateAddResult from the IDU AGEN cone.
  io.addFwd.valid :=
    io.in.valid && dinst.info.rdWrEn && dinst.info.rd =/= 0.U && isAdd && !hasLateLoadOperand
  io.addFwd.addr  := dinst.info.rd
  io.addFwd.data  := alu.io.addResult

  // The producer token is decode-only.  In particular, do not feed the
  // current load address/cacheability back into IDU ready; cache hit only
  // decides whether the already-issued consumer completes in the next cycle.
  io.lateLoadProducer.valid := io.in.valid && isTypLoad && func3t === "b010".U

  val memWMask = GenMemWMask(reg1AddImm(1, 0), func3t)

  when(io.memReq.valid) {
    val memWMaskCorrect = GenMemWMaskTrivialRef(reg1AddImm(1, 0), func3t)
    when(memWMask =/= memWMaskCorrect) {
      printf(p"reg1AddImm: ${reg1AddImm}, func3t: ${func3t}\n")
      printf(p"memWMask: ${memWMask}, correct: ${memWMaskCorrect}\n")
      stop()
    }
  }

  val memWData = GenMemWData(reg1AddImm(1, 0), reg_v2)

  io.dcache.queryAddr  := reg1AddImm
  val cacheableStore = isTypStore && reg1AddImm(21, 20) === "b01".U
  val cacheableStoreFire = memReqFire && cacheableStore
  val fullWordStore = memWMask === "b1111".U
  io.dcache.invalidate := cacheableStoreFire && !fullWordStore
  io.dcache.storeUpdate := cacheableStoreFire && fullWordStore
  io.dcache.storeData   := memWData
  io.dcache.storeMask   := memWMask

  io.memReq.valid      := needMemReq && io.in.valid && io.out.ready
  io.memReq.bits.addr  := reg1AddImm
  io.memReq.bits.size  := func3t(1, 0)
  io.memReq.bits.wen   := isTypStore
  io.memReq.bits.wdata := memWData
  io.memReq.bits.wmask := memWMask

  io.in.ready  := memReqFire || (
    io.out.ready && !needMemReq && exuResultValid
  )
  io.out.valid := memReqFire || (
    io.in.valid && !needMemReq && exuResultValid
  )

  writeBackInfo.iid := dinst.iid

  // --- Next PC ---
  val isJmpCsr = is_ecall || is_mret
  val willJmp  = (isTypBranch && takeBranch) || isTypJALR || isTypJAL || isJmpCsr

  val normalNxtPC = Wire(Types.UWord)
  val nxtPC       = Wire(Types.UWord)

  normalNxtPC := TrimmedPC.expand(
    Mux(
      isTypJALR,
      TrimmedPC.trim(reg1AddImm),
      Mux(
        isTypJAL || (isFmtB && takeBranch),
        TrimmedPC.trim(pcAddImm),
        TrimmedPC.trim(snpc)
      )
    )
  )
  nxtPC       := normalNxtPC
  io.nxtPC    := nxtPC
  io.pc       := dinst.pc

  io.jmpHappen   := willJmp
  io.isJAL       := isTypJAL
  io.isBranch    := isTypBranch
  io.branchTaken := takeBranch
  io.btbUpdateEn := isTypBranch || isTypJAL || isTypJALR
  // io.predWrong := (normalNxtPC =/= dinst.pred.pc) || isJmpCsr
  // io.predWrong := isTypJALR || isJmpCsr || (isFmtB && (takeBranch ^ dinst.pred.take)) || (isTypJAL && (~dinst.pred.hit))
  io.predWrong := (isFmtB && (takeBranch ^ dinst.pred.take)) || io.in.bits.info.notBranchPredWrong

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

class EXUForDifftest(
  implicit p: CPUParameters)
    extends Module {
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
