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
    val dinst    = Flipped(Decoupled(new DecodedInst))
    val rvec     = GPRegReqIO.TX.VecRead(2)
    val csr_rvec = CSRegReqIO.TX.SingleRead
    val mem      = AXI4IO.Master
    val out      = Decoupled(new WriteBackInfo)
  })

  val GARBAGE_UNINIT_VALUE = "hDEADBEEF".U

  val alu = Module(new ALU)

  alu.io.out.ready := io.out.ready
  alu.io.in.valid  := io.dinst.valid

  val alu_in = alu.io.in.bits
  val dinst  = io.dinst.bits
  val func3t = dinst.code(14, 12)
  val func7t = dinst.code(31, 25)

  alu_in.is_imm := (dinst.info.fmt === InstFmt.imm)
  alu_in.func3t := Mux(dinst.info.fmt === InstFmt.branch, func3t >> 1, func3t)
  alu_in.func7t := func7t

  val MS_fsm = Module(new OneMasterOneSlaveFSM)
  MS_fsm.connectMaster(io.dinst)
  MS_fsm.connectSlave(io.out)

  // reg

  io.rvec.en      := true.B
  io.rvec.addr(0) := dinst.info.rs1
  io.rvec.addr(1) := dinst.info.rs2
  val reg_v1 = io.rvec.data(0)
  val reg_v2 = io.rvec.data(1)

  alu_in.src1 := reg_v1
  // when branch, src2 is reg_v2
  alu_in.src2 := Mux(dinst.info.fmt === InstFmt.imm, dinst.info.imm, reg_v2)

  // csr

  val is_mret  = dinst.code === "h30200073".U
  val is_ecall = dinst.code === "h73".U

  io.out.bits.csr_ecallflag := is_ecall

  val csrren    = io.csr_rvec.en
  val csr_raddr = io.csr_rvec.addr
  val csr_rdata = io.csr_rvec.data

  val csrwen    = io.out.bits.csr.en
  val csr_waddr = io.out.bits.csr.addr
  val csr_wdata = io.out.bits.csr.data

  object CSROp {
    val csrrw = 1.U
    val csrrs = 2.U
    def isValidCSRop(op: UInt): Bool = {
      (op === csrrw) || (op === csrrs)
    }
  }

  when(dinst.info.typ === InstType.system) {
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
      // printf("(exu) CSR access addr 0x%x\n", csr_addr)
      csr_wdata := MuxLookup(func3t, GARBAGE_UNINIT_VALUE)(
        Seq(
          CSROp.csrrw -> reg_v1,
          CSROp.csrrs -> (csr_rdata | reg_v1)
        )
      )
    }
  }.otherwise {
    csrren    := false.B
    csrwen    := false.B
    csr_raddr := 0.U
    csr_waddr := 0.U
    csr_wdata := 0.U
  }

  // mem

  object MemOp {
    val byte     = 0.U
    val halfword = 1.U
    val word     = 2.U
    val lbu      = 4.U
    val lhu      = 5.U

    def isValidLoadOp(op: UInt):  Bool = {
      (op === byte) || (op === halfword) || (op === word) || (op === lbu) || (op === lhu)
    }
    def isValidStoreOp(op: UInt): Bool = {
      (op === byte) || (op === halfword) || (op === word)
    }
  }

  val memRdRawData = Reg(Types.UWord)

  val isLoad  = (dinst.info.typ === InstType.load) && MS_fsm.io.master_valid
  val isStore = (dinst.info.typ === InstType.store) && MS_fsm.io.master_valid
  val isMemOp = isLoad || isStore

  val memWDone = Reg(Bool())
  val memRDone = Reg(Bool())

  val memAddrSent = Reg(Bool())

  val memOPDone = memWDone || memRDone

  val memIO = io.mem

  // io.mem.dontCareNonLiteW()

  val memAddr            = reg_v1 + dinst.info.imm
  val memAddrUnalignPart = memAddr(1, 0)
  // val memAddrUnalignPartBitlen = memAddrUnalignPart << 3

  memIO.araddr  := memAddr
  memIO.arvalid := isLoad && (!memRDone) && (!memAddrSent)

  val memOpSize = MuxLookup(func3t, 0.U)(
    Seq(
      MemOp.byte     -> 0.U,
      MemOp.halfword -> 1.U,
      MemOp.word     -> 2.U,
      MemOp.lbu      -> 0.U,
      MemOp.lhu      -> 1.U
    )
  )

  memIO.arid    := 0.U
  memIO.arlen   := 0.U
  memIO.arsize  := memOpSize
  memIO.arburst := 1.U

  // val memRdData = memRdRawData >> memAddrUnalignPartBitlen
  val memRdData = MuxLookup(memAddrUnalignPart, 0.U(8.W))(
    Seq(
      0.U -> memRdRawData,
      1.U -> memRdRawData(31, 8).pad(32),
      2.U -> memRdRawData(31, 16).pad(32),
      3.U -> memRdRawData(31, 24).pad(32)
    )
  )

  when(memIO.arvalid && memIO.arready) {
    memAddrSent := true.B
  }
  when(memIO.rvalid && !memRDone) {
    memRdRawData := memIO.rdata
    memRDone     := true.B
  }
  memIO.rready := true.B
  when(!isMemOp) {
    memRDone    := false.B
    memWDone    := false.B
    memAddrSent := false.B
  }

  // mem write

  // for now sw only consider align addr

  val memWAddr = memIO.awaddr
  val memWData = memIO.wdata
  val memWMask = memIO.wstrb

  memIO.awvalid := isStore && (!memWDone) && (!memAddrSent)
  memIO.wvalid  := isStore && (!memWDone)
  memIO.wlast   := memIO.wvalid
  dontTouch(io.mem)
  dontTouch(memIO.wlast)

  memIO.awid    := 0.U
  memIO.awlen   := 0.U
  memIO.awsize  := memOpSize
  memIO.awburst := 1.U

  when(memIO.awvalid && memIO.awready) {
    memAddrSent := true.B
  }
  // when(memIO.wvalid && memIO.wready) {
  //   memWDone := true.B
  // }

  //
  // need wait for bresp
  // since w done only means data has been sent
  // later read operation may have higher priority and
  // the write may not be finished yet
  //
  when(memIO.bvalid && memIO.bready) {
    memWDone := true.B
  }
  memIO.bready := true.B

  // memWData := reg_v2 << memAddrUnalignPartBitlen
  memWData := MuxLookup(memAddrUnalignPart, 0.U(32.W))(
    Seq(
      0.U -> reg_v2,
      1.U -> Cat(reg_v2(23, 0), 0.U(8.W)),
      2.U -> Cat(reg_v2(15, 0), 0.U(16.W)),
      3.U -> Cat(reg_v2(7, 0), 0.U(24.W))
    )
  )
  val wByteMask = MuxLookup(memAddrUnalignPart, 0.U(4.W))(
    Seq(
      0.U -> "b0001".U(4.W),
      1.U -> "b0010".U(4.W),
      2.U -> "b0100".U(4.W),
      3.U -> "b1000".U(4.W)
    )
  )

  memWAddr := memAddr
  memWMask := MuxLookup(func3t, 0.U)(
    Seq(
      MemOp.byte     -> wByteMask,
      MemOp.halfword -> (wByteMask | (wByteMask << 1)), // (3.U(4.W) << memAddrUnalignPart),
      MemOp.word     -> 15.U(4.W)
    )
  )

  when(dinst.info.typ === InstType.store) {
    when(!MemOp.isValidStoreOp(func3t)) {
      printf("(exu) UNKNOWN STORE func3t %d\n", func3t)
    }
  }

  MS_fsm.io.self_finished := alu.io.out.valid && (
    (!isMemOp) || memOPDone
  )

  // wdata

  val nxt_pc   = Wire(Types.UWord)
  val pcAddImm = dinst.pc + dinst.info.imm
  val snpc     = dinst.pc + 4.U

  // for now, system inst, ecall and mret has rd == 0
  // TODO: handle rd != 0 case
  io.out.bits.gpr.en := (dinst.info.rd =/= 0.U) && (dinst.info.typ =/= InstType.branch) &&
    (dinst.info.typ =/= InstType.store)

  io.out.bits.gpr.addr := dinst.info.rd
  io.out.bits.gpr.data := MuxLookup(dinst.info.typ, GARBAGE_UNINIT_VALUE)(
    Seq(
      InstType.arithmetic -> alu.io.out.bits,
      InstType.lui        -> dinst.info.imm,
      InstType.auipc      -> pcAddImm,
      InstType.jalr       -> snpc,
      InstType.jal        -> snpc,
      InstType.load       -> MuxLookup(func3t, GARBAGE_UNINIT_VALUE)(
        Seq(
          MemOp.byte     -> Cat(Fill(24, memRdData(7)), memRdData(7, 0)),
          MemOp.halfword -> Cat(Fill(16, memRdData(15)), memRdData(15, 0)),
          MemOp.word     -> memRdData,
          MemOp.lbu      -> Cat(Fill(24, 0.U), memRdData(7, 0)),
          MemOp.lhu      -> Cat(Fill(16, 0.U), memRdData(15, 0))
        )
      ),
      InstType.system     -> Mux(
        is_ecall || is_mret,
        GARBAGE_UNINIT_VALUE,
        MuxLookup(func3t, GARBAGE_UNINIT_VALUE)(
          Seq(
            CSROp.csrrw -> csr_rdata,
            CSROp.csrrs -> csr_rdata
          )
        )
      )
    )
  )
  when(dinst.info.typ === InstType.system) {
    when(!(is_ecall || is_mret)) {
      when(!CSROp.isValidCSRop(func3t)) {
        printf("(exu) UNKNOWN SYSTEM func3t %d\n", func3t)
      }
    }
  }
  when(dinst.info.typ === InstType.load) {
    when(!MemOp.isValidLoadOp(func3t)) {
      printf("(exu) UNKNOWN LOAD func3t %d\n", func3t)
    }
  }

  // nxt_pc

  object BranchOp {
    val beq  = 0.U
    val bne  = 1.U
    val blt  = 4.U
    val bge  = 5.U
    val bltu = 6.U
    val bgeu = 7.U
    def isValidBranchOp(op: UInt): Bool = {
      (op === beq) || (op === bne) || (op === blt) || (op === bge) || (op === bltu) || (op === bgeu)
    }
  }

  io.out.bits.nxt_pc := nxt_pc
  when(is_ecall || is_mret) {
    nxt_pc := csr_rdata
  }.otherwise {
    when(dinst.info.typ === InstType.jalr) {
      val r1AddImm = reg_v1 + dinst.info.imm
      nxt_pc := r1AddImm(31, 1) ## 0.U(1.W)
    }.elsewhen(dinst.info.typ === InstType.jal) {
      nxt_pc := pcAddImm
    }.elsewhen(dinst.info.fmt === InstFmt.branch) {
      // reuse alu
      // branch func3t
      //
      // blt/bge 10x -> feed alu 010 -> slt
      // bltu/bgeu 11x -> feed alu 011 -> sltu
      val isLessThan  = alu.io.out.bits(0)
      // val branchCalc = MuxLookup(func3t(2,1), false.B)(
      //   Seq(
      //     0.U  -> (reg_v1 === reg_v2),
      //     2.U  -> isLessThan,
      //     3.U -> isLessThan,
      //   )
      // )
      //
      val branchCalc  = Mux(func3t(2), isLessThan, (reg_v1 === reg_v2))
      val takeBranch = Mux(func3t(0), ~branchCalc, branchCalc)
      nxt_pc := Mux(takeBranch, dinst.pc + dinst.info.imm, snpc)
      when(!BranchOp.isValidBranchOp(func3t)) {
        printf("(exu) UNKNOWN BRANCH func3t %d\n", func3t)
      }
    }.otherwise {
      nxt_pc := snpc
    }
  }
}
