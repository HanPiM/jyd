package cpu

import chisel3._
import chisel3.util._

class NumericTokenDfa2ByteStep extends Module {
  val io = IO(new Bundle {
    val state     = Input(UInt(3.W))
    val mask      = Input(UInt(8.W))
    val consumed  = Input(UInt(3.W))
    val active    = Input(Bool())
    val stopped   = Input(Bool())
    val symbols   = Input(UInt(16.W))
    val available = Input(UInt(3.W))
    val result    = Output(UInt(16.W))
  })

  private def isDigit(c: UInt): Bool = c >= '0'.U && c <= '9'.U

  private def transition(state: UInt, symbol: UInt): (UInt, UInt) = {
    val digit = isDigit(symbol)
    val next = MuxLookup(state, 1.U(3.W))(
      Seq(
        0.U -> Mux(digit, 4.U, Mux(symbol === '+'.U || symbol === '-'.U, 2.U, Mux(symbol === '.'.U, 5.U, 1.U))),
        2.U -> Mux(digit, 4.U, Mux(symbol === '.'.U, 5.U, 1.U)),
        4.U -> Mux(symbol === '.'.U, 5.U, Mux(digit, 4.U, 1.U)),
        5.U -> Mux(symbol === 'E'.U || symbol === 'e'.U, 3.U, Mux(digit, 5.U, 1.U)),
        3.U -> Mux(symbol === '+'.U || symbol === '-'.U, 6.U, 1.U),
        6.U -> Mux(digit, 7.U, 1.U),
        7.U -> Mux(digit, 7.U, 1.U)
      )
    )
    val transitionMask = MuxLookup(state, 0.U(8.W))(
      Seq(
        0.U -> (1.U(8.W) | Mux(next === 1.U, 2.U(8.W), 0.U(8.W))),
        2.U -> 4.U(8.W),
        3.U -> 8.U(8.W),
        4.U -> Mux(next =/= 4.U, 16.U(8.W), 0.U(8.W)),
        5.U -> Mux(next =/= 5.U, 32.U(8.W), 0.U(8.W)),
        6.U -> 64.U(8.W),
        7.U -> Mux(next === 1.U, 2.U(8.W), 0.U(8.W))
      )
    )
    (next, transitionMask)
  }

  val states = Wire(Vec(3, UInt(3.W)))
  val masks = Wire(Vec(3, UInt(8.W)))
  val consumed = Wire(Vec(3, UInt(3.W)))
  val active = Wire(Vec(3, Bool()))
  val stopped = Wire(Vec(3, Bool()))
  states(0) := io.state
  masks(0) := io.mask
  consumed(0) := io.consumed
  active(0) := io.active
  stopped(0) := io.stopped

  for (i <- 0 until 2) {
    val symbol = io.symbols(8 * i + 7, 8 * i)
    val zero = symbol === 0.U
    val comma = symbol === ','.U
    val take = active(i) && i.U < io.available
    val run = take && !zero && !comma
    val (next, transitionMask) = transition(states(i), symbol)
    val invalid = run && next === 1.U
    val terminal = take && (zero || comma || invalid)
    states(i + 1) := Mux(run, next, states(i))
    masks(i + 1) := masks(i) | Mux(run, transitionMask, 0.U)
    consumed(i + 1) := consumed(i) + (take && !zero)
    stopped(i + 1) := stopped(i) || terminal
    active(i + 1) := take && !terminal && (i + 1).U < io.available
  }

  io.result := Cat(masks(2), stopped(2), active(2), consumed(2), states(2))
}
