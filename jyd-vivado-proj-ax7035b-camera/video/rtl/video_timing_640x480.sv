`timescale 1ns/1ps
module video_timing_640x480 (
    input  wire        pix_clk,
    input  wire        rst,
    output reg  [9:0]  h_count,
    output reg  [9:0]  v_count,
    output wire        de,
    output wire        hsync,
    output wire        vsync,
    output wire        vblank_start
);
    localparam H_ACTIVE = 640;
    localparam H_FP     = 16;
    localparam H_SYNC   = 96;
    localparam H_BP     = 48;
    localparam H_TOTAL  = H_ACTIVE + H_FP + H_SYNC + H_BP; // 800

    localparam V_ACTIVE = 480;
    localparam V_FP     = 10;
    localparam V_SYNC   = 2;
    localparam V_BP     = 33;
    localparam V_TOTAL  = V_ACTIVE + V_FP + V_SYNC + V_BP; // 525

    always @(posedge pix_clk) begin
        if (rst) begin
            h_count <= 10'd0;
            v_count <= 10'd0;
        end else if (h_count == H_TOTAL-1) begin
            h_count <= 10'd0;
            if (v_count == V_TOTAL-1)
                v_count <= 10'd0;
            else
                v_count <= v_count + 10'd1;
        end else begin
            h_count <= h_count + 10'd1;
        end
    end

    assign de = (h_count < H_ACTIVE) && (v_count < V_ACTIVE);
    // 640x480@60 uses negative sync polarity.
    assign hsync = ~((h_count >= H_ACTIVE + H_FP) &&
                     (h_count <  H_ACTIVE + H_FP + H_SYNC));
    assign vsync = ~((v_count >= V_ACTIVE + V_FP) &&
                     (v_count <  V_ACTIVE + V_FP + V_SYNC));
    assign vblank_start = (h_count == 10'd0) && (v_count == V_ACTIVE);
endmodule

