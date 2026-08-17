package cpu

import chisel3._
import chisel3.util._

object NumericTokenDfaSymbolClass {
  val Other = 0
  val Zero = 1
  val Comma = 2
  val Digit = 3
  val Sign = 4
  val Dot = 5
  val Exponent = 6

  def classify(symbol: UInt): UInt = MuxCase(Other.U(3.W),
    Seq(
      (symbol === 0.U) -> Zero.U(3.W),
      (symbol === ','.U) -> Comma.U(3.W),
      (symbol >= '0'.U && symbol <= '9'.U) -> Digit.U(3.W),
      (symbol === '+'.U || symbol === '-'.U) -> Sign.U(3.W),
      (symbol === '.'.U) -> Dot.U(3.W),
      (symbol === 'E'.U || symbol === 'e'.U) -> Exponent.U(3.W)
    ))
}

class NumericTokenDfa2ClassStep extends Module {
  val io = IO(new Bundle {
    val state     = Input(UInt(3.W))
    val mask      = Input(UInt(8.W))
    val consumed  = Input(UInt(3.W))
    val active    = Input(Bool())
    val stopped   = Input(Bool())
    val classes   = Input(UInt(6.W))
    val available = Input(UInt(3.W))
    val result    = Output(UInt(16.W))
  })

  private def transition(state: UInt, symbolClass: UInt): (UInt, UInt) = {
    val digit = symbolClass === NumericTokenDfaSymbolClass.Digit.U
    val sign = symbolClass === NumericTokenDfaSymbolClass.Sign.U
    val dot = symbolClass === NumericTokenDfaSymbolClass.Dot.U
    val exponent = symbolClass === NumericTokenDfaSymbolClass.Exponent.U
    val next = MuxLookup(state, 1.U(3.W))(
      Seq(
        0.U -> Mux(digit, 4.U, Mux(sign, 2.U, Mux(dot, 5.U, 1.U))),
        2.U -> Mux(digit, 4.U, Mux(dot, 5.U, 1.U)),
        4.U -> Mux(dot, 5.U, Mux(digit, 4.U, 1.U)),
        5.U -> Mux(exponent, 3.U, Mux(digit, 5.U, 1.U)),
        3.U -> Mux(sign, 6.U, 1.U),
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
    val symbolClass = io.classes(3 * i + 2, 3 * i)
    val zero = symbolClass === NumericTokenDfaSymbolClass.Zero.U
    val comma = symbolClass === NumericTokenDfaSymbolClass.Comma.U
    val take = active(i) && i.U < io.available
    val run = take && !zero && !comma
    val (next, transitionMask) = transition(states(i), symbolClass)
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

  val classStep = Module(new NumericTokenDfa2ClassStep)
  classStep.io.state := io.state
  classStep.io.mask := io.mask
  classStep.io.consumed := io.consumed
  classStep.io.active := io.active
  classStep.io.stopped := io.stopped
  classStep.io.classes := Cat(
    NumericTokenDfaSymbolClass.classify(io.symbols(15, 8)),
    NumericTokenDfaSymbolClass.classify(io.symbols(7, 0))
  )
  classStep.io.available := io.available
  io.result := classStep.io.result
}

/** Scan two bytes while allowing a token boundary between them.
  *
  * The generic step module deliberately stops at the first comma, invalid
  * transition, or NUL because its caller exposes one software-visible token
  * step. The whole-string scanner has no such boundary: it can commit the
  * finished token locally, restart from state zero, and consume the following
  * byte from the same fetched word.
  */
class NumericTokenDfa2ByteScan extends Module {
  val io = IO(new Bundle {
    val state     = Input(UInt(3.W))
    val mask      = Input(UInt(8.W))
    val symbols   = Input(UInt(16.W))
    val available = Input(UInt(2.W))
    val active    = Input(Bool())
    val nextState = Output(UInt(3.W))
    val nextMask  = Output(UInt(8.W))
    val consumed  = Output(UInt(2.W))
    val terminated = Output(Bool())
    val counterIncrement = Output(Vec(8, UInt(2.W)))
    val finalIncrement   = Output(Vec(8, UInt(2.W)))
  })

  val classes = Seq(
    NumericTokenDfaSymbolClass.classify(io.symbols(7, 0)),
    NumericTokenDfaSymbolClass.classify(io.symbols(15, 8))
  )
  val steps = Seq.fill(2)(Module(new NumericTokenDfa2ClassStep))
  val states = Wire(Vec(3, UInt(3.W)))
  val masks = Wire(Vec(3, UInt(8.W)))
  val active = Wire(Vec(3, Bool()))
  val terminated = Wire(Vec(3, Bool()))
  val consumed = Wire(Vec(3, UInt(2.W)))
  val counterIncrement = Wire(Vec(3, Vec(8, UInt(2.W))))
  val finalIncrement = Wire(Vec(3, Vec(8, UInt(2.W))))

  states(0) := io.state
  masks(0) := io.mask
  active(0) := io.active
  terminated(0) := false.B
  consumed(0) := 0.U
  counterIncrement(0).foreach(_ := 0.U)
  finalIncrement(0).foreach(_ := 0.U)

  for (byte <- 0 until 2) {
    val take = active(byte) && byte.U < io.available
    val zero = classes(byte) === NumericTokenDfaSymbolClass.Zero.U
    val step = steps(byte)
    step.io.state := states(byte)
    step.io.mask := masks(byte)
    step.io.consumed := 0.U
    step.io.active := take
    step.io.stopped := false.B
    step.io.classes := Cat(0.U(3.W), classes(byte))
    step.io.available := Mux(take, 1.U, 0.U)

    val stopped = take && step.io.result(7)
    val commit = stopped && (!zero || masks(byte).orR)
    val committedMask = step.io.result(15, 8)
    val committedState = step.io.result(2, 0)
    states(byte + 1) := Mux(stopped, 0.U, step.io.result(2, 0))
    masks(byte + 1) := Mux(stopped, 0.U, step.io.result(15, 8))
    terminated(byte + 1) := terminated(byte) || (stopped && zero)
    active(byte + 1) := take && !(stopped && zero)
    consumed(byte + 1) := consumed(byte) + (take && !zero)
    for (state <- 0 until 8) {
      counterIncrement(byte + 1)(state) :=
        counterIncrement(byte)(state) + (commit && committedMask(state))
      finalIncrement(byte + 1)(state) :=
        finalIncrement(byte)(state) + (commit && committedState === state.U)
    }
  }

  io.nextState := states(2)
  io.nextMask := masks(2)
  io.consumed := consumed(2)
  io.terminated := terminated(2)
  io.counterIncrement := counterIncrement(2)
  io.finalIncrement := finalIncrement(2)
}
