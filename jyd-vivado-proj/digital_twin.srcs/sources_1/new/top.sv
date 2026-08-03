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
        .uart_rx(i_uart_rx),
        .uart_tx(o_uart_tx)
    );

    student_top student_top_inst(
        .w_cpu_clk(cpu_clk),
        .w_clk_50Mhz(w_clk_50Mhz),
        .w_clk_rst(~w_clk_rst),
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
        .uart_rready(uart_rready),
        .virtual_led(virtual_led),
        .virtual_seg(virtual_seg)
    );

endmodule
