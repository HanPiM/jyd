`timescale 1ns / 1ps
//////////////////////////////////////////////////////////////////////////////////
// Company: 
// Engineer: 
// 
// Create Date: 04/16/2025 06:21:13 PM
// Design Name: 
// Module Name: student_top
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


module student_top#(
    parameter                           P_SW_CNT            = 64,
    parameter                           P_LED_CNT           = 32,
    parameter                           P_SEG_CNT           = 40,
    parameter                           P_KEY_CNT           = 8
) (
    input                                       w_cpu_clk     ,
    input                                       w_clk_50Mhz   ,
    input                                       w_clk_rst     ,
    output                                      uartTxPush    ,
    output [7:0]                                uartTxData    ,
    input                                       uartTxFull    ,
    input  [7:0]                                uartRxData    ,
    input                                       uartRxEmpty   ,
    output                                      uartRxPop     ,

    output [P_LED_CNT - 1:0]                    virtual_led   ,
    output [P_SEG_CNT - 1:0]                    virtual_seg   
);
    logic [31:0] seg_wdata;
    logic [39:0] seg_output;
    display_seg seg_driver (
        .clk    (w_clk_50Mhz),
        .rst    (w_clk_rst),
        .s      (seg_wdata),
        .seg1   (seg_output[6:0]),
        .seg2   (seg_output[16:10]),
        .seg3   (seg_output[26:20]),
        .seg4   (seg_output[36:30]),
        .ans    ({seg_output[39:38], seg_output[29:28], seg_output[19:18], seg_output[9:8]})
    ); 
       assign seg_output[7]  = 0;
    assign seg_output[17] = 0;
    assign seg_output[27] = 0;
    assign seg_output[37] = 0;
    
    assign virtual_seg = seg_output;
//    wire w_cpu_rst;
    
//    rst_sync u_rst_sync_cpu (
//        .clk      (w_cpu_clk),
//        .rst_in   (w_clk_rst),
//        .rst_out  (w_cpu_rst)
//    );
    
    JYDFPGATop mytop(
        .clock    (w_cpu_clk),
        .clk_50Mhz(w_clk_50Mhz),
        .reset    (w_clk_rst),
        .led      (virtual_led),
        .seg      (seg_wdata),
        .uartTxPush(uartTxPush),
        .uartTxData(uartTxData),
        .uartTxFull(uartTxFull),
        .uartRxData(uartRxData),
        .uartRxEmpty(uartRxEmpty),
        .uartRxPop(uartRxPop)
    );
//    // IROM
//    logic [31:0] pc;
//    logic [11:0] inst_addr;
//    logic [31:0] instruction;

//    // perip
//    logic [31:0] perip_addr, perip_wdata, perip_rdata;
//    logic perip_wen;
//    logic [1:0] perip_mask;

//    // 16KB = 2^12 * 32bit
//    assign inst_addr = pc[13:2];

//    myCPU Core_cpu (
//        .cpu_rst            (w_clk_rst),
//        .cpu_clk            (w_cpu_clk),

//        // Interface to IROM
//        .irom_addr          (pc),             
//        .irom_data          (instruction),   

//        // Interface to DRAM & periphera
//        .perip_addr         (perip_addr),     
//        .perip_wen          (perip_wen),     
//        .perip_mask         (perip_mask),   
//        .perip_wdata        (perip_wdata),    
//        .perip_rdata        (perip_rdata)     
//    );

//    IROM Mem_IROM (
//        .a          (inst_addr),
//        .spo        (instruction)
//    );
    
//    perip_bridge bridge_inst (
//        .clk				(w_cpu_clk),
//        .cnt_clk            (w_clk_50Mhz),
//        .rst                (w_clk_rst),
//        .perip_addr			(perip_addr),
//        .perip_wdata		(perip_wdata),
//        .perip_wen			(perip_wen),
//        .perip_mask			(perip_mask),
//        .perip_rdata		(perip_rdata),
//        .virtual_sw_input	(virtual_sw),
//        .virtual_key_input	(virtual_key),	
//        .virtual_seg_output	(virtual_seg),
//        .virtual_led_output (virtual_led)
//    );

endmodule

module rst_sync (
    input  wire clk,
    input  wire rst_in,   // 异步输入，高有效
    output wire rst_out   // 同步到 clk 的高有效复位
);

reg [1:0] sync_ff;

always @(posedge clk or posedge rst_in) begin
    if (rst_in)
        sync_ff <= 2'b11;                  // 异步进入复位
    else
        sync_ff <= {sync_ff[0], 1'b0};     // 同步释放复位
end

assign rst_out = sync_ff[1];

endmodule
