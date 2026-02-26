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

ysyx_25100261 dut (
		.clock(clk),
		.reset(rst)
);

always @(posedge clk) begin
	if (dut.core.wbu.isEBreak) begin
		$display("EBREAK instruction executed. Ending simulation.");
		if(dut.core.gprs.a0 != 0) begin
			$display("HIT BAD TRAP a0 = %d", dut.core.gprs.a0);
			$fatal;
		end else begin
			$display("HIT GOOD TRAP");
		end
		$finish;
	end
end

initial begin
    $dumpfile("wave.fst");
    $dumpvars(0, testbench);
		#3000000000 $finish;
end

endmodule
