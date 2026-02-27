object Elaborate extends App {
  if (args.size != 2) {
    println("Usage: --target-dir <dir>")
    sys.exit(1)
  }
  val firtoolOptions = Array(
    "--lowering-options=" + List(
      // make yosys happy
      // see https://github.com/llvm/circt/blob/main/docs/VerilogGeneration.md
      "disallowLocalVariables",
      "disallowPackedArrays",
      "locationInfoStyle=wrapInAtSquareBracket"
    ).reduce(_ + "," + _),
    "-disable-all-randomization",
    "-strip-debug-info"
  )
  println("Emitting cpu Verilog...")
  circt.stage.ChiselStage.emitSystemVerilogFile(new top.ysyx_25100261(), args, firtoolOptions)
  println("Finish emit cpu Verilog.")
  circt.stage.ChiselStage.emitSystemVerilogFile(new top.NPCTestSoC(),
    Array("--target-dir", "build/npctestsoc"),
    firtoolOptions
  )
}
