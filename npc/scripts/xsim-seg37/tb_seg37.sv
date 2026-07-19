`timescale 1ns / 1ps

module tb_seg37;
  localparam integer MAX_CPU_CYCLES = 25000000;

  logic cpu_clk = 1'b0;
  logic clk_50mhz = 1'b0;
  logic reset = 1'b1;
  wire [31:0] led;
  wire [31:0] seg;
  integer cpu_cycles = 0;
  logic [31:0] last_seg = 32'hxxxxxxxx;

  // The FPGA clock is 280 MHz.  Avoid the clock-wizard model here: this test
  // targets the CPU and its memory/arithmetic IP models, not PLL lock behavior.
  always #1.785714 cpu_clk = ~cpu_clk;
  always #10 clk_50mhz = ~clk_50mhz;

  JYDFPGATop dut (
    .clock(cpu_clk),
    .reset(reset),
    .clk_50Mhz(clk_50mhz),
    .led(led),
    .seg(seg)
  );

  initial begin
    repeat (20) @(posedge cpu_clk);
    reset <= 1'b0;
    $display("SEG37_START reset_released time=%0t", $time);
  end

  always @(posedge cpu_clk) begin
    if (!reset) begin
      cpu_cycles <= cpu_cycles + 1;
      if (seg !== last_seg) begin
        $display("SEG37_UPDATE cycle=%0d seg=%08x led=%08x", cpu_cycles, seg, led);
        last_seg <= seg;
      end
      if (seg[31:24] == 8'h37) begin
        $display("SEG37_PASS cycle=%0d seg=%08x led=%08x", cpu_cycles, seg, led);
        $finish;
      end
      if (cpu_cycles == MAX_CPU_CYCLES) begin
        $display("SEG37_FAIL simulation_cycle_limit=%0d seg=%08x led=%08x", MAX_CPU_CYCLES, seg, led);
        $finish;
      end
    end
  end
endmodule
