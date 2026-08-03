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
  wire uart_tx_push;
  wire [7:0] uart_tx_data;
  wire [7:0] uart_rx_data;
  wire uart_rx_empty;
  wire uart_rx_pop;
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
    .uartTxPush(uart_tx_push),
    .uartTxData(uart_tx_data),
    .uartRxData(uart_rx_data),
    .uartRxEmpty(uart_rx_empty),
    .uartRxPop(uart_rx_pop)
  );

  jyd_uart_subsystem uart_subsystem (
    .cpu_clk(cpu_clk),
    .uart_clk(clk_50mhz),
    .resetn(~reset),
    .tx_push(uart_tx_push),
    .tx_data(uart_tx_data),
    .rx_data(uart_rx_data),
    .rx_empty(uart_rx_empty),
    .rx_pop(uart_rx_pop),
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
