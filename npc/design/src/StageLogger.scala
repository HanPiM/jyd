package dpiwrap

import chisel3._
import chisel3.layer.{Layer, LayerConfig}
import chisel3.util.circt.dpi.RawClockedVoidFunctionCall
import config.Config

object StageLogLayer extends Layer(LayerConfig.Extract())

object StageLogConst {
  val eventWidth = 32
  val stageWidth = 32

  object Event {
    val stage = 0.U(eventWidth.W)
    val retire = 1.U(eventWidth.W)
    val flush = 2.U(eventWidth.W)
  }

  object Stage {
    val ifu = 0.U(stageWidth.W)
    val idu = 1.U(stageWidth.W)
    val exu = 2.U(stageWidth.W)
    val lsu = 3.U(stageWidth.W)
    val wbu = 4.U(stageWidth.W)
  }
}

object StageLogger {
  def apply(
    clock: Clock,
    event: UInt,
    stage: UInt,
    enable: Bool,
    iid: UInt,
    data: UInt = 0.U(32.W),
    flags: UInt = 0.U(32.W)
  ): Unit = {
    if (Config.genStageLog) {
      layer.block(StageLogLayer) {
        RawClockedVoidFunctionCall("konata_event")(clock, enable, event, stage, iid, data, flags)
      }
    }
  }
}
