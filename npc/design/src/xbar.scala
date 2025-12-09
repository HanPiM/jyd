package xbar
import chisel3._
import chisel3.util._

import axi4._

// never map more than 1 range to one slave
class AXI4LiteXBar(mappings: Seq[((UInt, UInt), AXI4IO.SlaveT)]) extends Module {

  // println(s"AXI4LiteXBar mappings: ${mappings.map(_._1)}")
  // println(s"AXI4LiteXBar parameter: ${mappings.map(_._2)}")

  require(mappings.nonEmpty, "AXI4LiteXBar requires non-empty mappings.")
  require(mappings.size > 1, "AXI4LiteXBar requires at least two mappings.")

  val axiParam = mappings.head._2

  val io = IO(new Bundle {
    val in = AXI4IO.Slave
    val slaves = Vec(mappings.size, AXI4IO.Master)
  })

  def connect() = {
    io.slaves.zip(mappings).foreach { case (sio, (_, s)) =>
      AXI4IO.connectMasterSlave(sio, s)
    }
  }

  val isAR = Wire(Vec(mappings.size, Bool()))
  val isAW = Wire(Vec(mappings.size, Bool()))

  val hasLastRdReq = RegInit(false.B)
  val hasLastWrReq = RegInit(false.B)
  val lastRdReqIdx = RegInit(0.U(log2Ceil(mappings.size).W))
  val lastWrReqIdx = RegInit(0.U(log2Ceil(mappings.size).W))

  val master = io.in

  // val slaveIO = mappings.map(_._2.ioImp)

  val slaveIO = io.slaves

  for ((((addrBeg, addrEnd), _), i) <- mappings.zipWithIndex) {
    isAR(i) := (master.araddr >= addrBeg) && (master.araddr < addrEnd)
    isAW(i) := (master.awaddr >= addrBeg) && (master.awaddr < addrEnd)

    slaveIO(i).arvalid := isAR(i) && master.arvalid
    slaveIO(i).awvalid := isAW(i) && master.awvalid

    when(slaveIO(i).arvalid && slaveIO(i).arready) {
      lastRdReqIdx := i.U
      hasLastRdReq := true.B
    }
    when(slaveIO(i).awvalid && slaveIO(i).awready) {
      lastWrReqIdx := i.U
      hasLastWrReq := true.B
    }
  }

  master.arready := Mux1H(isAR, io.slaves.map(_.arready))
  master.awready := Mux1H(isAW, io.slaves.map(_.awready))

  master.rvalid := slaveIO(lastRdReqIdx).rvalid && hasLastRdReq
  AXI4IO.noShakeConnectR(master, slaveIO(lastRdReqIdx))
  for (i <- mappings.indices) {
    slaveIO(i).rready := hasLastRdReq && (i.U === lastRdReqIdx) && master.rready
  }

  master.wready := slaveIO(lastWrReqIdx).wready && hasLastWrReq
  for (i <- mappings.indices) {
    slaveIO(i).wvalid := hasLastWrReq && (i.U === lastWrReqIdx) && master.wvalid
  }

  master.bvalid := slaveIO(lastWrReqIdx).bvalid && hasLastWrReq
  AXI4IO.noShakeConnectB(master, slaveIO(lastWrReqIdx))

  for (i <- mappings.indices) {
    slaveIO(i).bready := hasLastWrReq && (i.U === lastWrReqIdx) && master.bready
  }

  when(master.rvalid && master.rready) {
    hasLastRdReq := false.B
  }
  when(master.bvalid && master.bready) {
    hasLastWrReq := false.B
  }

  io.slaves.foreach { s =>
    AXI4IO.noShakeConnectAR(master, s)
    AXI4IO.noShakeConnectAW(master, s)
    AXI4IO.noShakeConnectW(master, s)
  }

}
