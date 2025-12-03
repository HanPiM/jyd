package regfile

import chisel3._
import chisel3.util.Counter
import cpu.Types
import cpu.Types.Ops._
import chisel3.util.MuxLookup

class MetaRegReqIO(addr_width: Int = Types.BitWidth.reg_addr, data_width: Int = Types.BitWidth.word) {
  class _VecReadRX(N: Int) extends Bundle {
    require((1 << addr_width) >= N)
    val en   = Input(Bool())
    val addr = Input(Vec(N, Types.RegAddr))
    val data = Output(Vec(N, Types.UWord))
  }
  class _SingleReadRX      extends Bundle {
    val en   = Input(Bool())
    val addr = Input(Types.RegAddr)
    val data = Output(Types.UWord)
  }
  class _WriteRX           extends Bundle {
    val en   = Input(Bool())
    val addr = Input(Types.RegAddr)
    val data = Input(Types.UWord)
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

class RegisterFile(READ_PORTS: Int = 2) extends Module {
  val N_REG = 1 << Types.BitWidth.reg_addr

  val io  = IO(new Bundle {
    val write = GPRegReqIO.RX.Write
    val rvec  = GPRegReqIO.RX.VecRead(READ_PORTS)
  })
  val reg = RegInit(VecInit(Seq.fill(N_REG)(0.UWord)))

  when(io.write.en) {
    reg(io.write.addr) := io.write.data
    printf("(RegFile) write reg[%d] <= 0x%x\n", io.write.addr, io.write.data)
  }
  for (i <- 0 until READ_PORTS) {
    when(io.rvec.addr(i) === 0.U) {
      io.rvec.data(i) := 0.U
    }.otherwise {
      io.rvec.data(i) := reg(io.rvec.addr(i))
      printf("(RegFile) read reg[%d] => 0x%x\n", io.rvec.addr(i), io.rvec.data(i))
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

class ControlStatusRegisterFile extends Module {
  val io = IO(new Bundle {
    val is_ecall = Input(Bool())
    val read     = CSRegReqIO.RX.SingleRead
    val write    = CSRegReqIO.RX.Write
  })

  val mcycle64 = RegInit(0.U(64.W))
  mcycle64 := mcycle64 + 1.U

  val mvendor_id = "h79737978".U(32.W) // ysyx
  val march_id   = "d25100261".U(32.W)

  // Writable CSRs
  // 0: None
  // 1: mstatus
  val waregs = RegInit(VecInit(0.U +: "h00001800".U(32.W) +: Seq.fill(3)(0.U(32.W))))
  val walut  = Seq(
    CSRAddr.mstatus -> 1.U,
    CSRAddr.mepc    -> 2.U,
    CSRAddr.mcause  -> 3.U,
    CSRAddr.mtvec   -> 4.U
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
  }.otherwise {
    io.read.data := 0.U
  }

  when(io.write.en && (widx =/= 0.U)) {
    waregs(widx) := io.write.data
    when(io.is_ecall && (io.write.addr === CSRAddr.mepc)) {
      waregs(3) := 11.U // mcause = 11 for ecall from M-mode
    }
  }

}
