package busfsm
import chisel3._
import chisel3.util._

class OneMasterOneSlaveFSM_Old extends Module {
  val io = IO(new Bundle {
    val master_valid = Input(Bool())
    val master_ready = Output(Bool())

    val self_finished = Input(Bool())

    val slave_valid = Output(Bool())
    val slave_ready = Input(Bool())

    val _state = Output(UInt(2.W))
  })

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

  io.master_ready := (state === s_wait_slave) && (io.self_finished) && (io.slave_ready)
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
class OneMasterOneSlaveFSM extends Module {
  val io = IO(new Bundle {
    val master_valid = Input(Bool())
    val master_ready = Output(Bool())

    val self_finished = Input(Bool())

    val slave_valid = Output(Bool())
    val slave_ready = Input(Bool())
  })

  val full = RegInit(false.B)

  val state = Wire(UInt(2.W))
  when(!full) {
    state := 0.U
  }.elsewhen(full && !io.self_finished) {
    state := 1.U
  }.otherwise {
    state := 2.U
  }
  dontTouch(state)

  val slave_picked = io.slave_ready && io.self_finished

  val ready = !full //|| slave_picked
  io.master_ready := ready

  io.slave_valid := full && io.self_finished

  when(ready) {
    full := io.master_valid
  }.elsewhen(slave_picked) {
    full := false.B
  }

  def connectMaster[T <: Data](master: DecoupledIO[T]): Unit = {
    master.ready    := io.master_ready
    io.master_valid := master.valid
  }
  def connectSlave[T <: Data](slave: DecoupledIO[T]):   Unit = {
    slave.valid    := io.slave_valid
    io.slave_ready := slave.ready
  }
}
