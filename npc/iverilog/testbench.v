`timescale 1ns/1ps

module testbench;
reg clk;
reg rst;

always #5 clk = ~clk;

initial begin
		clk = 0;
		rst = 0;
		#10 rst = 1;
		repeat(30) @(posedge clk);
		rst = 0;
end

NPCTestSoC dut (
		.clock(clk),
		.reset(rst)
);

wire exu_out_valid;
wire [31:0] exu_code;
wire [31:0] gpr_a0;

`ifdef SIM_NETLIST
assign exu_out_valid = dut.cpu.core._exu_io_out_valid;
assign exu_code = {
	dut.cpu.core.\exu._stageCalc_io_out_bits_dinst_code_31_ ,
	dut.cpu.core.\exu._stageCalc_io_out_bits_dinst_code_30_ ,
	dut.cpu.core.\exu._stageCalc_io_out_bits_dinst_code_29_ ,
	dut.cpu.core.\exu._stageCalc_io_out_bits_dinst_code_28_ ,
	dut.cpu.core.\exu._stageCalc_io_out_bits_dinst_code_27_ ,
	dut.cpu.core.\exu._stageCalc_io_out_bits_dinst_code_26_ ,
	dut.cpu.core.\exu._stageCalc_io_out_bits_dinst_code_25_ ,
	dut.cpu.core.\exu._stageCalc_io_out_bits_dinst_code_24_ ,
	dut.cpu.core.\exu._stageCalc_io_out_bits_dinst_code_23_ ,
	dut.cpu.core.\exu._stageCalc_io_out_bits_dinst_code_22_ ,
	dut.cpu.core.\exu._stageCalc_io_out_bits_dinst_code_21_ ,
	dut.cpu.core.\exu._stageCalc_io_out_bits_dinst_code_20_ ,
	dut.cpu.core.\exu._stageCalc_io_out_bits_dinst_code_19_ ,
	dut.cpu.core.\exu._stageCalc_io_out_bits_dinst_code_18_ ,
	dut.cpu.core.\exu._stageCalc_io_out_bits_dinst_code_17_ ,
	dut.cpu.core.\exu._stageCalc_io_out_bits_dinst_code_16_ ,
	dut.cpu.core.\exu._stageCalc_io_out_bits_dinst_code_15_ ,
	dut.cpu.core.\exu._stageCalc_io_out_bits_dinst_code_14_ ,
	dut.cpu.core.\exu._stageCalc_io_out_bits_dinst_code_13_ ,
	dut.cpu.core.\exu._stageCalc_io_out_bits_dinst_code_12_ ,
	5'b0 , // rd was optimized out
	// dut.cpu.core.\exu._stageCalc_io_out_bits_dinst_code_11_ ,
	// dut.cpu.core.\exu._stageCalc_io_out_bits_dinst_code_10_ ,
	// dut.cpu.core.\exu._stageCalc_io_out_bits_dinst_code_9_ ,
	// dut.cpu.core.\exu._stageCalc_io_out_bits_dinst_code_8_ ,
	// dut.cpu.core.\exu._stageCalc_io_out_bits_dinst_code_7_ ,
	dut.cpu.core.\exu._stageCalc_io_out_bits_dinst_code_6_ ,
	dut.cpu.core.\exu._stageCalc_io_out_bits_dinst_code_5_ ,
	dut.cpu.core.\exu._stageCalc_io_out_bits_dinst_code_4_ ,
	dut.cpu.core.\exu._stageCalc_io_out_bits_dinst_code_3_ ,
	dut.cpu.core.\exu._stageCalc_io_out_bits_dinst_code_2_ ,
	dut.cpu.core.\exu._stageCalc_io_out_bits_dinst_code_1_ ,
	dut.cpu.core.\exu._stageCalc_io_out_bits_dinst_code_0_
};
assign gpr_a0 = {
	dut.cpu.core.\gprs.a0_31_ ,
	dut.cpu.core.\gprs.a0_30_ ,
	dut.cpu.core.\gprs.a0_29_ ,
	dut.cpu.core.\gprs.a0_28_ ,
	dut.cpu.core.\gprs.a0_27_ ,
	dut.cpu.core.\gprs.a0_26_ ,
	dut.cpu.core.\gprs.a0_25_ ,
	dut.cpu.core.\gprs.a0_24_ ,
	dut.cpu.core.\gprs.a0_23_ ,
	dut.cpu.core.\gprs.a0_22_ ,
	dut.cpu.core.\gprs.a0_21_ ,
	dut.cpu.core.\gprs.a0_20_ ,
	dut.cpu.core.\gprs.a0_19_ ,
	dut.cpu.core.\gprs.a0_18_ ,
	dut.cpu.core.\gprs.a0_17_ ,
	dut.cpu.core.\gprs.a0_16_ ,
	dut.cpu.core.\gprs.a0_15_ ,
	dut.cpu.core.\gprs.a0_14_ ,
	dut.cpu.core.\gprs.a0_13_ ,
	dut.cpu.core.\gprs.a0_12_ ,
	dut.cpu.core.\gprs.a0_11_ ,
	dut.cpu.core.\gprs.a0_10_ ,
	dut.cpu.core.\gprs.a0_9_ ,
	dut.cpu.core.\gprs.a0_8_ ,
	dut.cpu.core.\gprs.a0_7_ ,
	dut.cpu.core.\gprs.a0_6_ ,
	dut.cpu.core.\gprs.a0_5_ ,
	dut.cpu.core.\gprs.a0_4_ ,
	dut.cpu.core.\gprs.a0_3_ ,
	dut.cpu.core.\gprs.a0_2_ ,
	dut.cpu.core.\gprs.a0_1_ ,
	dut.cpu.core.\gprs.a0_0_
};
`else
assign exu_out_valid = dut.cpu.core.exu.io_out_valid;
assign exu_code = dut.cpu.core.exu.io_in_bits_code;
assign gpr_a0 = dut.cpu.core.gprs.a0;
`endif

always @(posedge clk) begin
	if (exu_out_valid && exu_code == 32'h00100073) begin // EBREAK
		$display("EBREAK instruction executed. Ending simulation.");
		if(gpr_a0 != 0) begin
			$display("HIT BAD TRAP a0 = %d", gpr_a0);
			$fatal;
		end else begin
			$display("HIT GOOD TRAP");
		end
		$finish;
	end
end

initial begin
    // $dumpfile("wave.fst");
    // $dumpvars(0, testbench);
end

endmodule
