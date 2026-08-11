package jyd

import chisel3._
import chisel3.util._
import simplebus._

object CameraRegister {
  val Status     = 0.U(2.W)
  val FrameCount = 1.U(2.W)
  val SampleRgb  = 2.U(2.W)
  val Control    = 3.U(2.W)
}

/** Deterministic camera register model for the ordinary RTL simulation SoC.
  *
  * This intentionally models only the software-visible control plane. The
  * video data path remains external hardware in the FPGA build.
  */
class SimpleBusCameraSim extends Module {
  val io = IO(SimpleBusIO.Slave)
  io.dontCareResp()
  io.req_ready := true.B

  val control    = RegInit(0.U(1.W))
  val frameCount = RegInit(1.U(32.W))
  val frameTimer = RegInit(0.U(8.W))
  frameTimer := frameTimer + 1.U
  when(frameTimer === 0.U) {
    frameCount := frameCount + 1.U
  }

  val doReq = io.req_valid && io.req_ready
  when(doReq && io.wen && io.addr(3, 2) === CameraRegister.Control) {
    control := io.wdata(0)
  }

  val readData = MuxLookup(io.addr(3, 2), 0.U(32.W))(
    Seq(
      CameraRegister.Status     -> "h27".U(32.W),
      CameraRegister.FrameCount -> frameCount,
      CameraRegister.SampleRgb  -> "h9a".U(32.W),
      CameraRegister.Control    -> control.pad(32)
    )
  )

  val respDataPipe0 = RegEnable(readData, doReq)
  val respDataReg   = RegNext(respDataPipe0)
  io.resp_valid := RegNext(RegNext(doReq, false.B), false.B)
  io.rdata      := respDataReg
}

/** CPU-clock-domain register bank connected to the passive camera monitor in
  * the AX7035B outer wrapper.
  */
class SimpleBusFPGACameraRegs extends Module {
  val io = IO(new Bundle {
    val bus           = SimpleBusIO.Slave
    val cameraStatus  = Input(UInt(32.W))
    val frameCount    = Input(UInt(32.W))
    val sampleRgb     = Input(UInt(8.W))
    val forceColorbar = Output(Bool())
  })
  io.bus.dontCareResp()
  io.bus.req_ready := true.B

  val control = RegInit(0.U(1.W))
  val doReq   = io.bus.req_valid && io.bus.req_ready
  when(doReq && io.bus.wen && io.bus.addr(3, 2) === CameraRegister.Control) {
    control := io.bus.wdata(0)
  }

  val readData = MuxLookup(io.bus.addr(3, 2), 0.U(32.W))(
    Seq(
      CameraRegister.Status     -> io.cameraStatus,
      CameraRegister.FrameCount -> io.frameCount,
      CameraRegister.SampleRgb  -> io.sampleRgb.pad(32),
      CameraRegister.Control    -> control.pad(32)
    )
  )

  val respDataPipe0 = RegEnable(readData, doReq)
  val respDataReg   = RegNext(respDataPipe0)
  io.bus.resp_valid := RegNext(RegNext(doReq, false.B), false.B)
  io.bus.rdata      := respDataReg
  io.forceColorbar  := control(0)
}
