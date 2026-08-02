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

class AddForwardInfo(
  implicit p: CPUParameters)
    extends Bundle {
  val valid = Bool()
  // Address generation only consumes bits 21:0.  Producer identity is already
  // carried by the ordinary EXU writeback-forward bundle.
  val data = UInt(22.W)
}

class Reg1AddImmWbuRawInfo extends Bundle {
  val dataValid = Bool()
  val data      = UInt(22.W)
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

class LateAddForwardInfo extends Bundle {
  val valid = Bool()
  val data  = Types.UWord
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
    dcacheFwd:  DCacheForwardInfo,
    allowCache: Bool,
    lateLoadProducer: LateLoadProducerInfo,
    allowLateLoad: Bool,
    lateAddFwd: LateAddForwardInfo
  ): (Bool, UInt, Bool) = {
    require(wrBacks.length == 3)
    val exuConflict = SingleByPassMux.conflict(rs, wrBacks(0).addr, wrBacks(0).enWr)
    val lsuConflict = SingleByPassMux.conflict(rs, wrBacks(1).addr, wrBacks(1).enWr)
    val wbuConflict = SingleByPassMux.conflict(rs, wrBacks(2).addr, wrBacks(2).enWr)
    val cacheSelect = allowCache && SingleByPassMux.conflict(rs, dcacheFwd.addr, dcacheFwd.valid) && !exuConflict
    // This exception is independent of the combinational DCache hit result.
    // The dependent ADD/ADDI is held in EXU if the registered LSU source later
    // reports a miss.
    val lateLoadSelect = allowLateLoad && exuConflict && lateLoadProducer.valid
    // Sequential single issue makes exuConflict identify the producer; the
    // dedicated late-add result therefore needs no second rd comparison.
    val lateAddSelect = exuConflict && lateAddFwd.valid

    val needStall = Mux(
      exuConflict,
      !wrBacks(0).dataVaild && !lateLoadSelect && !lateAddSelect,
      Mux(lsuConflict, !wrBacks(1).dataVaild && !cacheSelect, wbuConflict && !wrBacks(2).dataVaild)
    )

    // cacheSelect already excludes an EXU conflict.  Put the EXU arm at the
    // outermost level so a same-cycle ALU result does not cross the cache/LSU
    // selection layer on its way back into the next decoded instruction.
    val nonExuData = Mux(
      cacheSelect,
      dcacheFwd.data,
      Mux(lsuConflict, wrBacks(1).data, Mux(wbuConflict, wrBacks(2).data, regData))
    )
    val outData = Mux(exuConflict, Mux(lateAddSelect, lateAddFwd.data, wrBacks(0).data), nonExuData)
    (needStall, outData, lateLoadSelect)
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
    val dcacheFwd  = Input(new DCacheForwardInfo)
    val lateLoadProducer = Input(new LateLoadProducerInfo)
    val lateAddFwd       = Input(new LateAddForwardInfo)
    val allowCacheRs1 = Input(Bool())
    val allowLateLoadRs1 = Input(Bool())
    val allowLateLoadRs2 = Input(Bool())
    val needStall  = Output(Bool())
    val lateLoadRs1 = Output(Bool())
    val lateLoadRs2 = Output(Bool())

    val outData1 = Output(Types.UWord)
    val outData2 = Output(Types.UWord)
  })

  val wrBacks    = Seq(io.wrBackInfo.exu, io.wrBackInfo.lsu, io.wrBackInfo.wbu)
  val csrWrBacks = Seq(io.wrBackInfo.exu, io.wrBackInfo.lsu, io.wrBackInfo.wbu)

  val (needStall1, outData1, lateLoadRs1) = CacheAwareByPassMux(
    io.rs1,
    io.regData1,
    wrBacks,
    io.dcacheFwd,
    io.allowCacheRs1,
    io.lateLoadProducer,
    io.allowLateLoadRs1,
    io.lateAddFwd
  )
  val (needStall2, outData2, lateLoadRs2) = CacheAwareByPassMux(
    io.rs2,
    io.regData2,
    wrBacks,
    io.dcacheFwd,
    true.B,
    io.lateLoadProducer,
    io.allowLateLoadRs2,
    io.lateAddFwd
  )

  val needStallCSR = CSRByPassNeedStall(csrWrBacks)

  io.needStall := needStall1 || needStall2 || needStallCSR
  io.lateLoadRs1 := lateLoadRs1
  io.lateLoadRs2 := lateLoadRs2
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
    val lateAddFwd           = Input(new LateAddForwardInfo)
    val exuAddFwd            = Input(new AddForwardInfo)
    val reg1AddImmWbuRawInfo = Input(new Reg1AddImmWbuRawInfo)

    val out = Decoupled(new DecodedInst)
  })

  dontTouch(io)

  // TODO: handle invalid instruction

  io.out.bits.code     := io.in.bits.code
  io.out.bits.pc       := io.in.bits.pc
  io.out.bits.iid      := io.in.bits.iid
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
  val addrImm = Mux(isTypStore, immS, immI)

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

  val bypassMux = Module(new ByPassMux())
  bypassMux.io.rs1        := res.rs1
  bypassMux.io.rs2        := res.rs2
  bypassMux.io.regData1   := io.rvec.data(0)
  bypassMux.io.regData2   := io.rvec.data(1)
  bypassMux.io.wrBackInfo := io.wrBackInfo
  val needReg1AddImm = isTypLoad || isTypStore || isTypJALR
  bypassMux.io.dcacheFwd := io.dcacheFwd
  bypassMux.io.lateLoadProducer := io.lateLoadProducer
  bypassMux.io.lateAddFwd       := io.lateAddFwd
  bypassMux.io.allowCacheRs1 := !needReg1AddImm
  // Only compact dedicated results may consume a load whose hit/miss is not
  // known in IDU. The fixed-immediate forms cover the hot xibei bit-extraction
  // loop without placing a general AND or barrel shifter in the late path.
  val isLateLoadAdd = isTypArithmetic && inst(14, 12) === 0.U && (isFmtI || inst(31, 25) === 0.U)
  val isLateLoadAndi1 = isTypArithmetic && isFmtI && inst(14, 12) === "b111".U && inst(31, 20) === 1.U
  val isLateLoadSrli1 = isTypArithmetic && isFmtI && inst(14, 12) === "b101".U && inst(31, 20) === 1.U
  val allowLateLoadRs1 = isLateLoadAdd || isLateLoadAndi1 || isLateLoadSrli1
  val allowLateLoadRs2 = isLateLoadAdd && !isFmtI
  bypassMux.io.allowLateLoadRs1 := allowLateLoadRs1
  bypassMux.io.allowLateLoadRs2 := allowLateLoadRs2
  res.lateLoadRs1 := bypassMux.io.lateLoadRs1
  res.lateLoadRs2 := bypassMux.io.lateLoadRs2
  // Only operations that still use the iterative B unit assert bExtValid.
  // Short B operations are evaluated by the ordinary ALU path so they retain
  // same-cycle forwarding and do not inherit the old universal 32-cycle cost.
  val arithmeticFunc3 = inst(14, 12)
  val arithmeticFunc7 = inst(31, 25)
  val isMExtArithmetic = !isFmtI && arithmeticFunc7 === "b0000001".U
  val bImmLow5 = inst(24, 20)
  val isBCount = isFmtI && arithmeticFunc3 === "b001".U && arithmeticFunc7 === "b0110000".U &&
    (bImmLow5 === 0.U || bImmLow5 === 1.U || bImmLow5 === 2.U)
  val isBClmul = !isFmtI && arithmeticFunc7 === "b0000101".U &&
    (arithmeticFunc3 === "b001".U || arithmeticFunc3 === "b011".U)
  val isBOrcB = isFmtI && arithmeticFunc3 === "b101".U && arithmeticFunc7 === "b0010100".U && bImmLow5 === 7.U
  val isBXperm4 = !isFmtI && arithmeticFunc7 === "b0010100".U && arithmeticFunc3 === "b010".U
  val isBRor = arithmeticFunc7 === "b0110000".U && arithmeticFunc3 === "b101".U
  val isIterativeB = isBCount || isBClmul || isBOrcB || isBXperm4 || isBRor
  res.bExtValid := isTypArithmetic && !isMExtArithmetic && isIterativeB
  res.reg1                := bypassMux.io.outData1
  res.reg2                := Mux(isFmtI, immI, bypassMux.io.outData2) // For exu ALU src2
  res.csrReadData         := io.csrRead.data

  val reg1AddImmExuConflict =
    SingleByPassMux.conflict(res.rs1, io.wrBackInfo.exu.addr, io.wrBackInfo.exu.enWr)
  val exuReg1AddImmBypass = reg1AddImmExuConflict && io.exuAddFwd.valid
  val needStallReg1AddImmFromEXU = needReg1AddImm && reg1AddImmExuConflict && !exuReg1AddImmBypass
  val needStallReg1AddImmFromWBU =
    needReg1AddImm &&
      SingleByPassMux.conflict(res.rs1, io.wrBackInfo.wbu.addr, io.wrBackInfo.wbu.enWr) &&
      !io.reg1AddImmWbuRawInfo.dataValid
  // val needStallReg1AddImmFromEXU = false.B

  val needStall = bypassMux.io.needStall || needStallReg1AddImmFromEXU || needStallReg1AddImmFromWBU

  layer.block(PerfCounterLayer) {
    val rawStallPerfTap = Module(new RAWStallPerfTap())
    rawStallPerfTap.io.rs1        := res.rs1
    rawStallPerfTap.io.rs2        := res.rs2
    rawStallPerfTap.io.wrBackInfo := io.wrBackInfo
    rawStallPerfTap.io.inst := inst
    rawStallPerfTap.io.instValid := io.in.valid
    rawStallPerfTap.io.actualNeedStall := needStall
    rawStallPerfTap.io.bypassNeedStall := bypassMux.io.needStall
    rawStallPerfTap.io.reg1AddImmEXUStall := needStallReg1AddImmFromEXU
    rawStallPerfTap.io.reg1AddImmWBUStall := needStallReg1AddImmFromWBU
  }

  // res.snpc       := io.in.bits.pc + 4.U
  res.pcAddImm := io.in.bits.pc + res.imm
  // Keep address generation independent from the generic rs1 bypass path.
  //
  // 80[012]
  // for [012] only lo 2 bits are used, so
  // hi 8+2b: {8'b80, 2'b0}
  //
  def addAddrImm(base: UInt): UInt = base(17, 0) + addrImm(17, 0)

  // res.reg1AddImm := DontCare
  val lsuReg1AddImmBypass =
    SingleByPassMux.conflict(res.rs1, io.wrBackInfo.lsu.addr, io.wrBackInfo.lsu.enWr) && io.wrBackInfo.lsu.dataVaild
  val wbuReg1AddImmBypass =
    SingleByPassMux.conflict(res.rs1, io.wrBackInfo.wbu.addr, io.wrBackInfo.wbu.enWr) &&
      io.reg1AddImmWbuRawInfo.dataValid
  val nonExuReg1AddImmBase = Mux(
    wbuReg1AddImmBypass,
    io.reg1AddImmWbuRawInfo.data,
    io.rvec.data(0)(21, 0)
  )
  val nonLsuReg1AddImmBase = Mux(exuReg1AddImmBypass, io.exuAddFwd.data, nonExuReg1AddImmBase)
  val useLsuReg1AddImm = !exuReg1AddImmBypass && lsuReg1AddImmBypass
  val reg1AddImmBase = Mux(useLsuReg1AddImm, io.wrBackInfo.lsu.data(21, 0), nonLsuReg1AddImmBase)
  def genReg1AddImm(base: UInt): UInt = {
    val region = base(21, 20) + base(19)
    region ## 0.U(2.W) ## addAddrImm(base)
  }
  res.reg1AddImm := genReg1AddImm(reg1AddImmBase)

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
  val returnTargetBase = Mux(lsuReg1AddImmBypass, io.wrBackInfo.lsu.data, nonExuReg1AddImmBase)
  val returnTargetPredWrong = isPredictableReturn && io.in.bits.pred.hit &&
    (exuReg1AddImmBypass || returnTargetBase(16, 2) =/= TrimmedPC.trim(io.in.bits.pred.pc))
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
