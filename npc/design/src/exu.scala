package cpu
import chisel3._
import chisel3.util._
import common_def._
import busfsm._

import regfile._
import memory._
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
  alu_in.func3t := func3t
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
  alu_in.src2 := Mux(dinst.info.fmt === InstFmt.reg, reg_v2, dinst.info.imm)

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

  val memAddr                  = reg_v1 + dinst.info.imm
  val memAddrUnalignPart       = memAddr(1, 0)
  val memAddrUnalignPartBitlen = memAddrUnalignPart << 3

  val memRdRawData = Reg(Types.UWord)

  val isLoad  = (dinst.info.typ === InstType.load) && MS_fsm.io.master_valid
  val isStore = (dinst.info.typ === InstType.store) && MS_fsm.io.master_valid
  val isMemOp = isLoad || isStore

  val memOpSize = MuxLookup(func3t, 0.U)(
    Seq(
      MemOp.byte     -> 0.U,
      MemOp.halfword -> 1.U,
      MemOp.word     -> 2.U,
      MemOp.lbu      -> 0.U,
      MemOp.lhu      -> 1.U
    )
  )

  val memWDone = Reg(Bool())
  val memRDone = Reg(Bool())

  val memAddrSent = Reg(Bool())

  val memOPDone = memWDone || memRDone

  val memIO = io.mem

  io.mem.dontCareNonLiteW()

  memIO.araddr  := memAddr
  memIO.arvalid := isLoad && (!memRDone) && (!memAddrSent)

  memIO.arid    := 0.U
  memIO.arlen   := 0.U
  memIO.arsize  := memOpSize
  memIO.arburst := 1.U

  when(memIO.arvalid && memIO.arready) {
    memAddrSent := true.B
  }
  when(memIO.rvalid && !memRDone) {
    memRdRawData := memIO.rdata
    memRDone     := true.B
  }
  memIO.rready  := true.B
  when(!isMemOp) {
    memRDone    := false.B
    memWDone    := false.B
    memAddrSent := false.B
  }

  val memRdData = memRdRawData // >> memAddrUnalignPartBitlen

  // mem write

  // for now sw only consider align addr

  val memWAddr = memIO.awaddr
  val memWData = memIO.wdata
  val memWMask = memIO.wstrb

  memIO.awvalid := isStore && (!memWDone) && (!memAddrSent)
  memIO.wvalid  := isStore && (!memWDone)

  memIO.awid := 0.U
  memIO.awlen := 0.U
  memIO.awsize := memOpSize
  memIO.awburst := 1.U

  when(memIO.awvalid && memIO.awready) {
    memAddrSent := true.B
  }
  when(memIO.wvalid && memIO.wready) {
    memWDone := true.B
  }

  when(memIO.bvalid) {}
  memIO.bready := true.B

  memWData := reg_v2 << memAddrUnalignPartBitlen
  memWAddr := memAddr
  memWMask := MuxLookup(func3t, 0.U)(
    Seq(
      MemOp.byte     -> (1.U(4.W) << memAddrUnalignPart),
      MemOp.halfword -> (3.U(4.W) << memAddrUnalignPart),
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

  // for now, system inst, ecall and mret has rd == 0
  // TODO: handle rd != 0 case
  io.out.bits.gpr.en := (dinst.info.rd =/= 0.U) && (dinst.info.typ =/= InstType.none) &&
    (dinst.info.typ =/= InstType.store)

  io.out.bits.gpr.addr := dinst.info.rd
  io.out.bits.gpr.data := MuxLookup(dinst.info.typ, GARBAGE_UNINIT_VALUE)(
    Seq(
      InstType.arithmetic -> alu.io.out.bits,
      InstType.lui        -> dinst.info.imm,
      InstType.auipc      -> (dinst.pc + dinst.info.imm),
      InstType.jalr       -> (dinst.pc + 4.U),
      InstType.jal        -> (dinst.pc + 4.U),
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

  val nxt_pc = io.out.bits.nxt_pc
  val snpc   = dinst.pc + 4.U
  when(is_ecall || is_mret) {
    nxt_pc := csr_rdata
  }.otherwise {
    when(dinst.info.typ === InstType.jalr) {
      nxt_pc := (reg_v1 + dinst.info.imm) &
        Cat(Fill(Types.BitWidth.word - 1, 1.U), 0.U(1.W)) // set bit0 to 0
    }.elsewhen(dinst.info.typ === InstType.jal) {
      nxt_pc := dinst.pc + dinst.info.imm
    }.elsewhen(dinst.info.fmt === InstFmt.branch) {
      val take_branch = MuxLookup(func3t, false.B)(
        Seq(
          BranchOp.beq  -> (reg_v1 === reg_v2),
          BranchOp.bne  -> (reg_v1 =/= reg_v2),
          BranchOp.blt  -> (reg_v1.asSInt < reg_v2.asSInt),
          BranchOp.bge  -> (reg_v1.asSInt >= reg_v2.asSInt),
          BranchOp.bltu -> (reg_v1 < reg_v2),
          BranchOp.bgeu -> (reg_v1 >= reg_v2)
        )
      )
      nxt_pc := Mux(take_branch, dinst.pc + dinst.info.imm, snpc)
      when(!BranchOp.isValidBranchOp(func3t)) {
        printf("(exu) UNKNOWN BRANCH func3t %d\n", func3t)
      }
    }.otherwise {
      nxt_pc := snpc
    }
  }
}
