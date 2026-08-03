`timescale 1ns / 1ps

module tb_seg37;
  localparam integer MAX_CPU_CYCLES = 25000000;

  logic cpu_clk = 1'b0;
  logic clk_50mhz = 1'b0;
  logic reset = 1'b1;
  wire [31:0] led;
  wire [31:0] seg;
  wire uart_tx;
  logic uart_rx = 1'b1;
  wire [3:0] uart_awaddr;
  wire uart_awvalid, uart_awready;
  wire [31:0] uart_wdata;
  wire [3:0] uart_wstrb;
  wire uart_wvalid, uart_wready;
  wire [1:0] uart_bresp;
  wire uart_bvalid, uart_bready;
  wire [3:0] uart_araddr;
  wire uart_arvalid, uart_arready;
  wire [31:0] uart_rdata;
  wire [1:0] uart_rresp;
  wire uart_rvalid, uart_rready;
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
    .seg(seg),
    .uart_awaddr(uart_awaddr),
    .uart_awvalid(uart_awvalid),
    .uart_awready(uart_awready),
    .uart_wdata(uart_wdata),
    .uart_wstrb(uart_wstrb),
    .uart_wvalid(uart_wvalid),
    .uart_wready(uart_wready),
    .uart_bresp(uart_bresp),
    .uart_bvalid(uart_bvalid),
    .uart_bready(uart_bready),
    .uart_araddr(uart_araddr),
    .uart_arvalid(uart_arvalid),
    .uart_arready(uart_arready),
    .uart_rdata(uart_rdata),
    .uart_rresp(uart_rresp),
    .uart_rvalid(uart_rvalid),
    .uart_rready(uart_rready)
  );

  jyd_uart_subsystem uart_subsystem (
    .cpu_clk(cpu_clk),
    .uart_clk(clk_50mhz),
    .resetn(~reset),
    .s_axi_awaddr(uart_awaddr),
    .s_axi_awvalid(uart_awvalid),
    .s_axi_awready(uart_awready),
    .s_axi_wdata(uart_wdata),
    .s_axi_wstrb(uart_wstrb),
    .s_axi_wvalid(uart_wvalid),
    .s_axi_wready(uart_wready),
    .s_axi_bresp(uart_bresp),
    .s_axi_bvalid(uart_bvalid),
    .s_axi_bready(uart_bready),
    .s_axi_araddr(uart_araddr),
    .s_axi_arvalid(uart_arvalid),
    .s_axi_arready(uart_arready),
    .s_axi_rdata(uart_rdata),
    .s_axi_rresp(uart_rresp),
    .s_axi_rvalid(uart_rvalid),
    .s_axi_rready(uart_rready),
    .uart_rx(uart_rx),
    .uart_tx(uart_tx)
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
