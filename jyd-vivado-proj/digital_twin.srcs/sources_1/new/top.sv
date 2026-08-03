`timescale 1ns / 1ps
//////////////////////////////////////////////////////////////////////////////////
// Company: 
// Engineer: 
// 
// Create Date: 04/16/2025 06:21:44 PM
// Design Name: 
// Module Name: top
// Project Name: 
// Target Devices: 
// Tool Versions: 
// Description: 
// 
// Dependencies: 
// 
// Revision:
// Revision 0.01 - File Created
// Additional Comments:
// 
//////////////////////////////////////////////////////////////////////////////////


module top(
    input  wire i_sys_clk_p         ,
    input  wire i_sys_clk_n         ,
    input  wire i_uart_rx           ,
    output wire o_uart_tx           ,

    output wire [31:0] virtual_led  ,
    output wire [39:0] virtual_seg
);

    wire w_clk_50Mhz, cpu_clk;
    wire w_clk_rst;

    wire uart_tx_push;
    wire [7:0] uart_tx_data;
    wire uart_tx_full;
    wire [7:0] uart_rx_data;
    wire uart_rx_empty;
    wire uart_rx_pop;

    mypll pll_inst(
        .clk_in1_p(i_sys_clk_p),
        .clk_in1_n(i_sys_clk_n),
        .clk_out1(w_clk_50Mhz),
        .clk_out2(cpu_clk),
        .locked(w_clk_rst)
    );

    jyd_uart_subsystem uart_subsystem_inst(
        .cpu_clk(cpu_clk),
        .uart_clk(w_clk_50Mhz),
        .resetn(w_clk_rst),
        .tx_push(uart_tx_push),
        .tx_data(uart_tx_data),
        .tx_full(uart_tx_full),
        .rx_data(uart_rx_data),
        .rx_empty(uart_rx_empty),
        .rx_pop(uart_rx_pop),
        .uart_rx(i_uart_rx),
        .uart_tx(o_uart_tx)
    );

    student_top student_top_inst(
        .w_cpu_clk(cpu_clk),
        .w_clk_50Mhz(w_clk_50Mhz),
        .w_clk_rst(~w_clk_rst),
        .uartTxPush(uart_tx_push),
        .uartTxData(uart_tx_data),
        .uartTxFull(uart_tx_full),
        .uartRxData(uart_rx_data),
        .uartRxEmpty(uart_rx_empty),
        .uartRxPop(uart_rx_pop),
        .virtual_led(virtual_led),
        .virtual_seg(virtual_seg)
    );

endmodule
