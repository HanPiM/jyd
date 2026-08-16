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
    input                                       key1          ,
    input                                       aht10SclIn    ,
    input                                       aht10SdaIn    ,
    output                                      aht10SclDriveLow,
    output                                      aht10SdaDriveLow,

    output [P_LED_CNT - 1:0]                    virtual_led   ,
    output [P_SEG_CNT - 1:0]                    virtual_seg   
);
    logic [31:0] seg_wdata;
    logic [31:0] display_wdata;
    logic [39:0] seg_output;
    logic display_scan_half;
    logic aht10_display_mode;
    logic key1_press_pulse;
    logic signed [15:0] aht10_temperature_x10;
    logic [15:0] aht10_humidity_x10;
    logic aht10_data_valid;
    logic [10:0] aht10_display_binary;
    logic [15:0] aht10_display_bcd;

    always_ff @(posedge w_clk_50Mhz or posedge w_clk_rst) begin
        if (w_clk_rst)
            aht10_display_mode <= 1'b0;
        else if (key1_press_pulse)
            aht10_display_mode <= ~aht10_display_mode;
    end

    jyd_key_debounce #(
        .CLK_HZ(50_000_000),
        .DEBOUNCE_MS(20)
    ) key1_debounce (
        .clk(w_clk_50Mhz),
        .rst_n(~w_clk_rst),
        .key_n(key1),
        .press_pulse(key1_press_pulse)
    );

    always_comb begin
        if (aht10_display_mode)
            aht10_display_binary = aht10_humidity_x10[10:0];
        else if (aht10_temperature_x10 < 0)
            aht10_display_binary = -aht10_temperature_x10;
        else
            aht10_display_binary = aht10_temperature_x10[10:0];
    end

    jyd_aht10_bin11_to_bcd aht10_bcd_formatter (
        .binary(aht10_display_binary),
        .bcd(aht10_display_bcd)
    );

    // Preserve the SEG MMIO register and existing driver. Once the first
    // autonomous sample arrives, select the local 50 MHz AHT10 display path.
    always_comb begin
        if (aht10_data_valid)
            display_wdata = {16'b0, aht10_display_bcd};
        else
            display_wdata = seg_wdata;
    end

    display_seg seg_driver (
        .clk    (w_clk_50Mhz),
        .rst    (w_clk_rst),
        .s      (display_wdata),
        .seg1   (seg_output[6:0]),
        .seg2   (seg_output[16:10]),
        .seg3   (seg_output[26:20]),
        .seg4   (seg_output[36:30]),
        .ans    ({seg_output[39:38], seg_output[29:28], seg_output[19:18], seg_output[9:8]}),
        .scan_half(display_scan_half)
    ); 
    // The packed BCD value is x10. Illuminate the decimal point between the
    // tens and tenths digits in the first dual-digit display package.
    assign seg_output[7]  = aht10_data_valid && !display_scan_half;
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
        .uartRxPop(uartRxPop),
        .aht10SclIn(aht10SclIn),
        .aht10SdaIn(aht10SdaIn),
        .aht10SclDriveLow(aht10SclDriveLow),
        .aht10SdaDriveLow(aht10SdaDriveLow),
        .aht10TemperatureLocalX10(aht10_temperature_x10),
        .aht10HumidityLocalX10(aht10_humidity_x10),
        .aht10DataValidLocal(aht10_data_valid)
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

module jyd_key_debounce #(
    parameter integer CLK_HZ = 50_000_000,
    parameter integer DEBOUNCE_MS = 20
) (
    input  wire clk,
    input  wire rst_n,
    input  wire key_n,
    output reg  press_pulse
);
    localparam integer STABLE_CYCLES = (CLK_HZ / 1000) * DEBOUNCE_MS;

    (* ASYNC_REG = "TRUE" *) reg key_sync1;
    (* ASYNC_REG = "TRUE" *) reg key_sync2;
    reg key_state_n;
    reg [31:0] stable_count;

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            key_sync1   <= 1'b1;
            key_sync2   <= 1'b1;
            key_state_n <= 1'b1;
            stable_count <= 0;
            press_pulse <= 1'b0;
        end else begin
            key_sync1 <= key_n;
            key_sync2 <= key_sync1;
            press_pulse <= 1'b0;

            if (key_sync2 == key_state_n) begin
                stable_count <= 0;
            end else if (stable_count == STABLE_CYCLES - 1) begin
                stable_count <= 0;
                key_state_n <= key_sync2;
                if (!key_sync2)
                    press_pulse <= 1'b1;
            end else begin
                stable_count <= stable_count + 1'b1;
            end
        end
    end
endmodule

module jyd_aht10_bin11_to_bcd (
    input  wire [10:0] binary,
    output reg  [15:0] bcd
);
    integer i;
    reg [26:0] work;

    always @* begin
        work = 0;
        work[10:0] = binary;
        for (i = 0; i < 11; i = i + 1) begin
            if (work[14:11] >= 5) work[14:11] = work[14:11] + 3;
            if (work[18:15] >= 5) work[18:15] = work[18:15] + 3;
            if (work[22:19] >= 5) work[22:19] = work[22:19] + 3;
            if (work[26:23] >= 5) work[26:23] = work[26:23] + 3;
            work = work << 1;
        end
        bcd = work[26:11];
    end
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
