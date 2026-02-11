package regfile

import chisel3._
import chisel3.util.{Cat,Counter, MuxLookup}

import chisel3.util.circt.dpi._

import common_def._
import Types.Ops._

class MetaRegReqIO(addr_width: Int = Types.BitWidth.reg_addr, data_width: Int = Types.BitWidth.word) {

  def _AddrT = UInt(addr_width.W)
  def _DataT = UInt(data_width.W)

  class _VecReadRX(N: Int) extends Bundle {
    require((1 << addr_width) >= N)
    val en   = Input(Bool())
    val addr = Input(Vec(N, _AddrT))
    val data = Output(Vec(N, _DataT))
  }
  class _SingleReadRX      extends Bundle {
    val en   = Input(Bool())
    val addr = Input(_AddrT)
    val data = Output(_DataT)
  }
  class _WriteRX           extends Bundle {
    val en   = Input(Bool())
    val addr = Input(_AddrT)
    val data = Input(_DataT)
  }
  object RX {
    def VecRead(N: Int) = new _VecReadRX(N)
    def SingleRead = new _SingleReadRX()
    def Write      = new _WriteRX()
  }
  object TX {
    def VecRead(N: Int) = Flipped(RX.VecRead(N))
    def SingleRead = Flipped(RX.SingleRead)
    def Write      = Flipped(RX.Write)
  }
}

object GPRegReqIO extends MetaRegReqIO()
object CSRegReqIO extends MetaRegReqIO(addr_width = Types.BitWidth.csr_addr)

class GPRIO(N_RD:Int=2) extends Bundle {
  val read  = GPRegReqIO.RX.VecRead(N_RD)
  val write = GPRegReqIO.RX.Write
}

class RegisterFile(READ_PORTS: Int = 2) extends Module {
  val N_REG = 1 << Types.BitWidth.reg_addr

  val io  = IO(new GPRIO(READ_PORTS))

  val reg = RegInit(VecInit(Seq.fill(N_REG)(0.UWord)))
  reg(0) := 0.UWord

  // io.a0 := reg(10.U)

  when(io.write.en) {
    reg(io.write.addr) := io.write.data

    RawClockedVoidFunctionCall("gpr_upd")(
      clock,
      io.write.en,
      io.write.addr.pad(8),
      io.write.data
    )

    //printf("(RegFile) write reg[%d] <= 0x%x\n", io.write.addr, io.write.data)
  }
  for (i <- 0 until READ_PORTS) {
    when(io.read.addr(i) === 0.U) {
      io.read.data(i) := 0.U
    }.otherwise {
      io.read.data(i) := reg(io.read.addr(i))
      //     printf("(RegFile) read reg[%d] => 0x%x\n", io.rvec.addr(i), io.rvec.data(i))
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
  val read     = CSRegReqIO.RX.SingleRead
  val write    = CSRegReqIO.RX.Write
}
class ControlStatusRegisterFile extends Module {
  val io = IO(new CSRIO)

  val mcycle64 = RegInit(0.U(64.W))
  mcycle64 := mcycle64 + 1.U
  // val mcycleHi = RegInit(0.U(32.W))
  // val mcycleLo = RegInit(0.U(32.W))
  // mcycleLo := mcycleLo + 1.U
  // when(mcycleLo === "hffffffff".U) {
  //   mcycleHi := mcycleHi + 1.U
  // }
  // val mcycle64 = Cat(mcycleHi, mcycleLo)

  val mvendor_id = "h79737978".U(32.W) // ysyx
  val march_id   = "d25100261".U(32.W)

  // Writable CSRs
  // 0: mstatus
  val waregs = RegInit(VecInit("h00001800".U(32.W) +: Seq.fill(3)(0.U(32.W))))
  val walut  = Seq(
    CSRAddr.mstatus -> 0.U,
    CSRAddr.mepc    -> 1.U,
    CSRAddr.mcause  -> 2.U,
    CSRAddr.mtvec   -> 3.U
  )
  val widx   = MuxLookup(io.write.addr, 0.U)(walut)
  val ridx   = MuxLookup(io.read.addr, 0.U)(walut)

  when(io.read.en) {
    io.read.data := MuxLookup(io.read.addr, waregs(ridx))(
      Seq(
        CSRAddr.mcycle    -> mcycle64(31, 0),
        CSRAddr.mcycleh   -> mcycle64(63, 32),
        CSRAddr.mvendorid -> mvendor_id,
        CSRAddr.marchid   -> march_id
      )
    )
 //   printf("(CSR) read CSR[0x%x] => 0x%x\n", io.read.addr, io.read.data)
  }.otherwise {
    io.read.data := 0.U
  }

  val en_wrtie= (io.write.en)||(io.is_ecall && (io.write.addr === CSRAddr.mepc))

  when(en_wrtie) {
    waregs(widx) := io.write.data
    when(io.is_ecall && (io.write.addr === CSRAddr.mepc)) {
//      printf("(CSR) ecall detected")
      waregs(2) := 11.U // mcause = 11 for ecall from M-mode
    }
  }

}
