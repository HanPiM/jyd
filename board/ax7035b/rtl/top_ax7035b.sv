`timescale 1ns/1ps

module top_ax7035b (
    input  wire       sys_clk,
    input  wire       uart_rx,
    output wire       uart_tx,
    inout  wire       aht10_scl,
    inout  wire       aht10_sda,
    output wire [7:0] seg_digit,
    output wire [5:0] seg_select
);
    wire cpu_clk;
    wire clk_50mhz;
    wire reset;
    wire clocks_locked;
    wire [31:0] led_unused;
    wire [31:0] seg_value;
    wire uart_tx_push;
    wire [7:0] uart_tx_data;
    wire uart_tx_full;
    wire [7:0] uart_rx_data;
    wire uart_rx_empty;
    wire uart_rx_pop;
    wire aht10_scl_drive_low;
    wire aht10_sda_drive_low;

    ax7035b_clock_gen u_clock (
        .sys_clk(sys_clk), .cpu_clk(cpu_clk), .clk_50mhz(clk_50mhz),
        .reset(reset), .locked(clocks_locked)
    );

    JYDFPGATop u_soc (
        .clock(cpu_clk), .reset(reset), .clk_50Mhz(clk_50mhz),
        .led(led_unused), .seg(seg_value),
        .uartTxPush(uart_tx_push), .uartTxData(uart_tx_data),
        .uartTxFull(uart_tx_full), .uartRxData(uart_rx_data),
        .uartRxEmpty(uart_rx_empty), .uartRxPop(uart_rx_pop),
        .aht10SclIn(aht10_scl), .aht10SdaIn(aht10_sda),
        .aht10SclDriveLow(aht10_scl_drive_low),
        .aht10SdaDriveLow(aht10_sda_drive_low)
    );

    ax7035b_uart #(.CLK_HZ(100_000_000), .BAUD_RATE(115200)) u_uart (
        .clk(cpu_clk), .reset(reset), .uart_rx(uart_rx), .uart_tx(uart_tx),
        .tx_push(uart_tx_push), .tx_data(uart_tx_data), .tx_full(uart_tx_full),
        .rx_data(uart_rx_data), .rx_empty(uart_rx_empty), .rx_pop(uart_rx_pop)
    );

    ax7035b_seg_scan u_seg (
        .clk(clk_50mhz), .reset(reset), .bcd_value_cpu(seg_value),
        .seg_digit(seg_digit), .seg_select(seg_select)
    );

    assign aht10_scl = aht10_scl_drive_low ? 1'b0 : 1'bz;
    assign aht10_sda = aht10_sda_drive_low ? 1'b0 : 1'bz;

    wire unused_ok = &{1'b0, clocks_locked, led_unused};
endmodule
