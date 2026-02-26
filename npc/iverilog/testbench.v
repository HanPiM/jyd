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

initial begin
    $dumpfile("wave.fst");
    $dumpvars(0, testbench);
		#3000000000 $finish;
end

endmodule
