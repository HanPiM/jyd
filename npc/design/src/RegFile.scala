package regfile

import chisel3._
import chisel3.util.{Cat, Counter, MuxLookup}

import common_def._
import dpiwrap._

import Types.Ops._

class RegFileIO(AddrWidth: Int, data_width: Int = Types.BitWidth.word) {

  def _AddrT = UInt(AddrWidth.W)
  def _DataT = UInt(data_width.W)

  class VecReadIO(N: Int) extends Bundle {
    require((1 << AddrWidth) >= N)
    // val en   = Input(Bool())
    val addr = Input(Vec(N, _AddrT))
    val data = Output(Vec(N, _DataT))
  }
  class SingleReaIO       extends Bundle {
    val en   = Input(Bool())
    val addr = Input(_AddrT)
    val data = Output(_DataT)
  }
  class WriteIO           extends Bundle {
    val en   = Input(Bool())
    val addr = Input(_AddrT)
    val data = Input(_DataT)
  }
  object RX {
    def VecRead(N: Int) = new VecReadIO(N)
    def SingleRead = new SingleReaIO()
    def Write      = new WriteIO()
  }
  object TX {
    def VecRead(N: Int) = Flipped(RX.VecRead(N))
    def SingleRead = Flipped(RX.SingleRead)
    def Write      = Flipped(RX.Write)
  }
}

// object GPRegReqIO extends MetaRegReqIO(addr_width = Types.BitWidth.reg_addr)

object GPRegReqIO {
  def WriteTX(implicit p: CPUParameters) = new RegFileIO(p.gprAddrWidth).TX.Write
  def ReadVecTX(N: Int)(implicit p: CPUParameters) = new RegFileIO(p.gprAddrWidth).TX.VecRead(N)
}

object CSRegReqIO extends RegFileIO(AddrWidth = Types.BitWidth.csr_addr)

class GPRIO(
  N_RD:         Int = 2
)(
  implicit cfg: CPUParameters)
    extends Bundle {
  val read  = new RegFileIO(cfg.gprAddrWidth).RX.VecRead(N_RD)
  val write = new RegFileIO(cfg.gprAddrWidth).RX.Write
}

class RegisterFile(
  READ_PORTS:   Int = 2
)(
  implicit cfg: CPUParameters)
    extends Module {
  val io = IO(new GPRIO(READ_PORTS))

  val reg = Reg(Vec(cfg.GPRNum, Types.UWord))

  // val a0 = IO(Output(Types.UWord))
  // a0 := reg(10)
  // dontTouch(a0)

  when(io.write.en) {
    // Chisel will optimize to remove reg[0] instance
    when(io.write.addr =/= 0.U) {
      reg(io.write.addr) := io.write.data
    }

    ClockedCallVoidDPIC("gpr_upd")(
      clock,
      io.write.en,
      io.write.addr.pad(8),
      io.write.data
    )
  }
  for (i <- 0 until READ_PORTS) {
    when(io.read.addr(i) === 0.U) {
      io.read.data(i) := 0.U
    }.otherwise {
      io.read.data(i) := reg(io.read.addr(i))
    }
  }
}

object CSRAddr {
  val mstatus   = "h300".U(12.W)
  val mtvec     = "h305".U(12.W)
  val mepc      = "h341".U(12.W)
  val mcause    = "h342".U(12.W)
  val mcycle    = "hB00".U(12.W)
  val mcycleh   = "hB80".U(12.W)
  val mvendorid = "hF11".U(12.W)
  val marchid   = "hF12".U(12.W)
}

class CSRIO extends Bundle {
  val is_ecall = Input(Bool())
  val mcycle64 = Output(UInt(64.W))
  val read     = CSRegReqIO.RX.SingleRead
  val write    = CSRegReqIO.RX.Write
}

class ControlStatusRegisterFile extends Module {
  val io = IO(new CSRIO)

  // val mcycle64 = RegInit(0.U(64.W))
  // mcycle64 := mcycle64 + 1.U
  // val mcycleHi = RegInit(0.U(32.W))
  val mcycleHi = Wire(UInt(32.W))
  val mcycleLo = RegInit(0.U(32.W))
  // Discard mcycleHi for smaller area
  // NOTICE:
  // 32 bit mcycle support about 50 days at 1GHz
  mcycleHi := 0.U
  mcycleLo := mcycleLo + 1.U
  // when(mcycleLo === "hffffffff".U) {
  //   mcycleHi := mcycleHi + 1.U
  // }
  //
  val mcycle64 = Cat(mcycleHi, mcycleLo)
  io.mcycle64 := mcycle64

  val mvendor_id = "h79737978".U(32.W) // ysyx
  val march_id   = "d25100261".U(32.W)

  // Writable CSRs
  // 0: mstatus
  // val waregs = RegInit(VecInit("h00001800".U(32.W) +: Seq.fill(3)(0.U(32.W))))

  val waregs = Reg(Vec(4, UInt(32.W)))
  when(reset.asBool) {
    waregs(0) := "h00001800".U // mstatus
  }

  val walut = Seq(
    CSRAddr.mstatus -> 0.U,
    CSRAddr.mepc    -> 1.U,
    CSRAddr.mcause  -> 2.U,
    CSRAddr.mtvec   -> 3.U
  )
  val widx  = MuxLookup(io.write.addr, 0.U)(walut)
  val ridx  = MuxLookup(io.read.addr, 0.U)(walut)

  when(io.read.en) {
    io.read.data := MuxLookup(io.read.addr, waregs(ridx))(
      Seq(
        CSRAddr.mcycle    -> mcycle64(31, 0),
        CSRAddr.mcycleh   -> mcycle64(63, 32),
        CSRAddr.mvendorid -> mvendor_id,
        CSRAddr.marchid   -> march_id
      )
    )
  }.otherwise {
    // Chisel will optimize DontCare to remove check read_en logic
    io.read.data := DontCare
  }

  val en_wrtie = (io.write.en) || (io.is_ecall && (io.write.addr === CSRAddr.mepc))

  when(en_wrtie) {
    waregs(widx) := io.write.data
    when(io.is_ecall && (io.write.addr === CSRAddr.mepc)) {
      waregs(2) := 11.U // mcause = 11 for ecall from M-mode
    }
  }

}
