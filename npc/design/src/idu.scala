package cpu

import chisel3._
import chisel3.util._
import chisel3.layer._

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
  val useFastLane = Bool()

  val enWrCSR = Bool()
}

object WrBackForwardInfo {
  def apply(
    WrBack:     WrBackForwardInfo,
    newData:    UInt
  )(
    implicit p: CPUParameters
  ): WrBackForwardInfo = {
    val res = Wire(new WrBackForwardInfo)
    res.addr      := WrBack.addr
    res.enWr      := WrBack.enWr
    res.dataVaild := WrBack.dataVaild
    res.data      := newData
    res.useFastLane := WrBack.useFastLane
    res.enWrCSR   := WrBack.enWrCSR
    res
  }
  def apply(
    infoValid:  Bool,
    dinstInfo:  DecodedInst,
    dataVaild:  Bool,
    data:       UInt,
    csrWrEn:    Bool
  )(
    implicit p: CPUParameters
  ): WrBackForwardInfo = {
    val res = Wire(new WrBackForwardInfo)
    res.addr      := dinstInfo.info.rd
    res.enWr      := dinstInfo.info.rdWrEn && infoValid
    res.dataVaild := dataVaild
    res.data      := data
    res.useFastLane := dataVaild
    res.enWrCSR   := csrWrEn && infoValid
    res
  }
  def apply(
    dinst:      DecoupledIO[DecodedInst],
    dataVaild:  Bool,
    data:       UInt,
    csrWrEn:    Bool
  )(
    implicit p: CPUParameters
  ): WrBackForwardInfo = {
    apply(dinst.valid, dinst.bits, dataVaild, data, csrWrEn)
  }
  def apply(
    dinst:      DecoupledIO[DecodedInst]
  )(
    implicit p: CPUParameters
  ): WrBackForwardInfo = {
    val foo = Wire(Types.UWord)
    foo := DontCare
    apply(dinst, false.B, foo, false.B)
  }
}

class WrBackInfoGroup(
  implicit p: CPUParameters)
    extends Bundle {
  val exu = new WrBackForwardInfo
  val lsu = new WrBackForwardInfo
  val wbu = new WrBackForwardInfo
}

class DCacheForwardInfo(
  implicit p: CPUParameters)
    extends Bundle {
  val valid = Bool()
  val addr  = p.GPRAddr
  val data  = Types.UWord
}

class LateLoadProducerInfo(
  implicit p: CPUParameters)
    extends Bundle {
  val valid = Bool()
}

class LateLoadSourceInfo(
  implicit p: CPUParameters)
    extends Bundle {
  val valid     = Bool()
  val dataValid = Bool()
  val data      = Types.UWord
}

object SingleByPassMux {
  def conflict(rs: UInt, rd: UInt, en: Bool): Bool = (rs === rd) && (rd =/= 0.U) && en
  def apply(
    rs:      UInt,
    regData: UInt,
    wrBacks: Seq[WrBackForwardInfo]
  ): (Bool, UInt) = {
    val conflictVec  = wrBacks.map(wb => conflict(rs, wb.addr, wb.enWr))
    val dataVec      = wrBacks.map(_.data)
    val canBypassVec = wrBacks.map(_.dataVaild)

    val needStallVec = conflictVec.zip(canBypassVec).map { case (conflict, canBypass) =>
      conflict && !canBypass
    }

    val needStall  = needStallVec.reduce(_ || _)
    val useBypass  = conflictVec
      .zip(canBypassVec)
      .map { case (conflict, canBypass) =>
        conflict && canBypass
      }
      .reduce(_ || _)
    val bypassData = PriorityMux(conflictVec, dataVec)

    (needStall, Mux(useBypass, bypassData, regData))
  }
}

object CacheAwareByPassMux {
  def apply(
    rs:         UInt,
    regData:    UInt,
    wrBacks:    Seq[WrBackForwardInfo],
    lateLoadProducer: LateLoadProducerInfo,
    allowLateLoad: Bool,
    allowPrevExuFwd: Bool
  ): (Bool, UInt, Bool, Bool, Bool, Bool) = {
    require(wrBacks.length == 3)
    val exuConflict = SingleByPassMux.conflict(rs, wrBacks(0).addr, wrBacks(0).enWr)
    val lsuConflict = SingleByPassMux.conflict(rs, wrBacks(1).addr, wrBacks(1).enWr)
    // This exception is independent of the combinational DCache hit result.
    // The dependent ADD/ADDI is held in EXU if the registered LSU source later
    // reports a miss.
    val lateLoadSelect = allowLateLoad && exuConflict && lateLoadProducer.valid
    val prevExuFwdSelect = allowPrevExuFwd && exuConflict && wrBacks(0).dataVaild
    val prevLsuFwdSelect = !exuConflict && lsuConflict && wrBacks(1).dataVaild

    val needStall = Mux(
      exuConflict,
      !lateLoadSelect && !prevExuFwdSelect,
      lsuConflict && !prevLsuFwdSelect
    )

    // Current EXU data never enters the wide ID/EX operand payload. A safe
    // consumer carries only prevExuFwdSelect and reads that result after the
    // existing EXU/LSU register; unsafe consumers wait for ordinary LSU
    // forwarding on the next cycle.
    val prevLsuDeferredSelect = prevLsuFwdSelect && !wrBacks(1).useFastLane
    (needStall, regData, lateLoadSelect, prevExuFwdSelect, prevLsuFwdSelect, prevLsuDeferredSelect)
  }
}

object CSRByPassNeedStall {
  def apply(wrBacks: Seq[WrBackForwardInfo]): Bool = {
    wrBacks.map(_.enWrCSR).reduce(_ || _)
  }
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
    val lateLoadProducer = Input(new LateLoadProducerInfo)
    val allowLateLoadRs1 = Input(Bool())
    val allowLateLoadRs2 = Input(Bool())
    val allowPrevExuFwdRs1 = Input(Bool())
    val allowPrevExuFwdRs2 = Input(Bool())
    val needStall  = Output(Bool())
    val lateLoadRs1 = Output(Bool())
    val lateLoadRs2 = Output(Bool())
    val prevExuFwdRs1 = Output(Bool())
    val prevExuFwdRs2 = Output(Bool())
    val prevLsuFwdRs1 = Output(Bool())
    val prevLsuFwdRs2 = Output(Bool())
    val prevLsuDeferredRs1 = Output(Bool())
    val prevLsuDeferredRs2 = Output(Bool())

    val outData1 = Output(Types.UWord)
    val outData2 = Output(Types.UWord)
  })

  val wrBacks    = Seq(io.wrBackInfo.exu, io.wrBackInfo.lsu, io.wrBackInfo.wbu)
  val csrWrBacks = Seq(io.wrBackInfo.exu, io.wrBackInfo.lsu, io.wrBackInfo.wbu)

  val (needStall1, outData1, lateLoadRs1, prevExuFwdRs1, prevLsuFwdRs1, prevLsuDeferredRs1) = CacheAwareByPassMux(
    io.rs1,
    io.regData1,
    wrBacks,
    io.lateLoadProducer,
    io.allowLateLoadRs1,
    io.allowPrevExuFwdRs1
  )
  val (needStall2, outData2, lateLoadRs2, prevExuFwdRs2, prevLsuFwdRs2, prevLsuDeferredRs2) = CacheAwareByPassMux(
    io.rs2,
    io.regData2,
    wrBacks,
    io.lateLoadProducer,
    io.allowLateLoadRs2,
    io.allowPrevExuFwdRs2
  )

  val needStallCSR = CSRByPassNeedStall(csrWrBacks)

  io.needStall := needStall1 || needStall2 || needStallCSR
  io.lateLoadRs1 := lateLoadRs1
  io.lateLoadRs2 := lateLoadRs2
  io.prevExuFwdRs1 := prevExuFwdRs1
  io.prevExuFwdRs2 := prevExuFwdRs2
  io.prevLsuFwdRs1 := prevLsuFwdRs1
  io.prevLsuFwdRs2 := prevLsuFwdRs2
  io.prevLsuDeferredRs1 := prevLsuDeferredRs1
  io.prevLsuDeferredRs2 := prevLsuDeferredRs2
  io.outData1  := outData1
  io.outData2  := outData2
}

class IDU(
  implicit p: CPUParameters)
    extends Module {
  val io = IO(new Bundle {
    val in           = Flipped(Decoupled(new FetchedInst))
    val rvec         = GPRegReqIO.ReadVecTX(2)
    val csrRead      = CSRegReqIO.TX.SingleRead
    val csrJmpTarget = Input(new Bundle {
      val mepc  = Types.UWord
      val mtvec = Types.UWord
    })

    val pipelineFlush = Input(Bool())

    val wrBackInfo           = Input(new WrBackInfoGroup)
    val dcacheFwd            = Input(new DCacheForwardInfo)
    val lateLoadProducer     = Input(new LateLoadProducerInfo)

    val out = Decoupled(new DecodedInst)
  })

  dontTouch(io)

  // TODO: handle invalid instruction

  io.out.bits.code     := io.in.bits.code
  io.out.bits.pc       := io.in.bits.pc
  io.out.bits.iid      := io.in.bits.iid
  io.out.bits.epoch    := io.in.bits.epoch
  io.out.bits.predTake := io.in.bits.pred.take

  // alias
  val res      = io.out.bits.info
  val inst     = io.in.bits.code
  val isFenceI = inst === "h0000100f".U

  assert(!(io.in.valid && isFenceI), "fence.i is not supported on riscv32-jyd")

  res.viewAsSupertype(new InstMetaInfo) := InstInfoDecoder(inst(6, 0))

  val isTypLoad   = InstType.hasSame(res.typ, InstType.load)
  val isTypStore  = InstType.hasSame(res.typ, InstType.store)
  val isTypBranch = InstType.hasSame(res.typ, InstType.branch)
  val isTypJALR   = InstType.hasSame(res.typ, InstType.jalr)
  val isTypJAL    = InstType.hasSame(res.typ, InstType.jal)
  val isTypLUI    = InstType.hasSame(res.typ, InstType.lui)
  val isTypAUIPC  = InstType.hasSame(res.typ, InstType.auipc)
  val isTypSys    = InstType.hasSame(res.typ, InstType.system)
  val isTypArithmetic = InstType.hasSame(res.typ, InstType.arithmetic)

  val isFmtI = InstFmt.hasSame(res.fmt, InstFmt.imm)
  val isFmtU = InstFmt.hasSame(res.fmt, InstFmt.upper)
  val isFmtJ = InstFmt.hasSame(res.fmt, InstFmt.jump)

  val noNeedRs2 = isFmtI || isFmtU || isFmtJ

  res.rd  := inst(11, 7)
  res.rs1 := inst(19, 15)
  res.rs2 := Mux(noNeedRs2, 0.U, inst(24, 20))

  // for now, system inst, ecall and mret has rd == 0
  // TODO: handle rd != 0 case
  val isNoWrBackType = isTypStore || isTypBranch
  res.rdWrEn := ~isNoWrBackType

  io.rvec.addr(0) := res.rs1
  io.rvec.addr(1) := res.rs2
  io.csrRead.en   := io.in.valid
  io.csrRead.addr := inst(31, 20)

  val immI    = Cat(Fill(21, inst(31)), inst(30, 20))
  val immS    = Cat(immI(31, 5), inst(11, 8), inst(7))
  val immB    = Cat(immI(31, 12), inst(7), immS(10, 1), 0.U(1.W))
  val immU    = Cat(inst(31, 12), 0.U(12.W))
  val immJ    = Cat(immI(31, 20), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W))
  // I- and S-format address immediates share inst[31:25].  Only select the
  // low five bits, using the store opcode class directly, so address
  // generation does not inherit the full instruction-type decode cone.
  val isStoreEncoding = inst(6, 5) === "b01".U
  val addrImm12 = Cat(inst(31, 25), Mux(isStoreEncoding, inst(11, 7), inst(24, 20)))
  val addrImm = addrImm12.asSInt.pad(32).asUInt

  val dontcareImm = Wire(Types.UWord)
  dontcareImm := DontCare
  res.imm     := MuxLookup(res.fmt, dontcareImm)(
    Seq(
      InstFmt.imm    -> immI,
      InstFmt.jump   -> immJ,
      InstFmt.store  -> immS,
      InstFmt.branch -> immB,
      InstFmt.upper  -> immU
    )
  )

  val isXlrevLoopEncoding = inst(31, 25) === 2.U && inst(14, 12) === 6.U && inst(6, 0) === "b0001011".U
  val bypassMux = Module(new ByPassMux())
  // The looping xlrev form advances from EXU's private chain state after its
  // init instruction.  Its encoded rs1 only names the eventual destination;
  // treating it as a source creates a false loop-carried RAW dependency.
  val needReg1AddImm = isTypLoad || isTypStore || isTypJALR
  bypassMux.io.rs1        := Mux(isXlrevLoopEncoding || needReg1AddImm, 0.U, res.rs1)
  bypassMux.io.rs2        := res.rs2
  bypassMux.io.regData1   := io.rvec.data(0)
  bypassMux.io.regData2   := io.rvec.data(1)
  bypassMux.io.wrBackInfo := io.wrBackInfo
  bypassMux.io.lateLoadProducer := io.lateLoadProducer
  // Only compact dedicated results may consume a load whose hit/miss is not
  // known in IDU. The fixed-immediate forms cover the hot xibei bit-extraction
  // loop without placing a general AND or barrel shifter in the late path.
  val isLateLoadAndi1 = isTypArithmetic && isFmtI && inst(14, 12) === "b111".U && inst(31, 20) === 1.U
  val isLateLoadSrli1 = isTypArithmetic && isFmtI && inst(14, 12) === "b101".U && inst(31, 20) === 1.U
  val isEqualityBranch = isTypBranch && inst(14, 13) === 0.U
  val allowLateLoadRs1 = isLateLoadAndi1 || isLateLoadSrli1 || isEqualityBranch
  bypassMux.io.allowLateLoadRs1 := allowLateLoadRs1
  bypassMux.io.allowLateLoadRs2 := isEqualityBranch
  val arithmeticFunc3 = inst(14, 12)
  val arithmeticFunc7 = inst(31, 25)
  val isMExtArithmetic = !isFmtI && arithmeticFunc7 === "b0000001".U
  // These consumers either complete combinationally or capture operands in
  // their execution unit on the first fire. Address and store-data consumers
  // use the same narrow post-register token rather than a wide IDU bypass.
  val isXlrevEncoding = inst(6, 0) === "b0001011".U &&
    ((arithmeticFunc3 === 6.U &&
      (inst(31, 25) === 0.U || inst(31, 25) === 1.U || inst(31, 25) === 2.U)) ||
      (arithmeticFunc3 === 7.U && inst(31, 25) === 0.U))
  bypassMux.io.allowPrevExuFwdRs1 :=
    (isTypArithmetic && !isXlrevEncoding) || isTypBranch || isTypSys
  bypassMux.io.allowPrevExuFwdRs2 := isTypArithmetic || isTypBranch || isTypStore
  res.lateLoadRs1 := bypassMux.io.lateLoadRs1
  res.lateLoadRs2 := bypassMux.io.lateLoadRs2
  res.prevExuFwdRs1 := bypassMux.io.prevExuFwdRs1
  res.prevExuFwdRs2 := bypassMux.io.prevExuFwdRs2
  res.prevExuFwdRs2Alu := bypassMux.io.prevExuFwdRs2
  res.prevLsuFwdRs1 := bypassMux.io.prevLsuFwdRs1
  res.prevLsuFwdRs2 := bypassMux.io.prevLsuFwdRs2
  res.prevLsuDeferredRs1 := bypassMux.io.prevLsuDeferredRs1
  res.prevLsuDeferredRs2 := bypassMux.io.prevLsuDeferredRs2
  dontTouch(res.prevExuFwdRs2Alu)
  // Only operations that still use the iterative B unit assert bExtValid.
  // Short B operations are evaluated by the ordinary ALU path so they retain
  // same-cycle forwarding and do not inherit the old universal 32-cycle cost.
  val bImmLow5 = inst(24, 20)
  val isBCount = isFmtI && arithmeticFunc3 === "b001".U && arithmeticFunc7 === "b0110000".U &&
    (bImmLow5 === 0.U || bImmLow5 === 1.U || bImmLow5 === 2.U)
  val isBClmul = !isFmtI && arithmeticFunc7 === "b0000101".U &&
    (arithmeticFunc3 === "b001".U || arithmeticFunc3 === "b011".U)
  val isBOrcB = isFmtI && arithmeticFunc3 === "b101".U && arithmeticFunc7 === "b0010100".U && bImmLow5 === 7.U
  val isBXperm4 = !isFmtI && arithmeticFunc7 === "b0010100".U && arithmeticFunc3 === "b010".U
  val isBRor = arithmeticFunc7 === "b0110000".U && arithmeticFunc3 === "b101".U
  val isIterativeB = isBCount || isBClmul || isBOrcB || isBXperm4 || isBRor
  val isBShiftAdd = !isFmtI && arithmeticFunc7 === "b0010000".U &&
    (arithmeticFunc3 === "b010".U || arithmeticFunc3 === "b100".U || arithmeticFunc3 === "b110".U)
  val isBSext = isFmtI && arithmeticFunc7 === "b0110000".U && arithmeticFunc3 === "b001".U &&
    (bImmLow5 === 4.U || bImmLow5 === 5.U)
  val isBMinu = !isFmtI && arithmeticFunc7 === "b0000101".U && arithmeticFunc3 === "b101".U
  val isBBext = arithmeticFunc7 === "b0100100".U && arithmeticFunc3 === "b101".U
  val isCrcU8Custom = inst(31, 25) === 0.U && arithmeticFunc3 === 0.U && inst(6, 0) === "b0001011".U
  val isBitExtractMulCustom = inst(31, 25) === 0.U && arithmeticFunc3 === 5.U && inst(6, 0) === "b0001011".U
  val isListReverseCustom = isXlrevEncoding
  val isMatrixReduceCustom = inst(31, 25) === 2.U && arithmeticFunc3 === 7.U && inst(6, 0) === "b0001011".U
  val isNumericDfaCustom = inst(6, 0) === "b1011011".U &&
    ((inst(31, 25) === 0.U &&
      (arithmeticFunc3 === 0.U || arithmeticFunc3 === 2.U || arithmeticFunc3 === 3.U || arithmeticFunc3 === 5.U)) ||
     (inst(31, 25) === 1.U && (arithmeticFunc3 === 2.U || arithmeticFunc3 === 5.U)))
  res.bExtValid := isTypArithmetic && !isMExtArithmetic && isIterativeB
  res.crcValid := isCrcU8Custom
  res.xbmulValid := isBitExtractMulCustom
  res.xlrevValid := isListReverseCustom
  res.xlrevSingle := isListReverseCustom && arithmeticFunc3 === 6.U
  res.xlrevChain := isListReverseCustom && arithmeticFunc3 === 6.U && inst(31, 25) === 1.U
  res.xlrevLoop := isListReverseCustom && isXlrevLoopEncoding
  // The legacy custom-0 whole-parser state machine is intentionally unsupported.
  // Use the custom-2 word-fed numeric DFA operations decoded below.
  res.xmsumValid := isMatrixReduceCustom
  res.numericDfaValid := isNumericDfaCustom
  res.aluIsSub  := !isFmtI && inst(30)
  res.aluUseSpecialResult :=
    isTypArithmetic && (isBShiftAdd || isBSext || isBMinu || isBBext || isCrcU8Custom || isBitExtractMulCustom)
  // Loads consume rs1 only through the dedicated registered address payload
  // below.  Keeping cache-forwarded data out of the unused generic ALU
  // payload avoids a wide IDU payload mux/self-loop on the critical path.
  res.reg1                := bypassMux.io.outData1
  res.reg2                := Mux(isFmtI, immI, bypassMux.io.outData2) // For exu ALU src2
  res.csrReadData         := io.csrRead.data

  val addressExuConflict =
    needReg1AddImm && SingleByPassMux.conflict(res.rs1, io.wrBackInfo.exu.addr, io.wrBackInfo.exu.enWr)
  val addressLsuConflict =
    needReg1AddImm && SingleByPassMux.conflict(res.rs1, io.wrBackInfo.lsu.addr, io.wrBackInfo.lsu.enWr)
  val addressDcacheReady =
    SingleByPassMux.conflict(res.rs1, io.dcacheFwd.addr, io.dcacheFwd.valid)
  val addressLsuReady = addressLsuConflict && io.wrBackInfo.lsu.dataVaild
  val needStallAddress = addressExuConflict || (addressLsuConflict && !addressLsuReady && !addressDcacheReady)
  val needStall = bypassMux.io.needStall || needStallAddress

  layer.block(PerfCounterLayer) {
    val rawStallPerfTap = Module(new RAWStallPerfTap())
    rawStallPerfTap.io.rs1        := res.rs1
    rawStallPerfTap.io.rs2        := res.rs2
    rawStallPerfTap.io.wrBackInfo := io.wrBackInfo
    rawStallPerfTap.io.inst := inst
    rawStallPerfTap.io.instValid := io.in.valid
    rawStallPerfTap.io.actualNeedStall := needStall
    rawStallPerfTap.io.bypassNeedStall := bypassMux.io.needStall
    rawStallPerfTap.io.reg1AddImmEXUStall := false.B
    rawStallPerfTap.io.reg1AddImmWBUStall := false.B
  }

  // res.snpc       := io.in.bits.pc + 4.U
  res.pcAddImm := io.in.bits.pc + res.imm
  def addAddrImm(base: UInt): UInt = {
    val lowSum = base(15, 0) +& addrImm12.asSInt.pad(16).asUInt
    val positiveOffset = !addrImm12(11)
    val crossesIntoDram = positiveOffset && base(19, 12) === "hff".U && lowSum(16)
    val crossesIntoPerip = positiveOffset && base(20, 12) === "h1ff".U && lowSum(16)
    val high = Mux(crossesIntoPerip, "h20".U(6.W), Mux(crossesIntoDram, "h10".U(6.W), base(21, 16)))
    high ## lowSum(15, 0)
  }
  val addressBase = Mux(addressLsuReady, io.wrBackInfo.lsu.data, Mux(addressDcacheReady, io.dcacheFwd.data, io.rvec.data(0)))
  res.reg1AddImm := addAddrImm(addressBase)

  res.isECall := inst === "h73".U
  res.isMRet  := inst === "h30200073".U

  res.is_beq  := isTypBranch && inst(14, 12) === "b000".U
  res.is_bne  := isTypBranch && inst(14, 12) === "b001".U
  res.is_blt  := isTypBranch && inst(14, 12) === "b100".U
  res.is_bge  := isTypBranch && inst(14, 12) === "b101".U
  res.is_bltu := isTypBranch && inst(14, 12) === "b110".U
  res.is_bgeu := isTypBranch && inst(14, 12) === "b111".U

  val snpc = io.in.bits.pc + 4.U

  res.staticNextPCOrCSRTarget := TrimmedPC.expand(Mux(
    res.isECall,
    TrimmedPC.trim(io.csrJmpTarget.mtvec),
    Mux(res.isMRet, TrimmedPC.trim(io.csrJmpTarget.mepc), TrimmedPC.trim(snpc))
  ))

  res.preMuxWrBackData := Mux1H(
    Seq(
      isTypLUI               -> immU,
      isTypAUIPC             -> res.pcAddImm,
      (isTypJALR | isTypJAL) -> snpc,
      isTypSys               -> res.csrReadData
    )
  )

  val isJmpCSR = res.isECall || res.isMRet

  // The workload's reusable indirect transfers are standard returns.  Limit
  // prediction to that exact form so target validation compares the already
  // bypassed x1 value directly and does not extend the IDU address-add path.
  // Other JALR encodings retain the original always-redirect behavior.
  val isPredictableReturn = inst === "h00008067".U
  // Do not place the EXU add-result carry chain on the return-prediction
  // validation path.  A return immediately dependent on an EXU write to x1
  // takes the ordinary redirect path; normal compiler epilogues restore x1
  // earlier through LSU and retain prediction.  The comparison base excludes
  // EXU data structurally even when the conservative redirect term is true.
  val returnTargetBase = io.rvec.data(0)
  val returnProducerPending = SingleByPassMux.conflict(res.rs1, io.wrBackInfo.exu.addr, io.wrBackInfo.exu.enWr) ||
    SingleByPassMux.conflict(res.rs1, io.wrBackInfo.lsu.addr, io.wrBackInfo.lsu.enWr)
  val returnTargetPredWrong = isPredictableReturn && io.in.bits.pred.hit &&
    (returnProducerPending || returnTargetBase(16, 2) =/= TrimmedPC.trim(io.in.bits.pred.pc))
  res.notBranchPredWrong := isJmpCSR ||
    (isTypJAL && ~io.in.bits.pred.hit) ||
    (isTypJALR && (!isPredictableReturn || ~io.in.bits.pred.hit)) || returnTargetPredWrong

  io.in.ready  := (io.out.ready && !needStall)
  io.out.valid := io.in.valid && !needStall

  StageLogger(
    clock,
    StageLogConst.Event.stage,
    StageLogConst.Stage.idu,
    io.in.fire && !needStall && !io.pipelineFlush,
    io.in.bits.iid
  )
}
