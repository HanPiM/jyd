package cpu
import chisel3._
import chisel3.util._
import common_def._
import busfsm._

import axi4._

class LSUInput extends Bundle {
  val isLoad       = Bool()
  val isStore      = Bool()
  val destAddr     = Types.UWord
  val storeData    = Types.UWord
  val func3t       = UInt(3.W)
  val exuWriteBack = new WriteBackInfo
}

class LSUIO extends Bundle {
  val mem = AXI4IO.Master

  val in  = Flipped(Decoupled(new LSUInput))
  val out = Decoupled(new WriteBackInfo)
}

class LSU extends Module {
  val io = IO(new LSUIO)

  val selfFinish = Wire(Bool())
  val fsm = InnerBusCtrl(io.in, io.out, selfFinish)

  val outWriteBackInfo   = io.out.bits
  val inExuWriteBackInfo = io.in.bits.exuWriteBack

  // mem

  // object MemOp {
  //   val byte     = 0.U
  //   val halfword = 1.U
  //   val word     = 2.U
  //   val lbu      = 4.U
  //   val lhu      = 5.U
  //
  // }

  val memRdRawData = Reg(Types.UWord)
  // val memRdRawData = Wire(Types.UWord)

  val isLoad  = io.in.bits.isLoad && io.in.valid
  val isStore = io.in.bits.isStore && io.in.valid
  val isMemOp = isLoad || isStore

  val memWDone = Reg(Bool())
  val memRDone = Reg(Bool())

  val memAddrSent = Reg(Bool())

  val memOPDone = memWDone || memRDone

  val memIO = io.mem

  selfFinish := ((!isMemOp) || memOPDone)

  val memAddr            = io.in.bits.destAddr
  val func3t             = io.in.bits.func3t
  val storeData          = io.in.bits.storeData
  val memAddrUnalignPart = memAddr(1, 0)
  // val memAddrUnalignPartBitlen = memAddrUnalignPart << 3

  memIO.araddr  := memAddr
  memIO.arvalid := isLoad && (!memRDone) && (!memAddrSent)

  // Weird optmization from chisel make this mux can reuse
  // other mux logic and reduce area
  // synthesis area smaller than using func3t directly
  //
  val memOpSize = func3t(1, 0)
  // val memOpSize = Mux(func3t(1), 2.U, func3t(0))

  val memOpIsWord = func3t(1)
  val memOpIsHalf = (~func3t(1)) && func3t(0)
  val memOpIsByte = (~func3t(1)) && (~func3t(0))

  // val memOpSize = MuxLookup(func3t, 0.U)(
  //   Seq(
  //     MemOp.byte     -> 0.U,
  //     MemOp.halfword -> 1.U,
  //     MemOp.word     -> 2.U,
  //     MemOp.lbu      -> 0.U,
  //     MemOp.lhu      -> 1.U
  //   )
  // )

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
  // val memRdData = memRdRawData

  val memRdDataByte = Cat(Fill(24, memRdData(7) && (~func3t(2))), memRdData(7, 0))
  val memRdDataHalf = Cat(Fill(16, memRdData(15) && (~func3t(2))), memRdData(15, 0))
  val loadResult    = Mux(func3t(1), memRdData, Mux(func3t(0), memRdDataHalf, memRdDataByte))

  // val loadResult = MuxLookup(func3t, GARBAGE_UNINIT_VALUE)(
  //   Seq(
  //     MemOp.byte     -> Cat(Fill(24, memRdData(7)), memRdData(7, 0)),
  //     MemOp.halfword -> Cat(Fill(16, memRdData(15)), memRdData(15, 0)),
  //     MemOp.word     -> memRdData,
  //     MemOp.lbu      -> Cat(Fill(24, 0.U), memRdData(7, 0)),
  //     MemOp.lhu      -> Cat(Fill(16, 0.U), memRdData(15, 0))
  //   )
  // )

  when(memIO.arvalid && memIO.arready) {
    memAddrSent := true.B
  }

  // memRdRawData := memIO.rdata
  when(memIO.rvalid && !memRDone) {
    memRdRawData := memIO.rdata
    memRDone     := true.B
  }
  // val downStreamRecved = Reg(Bool())
  // dontTouch(downStreamRecved)
  // memIO.rready := io.out.ready

  // TODO: fix SoC AXI4 Delayer support delay ready
  memIO.rready := true.B
  // when(io.out.ready && io.out.valid) {
  //   downStreamRecved := true.B
  // }.elsewhen(io.dinst.valid && isMemOp){
  //   downStreamRecved := false.B
  // }
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

  // NOTE: need keep to make simulation bind signal by names
  dontTouch(io.mem)

  // dontTouch(memIO.wlast)

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

  // memWData := storeData << memAddrUnalignPartBitlen
  memWData := MuxLookup(memAddrUnalignPart, 0.U(32.W))(
    Seq(
      0.U -> storeData,
      1.U -> Cat(storeData(23, 0), 0.U(8.W)),
      2.U -> Cat(storeData(15, 0), 0.U(16.W)),
      3.U -> Cat(storeData(7, 0), 0.U(24.W))
    )
  )
  // memWData := storeData
  val wByteMask = MuxLookup(memAddrUnalignPart, 0.U(4.W))(
    Seq(
      0.U -> "b0001".U(4.W),
      1.U -> "b0010".U(4.W),
      2.U -> "b0100".U(4.W),
      3.U -> "b1000".U(4.W)
    )
  )
  val wByteMaskHalf = MuxLookup(memAddrUnalignPart, 0.U(4.W))(
    Seq(
      0.U -> "b0011".U(4.W),
      1.U -> "b0110".U(4.W),
      2.U -> "b1100".U(4.W)
    )
  )

  memWAddr := memAddr
  memWMask := Mux1H(
    Seq(
      memOpIsByte -> wByteMask,
      memOpIsHalf -> wByteMaskHalf,
      memOpIsWord -> "b1111".U(4.W)
    )
  )
  // memWMask := MuxLookup(func3t, 0.U)(
  //   Seq(
  //     MemOp.byte     -> wByteMask,
  //     MemOp.halfword -> wByteMaskHalf,
  //     MemOp.word     -> 15.U(4.W)
  //   )
  // )

  outWriteBackInfo.csr <> inExuWriteBackInfo.csr
  outWriteBackInfo.csr_ecallflag := inExuWriteBackInfo.csr_ecallflag
  outWriteBackInfo.gpr.addr      := inExuWriteBackInfo.gpr.addr
  outWriteBackInfo.gpr.en        := inExuWriteBackInfo.gpr.en
  outWriteBackInfo.gpr.data      := Mux(isMemOp, loadResult, inExuWriteBackInfo.gpr.data)
  outWriteBackInfo.is_ebreak     := inExuWriteBackInfo.is_ebreak
  outWriteBackInfo.pc            := inExuWriteBackInfo.pc
  outWriteBackInfo.nxt_pc        := inExuWriteBackInfo.nxt_pc

}
