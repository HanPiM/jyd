package regfile

import chisel3._
import chisel3.util.Counter
import cpu.Types
import cpu.Types.Ops._
import chisel3.util.MuxLookup

class RegReadBundle(N: Int) extends Bundle {
  require((1 << Types.BitWidth.reg_addr) >= N)
  val addr = Input(Vec(N, Types.RegAddr))
  val data = Output(Vec(N, Types.RegAddr))
}

class RegisterFile(READ_PORTS: Int = 2) extends Module {
  val N_REG = 1 << Types.BitWidth.reg_addr

  val io  = IO(new Bundle {
    val wen   = Input(Bool())
    val waddr = Input(Types.RegAddr)
    val wdata = Input(Types.UWord)

    val rvec = new RegReadBundle(READ_PORTS)
  })
  val reg = RegInit(VecInit(Seq.fill(N_REG)(0.UWord)))

  when(io.wen) {
    reg(io.waddr) := io.wdata
  }
  for (i <- 0 until READ_PORTS) {
    when(io.rvec.addr(i) === 0.U) {
      io.rvec.data(i) := 0.U
    }.otherwise {
      io.rvec.data(i) := reg(io.rvec.addr(i))
    }
  }
}

class ControlStatusRegisterFile extends Module {
  val io = IO(new Bundle {
    val addr     = Input(UInt(12.W))
    val wdata    = Input(Types.UWord)
    val wen      = Input(Bool())
    val is_ecall = Input(Bool())
    val ren      = Input(Bool())
    val rdata    = Output(Types.UWord)
  })

  val mcycle64 = RegInit(0.U(64.W))
  mcycle64 := mcycle64 + 1.U

  val mvendor_id = "h79737978".U(32.W) // ysyx
  val march_id   = "d25100261".U(32.W)

  val mstatus = RegInit("h00001800".U(32.W))
  val mepc    = RegInit(0.U(32.W))
  val mcause  = RegInit(0.U(32.W))
  val mtvec   = RegInit(0.U(32.W))

  when(io.ren) {
    io.rdata := MuxLookup(io.addr, 0.U)(
      Seq(
        "hB00".U -> mcycle64(31, 0),  // mcycle
        "hB80".U -> mcycle64(63, 32), // mcycleh
        "h300".U -> mstatus,
        "h341".U -> mepc,
        "h342".U -> mcause,
        "h305".U -> mtvec
      )
    )

  }.otherwise {
    io.rdata := 0.U
  }
}
