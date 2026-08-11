`timescale 1ns/1ps
module framebuffer_pingpong_rgb332 #(
    parameter integer FRAME_PIXELS = 320*240,
    parameter integer ADDR_W = 17
) (
    // Camera/write port
    input  wire                 wr_clk,
    input  wire                 wr_en,
    input  wire                 wr_bank,
    input  wire [ADDR_W-1:0]    wr_addr,
    input  wire [7:0]           wr_data,

    // HDMI/read port
    input  wire                 rd_clk,
    input  wire                 rd_bank,
    input  wire [ADDR_W-1:0]    rd_addr,
    output wire [7:0]           rd_data
);
    // Two independent simple-dual-port block RAMs. Each frame is
    // 320x240x8 = 614,400 bits; two frames use 1,228,800 bits.
    (* ram_style = "block" *) reg [7:0] fb0 [0:FRAME_PIXELS-1];
    (* ram_style = "block" *) reg [7:0] fb1 [0:FRAME_PIXELS-1];

    reg [7:0] fb0_q = 8'h00;
    reg [7:0] fb1_q = 8'h00;

    // Addresses are guaranteed in range by the producer/consumer logic.
    // Keeping the RAM template minimal improves Vivado block-RAM inference.
    always @(posedge wr_clk) begin
        if (wr_en) begin
            if (wr_bank)
                fb1[wr_addr] <= wr_data;
            else
                fb0[wr_addr] <= wr_data;
        end
    end

    always @(posedge rd_clk) begin
        fb0_q <= fb0[rd_addr];
        fb1_q <= fb1[rd_addr];
    end

    assign rd_data = rd_bank ? fb1_q : fb0_q;
endmodule

