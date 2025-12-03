package cpu

import chisel3._
import chisel3.util.{Cat, Decoupled, DecoupledIO, Enum, Fill, MuxLookup}
import chisel3.util.HasBlackBoxInline

// see https://www.chisel-lang.org/api/latest/chisel3/util/circt/dpi/index.html
// chisel has native dpi interface since
//  https://github.com/chipsalliance/chisel/pull/4158
import chisel3.util.circt.dpi.{
  RawClockedNonVoidFunctionCall,
  RawClockedVoidFunctionCall,
  RawUnclockedNonVoidFunctionCall
}

import chisel3.experimental.dataview._

import regfile._
import memory._

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

class Inst extends Bundle {
  val code = Output(Types.UWord)
  val pc   = Output(Types.UWord)
}

class OneMasterOneSlaveFSM extends Module {
  val io = IO(new Bundle {
    val master_valid = Input(Bool())
    val master_ready = Output(Bool())

    val self_finished = Input(Bool())

    val slave_valid = Output(Bool())
    val slave_ready = Input(Bool())

    val _state = Output(UInt(2.W))
  })

  val SINGLE_CYCLE_CPU = true

  val s_idle :: s_busy :: s_wait_slave :: Nil = Enum(3)
  val state                                   = RegInit(s_idle)

  io._state := state

  state := MuxLookup(state, s_idle)(
    Seq(
      s_idle       -> Mux(io.master_valid, s_busy, s_idle),
      s_busy       -> Mux(io.self_finished, s_wait_slave, s_busy),
      s_wait_slave -> Mux(io.slave_ready, s_idle, s_wait_slave)
    )
  )

  io.master_ready := (state === s_wait_slave) && (io.self_finished)
  io.slave_valid  := (state === s_wait_slave)

  def connectMaster[T <: Data](master: DecoupledIO[T]): Unit = {
    master.ready    := io.master_ready
    io.master_valid := master.valid
  }
  def connectSlave[T <: Data](slave: DecoupledIO[T]):   Unit = {
    slave.valid    := io.slave_valid
io.slave_ready := slave.ready
  }

}

class IFU extends Module {
  val io = IO(new Bundle {
    val pc  = Flipped(Decoupled(Input(Types.UWord)))
    val out = Decoupled(new Inst)
  })

  val fsm = Module(new OneMasterOneSlaveFSM)
  fsm.connectMaster(io.pc)
  fsm.connectSlave(io.out)

  fsm.io.self_finished := true.B

  // printf("(ifu) fetch inst %x at pc 0x%x\n", io.out.bits.code,io.pc.bits)
  // printf("(ifu) pc.valid: %b\n", io.pc.valid)

  val code = RawUnclockedNonVoidFunctionCall("fetch_inst", Types.UWord)(io.pc.valid, io.pc.bits)

  // NOTICE: dpi function auto generated with void return
  // see https://github.com/llvm/circt/blob/main/docs/Dialects/FIRRTL/FIRRTLIntrinsics.md#dpi-intrinsic-abi
  io.out.bits.code := code
  io.out.bits.pc   := io.pc.bits
}

object InstFmt     extends ChiselEnum {
  val imm, reg, store, upper, jump, branch = Value
}
object InstType    extends ChiselEnum {
  val none, arithmetic, load, store, jalr, jal, lui, auipc, system = Value
}
class InstMetaInfo extends Bundle     {
  val fmt = InstFmt()
  val typ = InstType()
}

class InstInfoDecoder extends Module {
  val io = IO(new Bundle {
    val opcode = Input(UInt(7.W))
    val valid  = Output(Bool())
    val out    = Output(new InstMetaInfo())
  })

  // opcode[1:0] should always be 11 for 32bit
  io.valid := io.opcode(1, 0).andR
  val opcu = io.opcode(6, 2)

  val lut = Seq(
    "b00000".U -> (InstFmt.imm, InstType.load),
    "b00100".U -> (InstFmt.imm, InstType.arithmetic),
    "b11001".U -> (InstFmt.imm, InstType.jalr),
    "b11100".U -> (InstFmt.imm, InstType.system),
    "b01100".U -> (InstFmt.reg, InstType.arithmetic),
    "b01000".U -> (InstFmt.store, InstType.store),
    "b01101".U -> (InstFmt.upper, InstType.lui),
    "b00101".U -> (InstFmt.upper, InstType.auipc),
    "b11011".U -> (InstFmt.jump, InstType.jal),
    "b11000".U -> (InstFmt.branch, InstType.none)
  ).map { case (key, (fmt, typ)) =>
    key -> {
      val info = Wire(new InstMetaInfo)
      info.fmt := fmt
      info.typ := typ
      info
    }
  }

  io.out := MuxLookup(opcu, 0.U.asTypeOf(new InstMetaInfo()))(lut)
}

class DecodedInstInfo extends InstMetaInfo {
  val imm = Types.UWord
  val rd  = Types.RegAddr
  val rs1 = Types.RegAddr
  val rs2 = Types.RegAddr
}
class DecodedInst     extends Inst         {
  val info = new DecodedInstInfo
}

class IDU extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(new Inst))
    val out = Decoupled(new DecodedInst)
  })

  val fsm = Module(new OneMasterOneSlaveFSM)
  fsm.connectMaster(io.in)
  fsm.connectSlave(io.out)

  io.out.bits.viewAsSupertype(new Inst) := io.in.bits

  // alias
  val res  = io.out.bits.info
  val inst = io.in.bits.code

  val iinfo_dec = Module(new InstInfoDecoder())
  iinfo_dec.io.opcode                   := io.in.bits.code(6, 0)
  res.viewAsSupertype(new InstMetaInfo) := iinfo_dec.io.out

  fsm.io.self_finished := iinfo_dec.io.valid

  /*
  printf("(idu) inst 0x%x at pc 0x%x\n", inst, io.in.bits.pc)
  when(!iinfo_dec.io.valid) {
    printf("(idu) UNKNOWN instruction with opcode 0b%b\n", inst(6, 0))
  }
  when(io.out.valid) {
    printf("(idu) decoded finished fmt %d type %d\n", res.fmt.asUInt, res.typ.asUInt)
  }
  printf("(idu) fsm st %d in.valid %b\n",fsm.io._state, io.in.valid)
   */

  res.rd  := inst(11, 7)
  res.rs1 := inst(19, 15)
  res.rs2 := inst(24, 20)

  // fetch IMM
  val immI = Cat(Fill(21, inst(31)), inst(30, 20))
  val immS = Cat(immI(31, 5), inst(11, 8), inst(7))
  val immB = Cat(immI(31, 12), inst(7), immS(10, 1), 0.U(1.W))
  val immU = Cat(inst(31, 12), 0.U(12.W))
  val immJ = Cat(immI(31, 20), inst(19, 12), inst(20), inst(30, 21), 0.U(1.W))

  res.imm := MuxLookup(iinfo_dec.io.out.fmt, "hBAADF00D".U)(
    Seq(
      InstFmt.imm    -> immI,
      InstFmt.jump   -> immJ,
      InstFmt.store  -> immS,
      InstFmt.branch -> immB,
      InstFmt.upper  -> immU
    )
  )

}

class ALUInput extends Bundle {
  val is_imm = Bool()
  val func3t = UInt(3.W)
  val func7t = UInt(7.W)
  val src1   = Types.UWord
  val src2   = Types.UWord
}

class ALU extends Module {
  val io               = IO(new Bundle {
    val in  = Flipped(Decoupled(new ALUInput))
    val out = Decoupled(Types.UWord)
  })
  val BADCALL_RESVALUE = "hBAADCA11".U

  val fsm = Module(new OneMasterOneSlaveFSM)
  fsm.connectMaster(io.in)
  fsm.connectSlave(io.out)
  fsm.io.self_finished := true.B

  // alias
  val inbits = io.in.bits
  val src1   = inbits.src1
  val src2   = inbits.src2

  val s_src1 = src1.asSInt
  val s_src2 = src2.asSInt

  val shamt = src2(4, 0)

  val add_sub_res = Wire(Types.UWord)
  when(inbits.is_imm || inbits.func7t === 0.U) {
    add_sub_res := src1 + src2
  }.elsewhen(inbits.func7t === "b0100000".U) {
    add_sub_res := src1 - src2
  }.otherwise {
    add_sub_res := BADCALL_RESVALUE
    // printf("(alu) UNKNOWN func7t %d", inbits.func7t)
  }

  val shift_res = Wire(Types.UWord)
  when(inbits.func7t === "b0100000".U) { // sra/srai
    shift_res := (s_src1 >> shamt).asUInt
  }.otherwise { // srl/srli
    shift_res := src1 >> shamt
  }

  io.out.bits := MuxLookup(inbits.func3t, BADCALL_RESVALUE)(
    Seq(
      0.U -> add_sub_res,                    // 000: add/sub/addi
      1.U -> (src1 << shamt),                // 001: sll/slli
      2.U -> Mux(s_src1 < s_src2, 1.U, 0.U), // 010: slt/slti
      3.U -> Mux(src1 < src2, 1.U, 0.U),     // 011: sltu/sltui
      4.U -> (src1 ^ src2),                  // 100: xor/xori
      5.U -> shift_res,                      // 101: srl/srli/sra/srai
      6.U -> (src1 | src2),                  // 110: or/ori
      7.U -> (src1 & src2)                   // 111: and/andi
    )
  )
}

class WriteBackInfo extends Bundle {
  val gpr = GPRegReqIO.TX.Write

  val csr           = CSRegReqIO.TX.Write
  val csr_ecallflag = Bool()

  val nxt_pc = Types.UWord
}
class EXU           extends Module {
  val io = IO(new Bundle {
    val dinst    = Flipped(Decoupled(new DecodedInst))
    val rvec     = GPRegReqIO.TX.VecRead(2)
    val csr_rvec = CSRegReqIO.TX.SingleRead
    val mem_rreq = MemReqIO.ReadTX
    val mem_wreq = MemReqIO.WriteTX
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

  // printf("(exu) inst 0x%x at pc 0x%x\n", dinst.code, dinst.pc)

  /*
  printf("(exu) fsm st %d alu.valid %b mem_rreq.respValid %b\n",MS_fsm.io._state, alu.io.out.valid, io.mem_rreq.respValid)
  when(io.mem_rreq.respValid){
    printf("(exu) MEM read data 0x%x for inst at pc 0x%x\n", io.mem_rreq.data, dinst.pc)
  }
  when(alu.io.out.valid) {
    printf("(exu) ALU result 0x%x for inst at pc 0x%x\n", alu.io.out.bits, dinst.pc)
  }
  when(io.out.valid) {
    printf("(exu) finish exu for inst at pc 0x%x\n", dinst.pc)
  }
   */

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

//  printf("(exu) reg(%d) 0x%x reg(%d) 0x%x\n", dinst.info.rs1,reg_v1,dinst.info.rs2, reg_v2)
//  printf("(exu) imm 0x%x\n", dinst.info.imm)

  val mem_addr                     = reg_v1 + dinst.info.imm
  val mem_addr_unalign_part        = mem_addr(1, 0)
  val mem_addr_unalign_part_bitlen = mem_addr_unalign_part << 3

  val mem_raddr     = io.mem_rreq.addr

  val mem_raw_rdata = Reg(Types.UWord)

  val s_rmem_noneed :: s_rmem_wait :: s_rmem_ok :: Nil = Enum(3)

  val mem_read_state = RegInit(s_rmem_noneed)
  val is_load        = dinst.info.typ === InstType.load

  when(io.mem_rreq.respValid) {
    mem_raw_rdata := io.mem_rreq.data
  }

  mem_read_state := MuxLookup(mem_read_state, s_rmem_noneed)(
    Seq(
      s_rmem_noneed -> Mux(is_load, s_rmem_wait, s_rmem_noneed),
      s_rmem_wait   -> Mux(io.mem_rreq.respValid, s_rmem_ok, s_rmem_wait),
      s_rmem_ok     -> Mux(MS_fsm.io.slave_ready, s_rmem_noneed, s_rmem_ok)
    )
  )

//  printf("(exu) mem_read_state %d selve rdy %d\n", mem_read_state, MS_fsm.io.slave_ready)

  val mem_data = mem_raw_rdata >> mem_addr_unalign_part_bitlen


  io.mem_rreq.en := is_load && (mem_read_state === s_rmem_wait)
  MS_fsm.io.self_finished := alu.io.out.valid && (
    (!is_load) || (mem_read_state === s_rmem_ok)
  )

  mem_raddr := mem_addr

//when(mem_ren) {
//  printf("(exu) @pc 0x%x\n", dinst.pc)
//  printf("(exu) LOAD from addr 0x%x\n", mem_raddr)
//}

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
          MemOp.byte     -> Cat(Fill(24, mem_data(7)), mem_data(7, 0)),
          MemOp.halfword -> Cat(Fill(16, mem_data(15)), mem_data(15, 0)),
          MemOp.word     -> mem_data,
          MemOp.lbu      -> Cat(Fill(24, 0.U), mem_data(7, 0)),
          MemOp.lhu      -> Cat(Fill(16, 0.U), mem_data(15, 0))
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
        // printf("(exu) UNKNOWN SYSTEM func3t %d\n", func3t)
      }
    }
  }
  when(dinst.info.typ === InstType.load) {
    when(!MemOp.isValidLoadOp(func3t)) {
//      printf("(exu) UNKNOWN LOAD func3t %d\n", func3t)
    }
  }

  // mem write

  // for now sw only consider align addr

  val mem_wdata = io.mem_wreq.data
  val mem_waddr = io.mem_wreq.addr
  val mem_wen   = io.mem_wreq.en
  val mem_wmask = io.mem_wreq.mask

  val is_store = dinst.info.typ === InstType.store

  mem_wdata := reg_v2 << mem_addr_unalign_part_bitlen
  mem_waddr := mem_addr
  mem_wmask := MuxLookup(func3t, 0.U)(
    Seq(
      MemOp.byte     -> (1.U(4.W) << mem_addr_unalign_part),
      MemOp.halfword -> (3.U(4.W) << mem_addr_unalign_part),
      MemOp.word     -> 15.U(4.W)
    )
  )

//   printf("(exu) STORE to addr 0x%x data 0x%x mask 0b%b\n", mem_waddr, mem_wdata, mem_wmask)
  val s_wmem_noneed :: s_wmem_wait :: s_wmem_ok :: Nil = Enum(3)
  val mem_write_state = RegInit(s_wmem_noneed)
  mem_write_state := MuxLookup(mem_write_state, s_wmem_noneed)(
    Seq(
      s_wmem_noneed -> Mux(is_store, s_wmem_wait, s_wmem_noneed),
      s_wmem_wait   -> Mux(io.mem_wreq.done, s_wmem_ok, s_wmem_wait),
      s_wmem_ok     -> Mux(MS_fsm.io.slave_ready, s_wmem_noneed, s_wmem_ok)
    )
  )

  mem_wen := is_store && (mem_write_state === s_wmem_wait)

  when(dinst.info.typ === InstType.store) {
    when(!MemOp.isValidStoreOp(func3t)) {
       printf("(exu) UNKNOWN STORE func3t %d\n", func3t)
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

class WBU extends Module {
  val io = IO(new Bundle {
    val data  = Flipped(Decoupled(new WriteBackInfo))
    val gpr = GPRegReqIO.TX.Write
    val csr = CSRegReqIO.TX.Write
    val is_ecall = Output(Bool())
    val done = Output(Bool())
  })


  val wbinfo = io.data.bits
  val valid  = io.data.valid

  io.data.ready := valid

  // printf("(wbu) write back gpr en %b addr %d data 0x%x\n", wbinfo.gpr.en, wbinfo.gpr.addr, wbinfo.gpr.data)
  // printf("(wbu) valid %b\n", valid)


  io.gpr.en   := wbinfo.gpr.en && valid
  io.gpr.addr := wbinfo.gpr.addr
  io.gpr.data := wbinfo.gpr.data

  io.csr.en   := wbinfo.csr.en && valid
  io.csr.addr := wbinfo.csr.addr
  io.csr.data := wbinfo.csr.data
  io.is_ecall := wbinfo.csr_ecallflag && valid

  io.done := valid
}
