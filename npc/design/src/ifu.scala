package cpu
import chisel3._
import chisel3.util._
import common_def._
import busfsm._

import memory._

class IFU extends Module {
  val io = IO(new Bundle {
    val pc  = Flipped(Decoupled(Input(Types.UWord)))
    val mem = MemReqIO.ReadTX
    val out = Decoupled(new Inst)
  })

  val fsm = Module(new OneMasterOneSlaveFSM)
  fsm.connectMaster(io.pc)
  fsm.connectSlave(io.out)

  val loadFSM = Module(new LoadStoreFSM)
  io.mem <> loadFSM.io.memRd

  loadFSM.io.memWr := DontCare
  loadFSM.io.wdata := 0.U
  loadFSM.io.wmask := 0.U

  loadFSM.io.wen:= false.B
  loadFSM.io.reqValid := fsm.io.master_valid && (!fsm.io.slave_ready)
  loadFSM.io.addr     := io.pc.bits

  val code = Reg(Types.UWord)
  val fetchDone = Reg(Bool())
  when(loadFSM.io.respValid) {
    code := loadFSM.io.rdata
    fetchDone := true.B
  }
  when(!fsm.io.master_valid) {
    fetchDone := false.B
  }

  fsm.io.self_finished := fetchDone

  // NOTICE: dpi function auto generated with void return
  // see https://github.com/llvm/circt/blob/main/docs/Dialects/FIRRTL/FIRRTLIntrinsics.md#dpi-intrinsic-abi
  io.out.bits.code := code
  io.out.bits.pc   := io.pc.bits
}


