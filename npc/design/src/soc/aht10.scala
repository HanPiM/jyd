package jyd

import chisel3._
import chisel3.util._
import simplebus._

object AHT10Register {
  val Status    = 0.U(2.W)
  val TempX10   = 1.U(2.W)
  val HumiX10   = 2.U(2.W)
  val SampleSeq = 3.U(2.W)
}

/** Deterministic AHT10 model for the ordinary RTL simulation SoC.
  *
  * The model deliberately avoids simulating I2C. It validates the CPU -> MMIO
  * -> software path with the values measured by the verified FPGA prototype.
  */
class SimpleBusAHT10Sim extends Module {
  val io = IO(SimpleBusIO.Slave)
  io.dontCareResp()
  io.req_ready := true.B

  val doReq = io.req_valid && io.req_ready
  val readData = MuxLookup(io.addr(3, 2), 0.U(32.W))(
    Seq(
      AHT10Register.Status    -> 1.U(32.W),
      AHT10Register.TempX10   -> 328.U(32.W),
      AHT10Register.HumiX10   -> 654.U(32.W),
      AHT10Register.SampleSeq -> 1.U(32.W)
    )
  )

  val respDataPipe0 = RegEnable(readData, doReq)
  val respDataReg   = RegNext(respDataPipe0)
  io.resp_valid := RegNext(RegNext(doReq, false.B), false.B)
  io.rdata      := respDataReg
}

/** Synthesizable FPGA AHT10 core.
  *
  * The resource RTL contains the verified 50 MHz controller and I2C FSM plus
  * a snapshot/toggle bundled-data CDC receiver in the CPU clock domain.
  */
class JYDAHT10FPGABlackBox extends BlackBox with HasBlackBoxResource {
  override def desiredName: String = "jyd_aht10_fpga"

  val io = IO(new Bundle {
    val clk_50mhz      = Input(Clock())
    val cpu_clk        = Input(Clock())
    val sensor_reset   = Input(Bool())
    val cpu_reset      = Input(Bool())
    val scl_in         = Input(Bool())
    val sda_in         = Input(Bool())
    val scl_drive_low  = Output(Bool())
    val sda_drive_low  = Output(Bool())
    val status         = Output(UInt(3.W))
    val temperature_x10 = Output(SInt(32.W))
    val humidity_x10   = Output(UInt(32.W))
    val sample_seq     = Output(UInt(32.W))
  })

  addResource("/aht10/jyd_aht10_fpga.v")
  addResource("/aht10/jyd_aht10_controller.v")
  addResource("/aht10/jyd_aht10_i2c_master.v")
}

class SimpleBusFPGAAHT10 extends Module {
  val io = IO(new Bundle {
    val bus          = SimpleBusIO.Slave
    val clk_50Mhz    = Input(Clock())
    val rst          = Input(Bool())
    val sclIn        = Input(Bool())
    val sdaIn        = Input(Bool())
    val sclDriveLow  = Output(Bool())
    val sdaDriveLow  = Output(Bool())
  })
  io.bus.dontCareResp()
  io.bus.req_ready := true.B

  val sensor = Module(new JYDAHT10FPGABlackBox)
  sensor.io.clk_50mhz    := io.clk_50Mhz
  sensor.io.cpu_clk      := clock
  sensor.io.sensor_reset := io.rst
  sensor.io.cpu_reset    := reset.asBool
  sensor.io.scl_in       := io.sclIn
  sensor.io.sda_in       := io.sdaIn
  io.sclDriveLow         := sensor.io.scl_drive_low
  io.sdaDriveLow         := sensor.io.sda_drive_low

  val doReq = io.bus.req_valid && io.bus.req_ready
  val readData = MuxLookup(io.bus.addr(3, 2), 0.U(32.W))(
    Seq(
      AHT10Register.Status    -> sensor.io.status.pad(32),
      AHT10Register.TempX10   -> sensor.io.temperature_x10.asUInt,
      AHT10Register.HumiX10   -> sensor.io.humidity_x10,
      AHT10Register.SampleSeq -> sensor.io.sample_seq
    )
  )

  val respDataPipe0 = RegEnable(readData, doReq)
  val respDataReg   = RegNext(respDataPipe0)
  io.bus.resp_valid := RegNext(RegNext(doReq, false.B), false.B)
  io.bus.rdata      := respDataReg
}
