`timescale 1ns/1ps
module ov5640_capture_downsample #(
    parameter integer FRAME_PIXELS = 320*240,
    parameter integer ADDR_W = 17
) (
    input  wire                 pclk,
    input  wire                 rst,
    input  wire [7:0]           cam_d,
    input  wire                 cam_href,
    input  wire                 cam_vsync,

    // Runtime rescue/debug controls. They are latched only at frame start.
    // Default 0/0 matches the ACM5640-V5 manual: low byte first, no byte skip.
    input  wire                 cfg_skip_first_byte,
    input  wire                 cfg_swap_byte_order,

    output reg                  fb_we,
    output reg                  fb_wr_bank,
    output reg [ADDR_W-1:0]     fb_wr_addr,
    output reg [7:0]            fb_wr_data,

    output reg                  frame_seq,
    output reg                  complete_bank,
    output reg                  saw_complete_frame
);
    reg vsync_d = 1'b0;
    reg href_d  = 1'b0;
    reg byte_phase = 1'b0;
    reg [7:0] first_byte = 8'h00;
    reg [10:0] x_pix = 11'd0;
    reg [9:0]  y_line = 10'd0;
    reg [ADDR_W:0] sample_count = 0;

    reg active_skip_first = 1'b0;
    reg active_swap_bytes = 1'b0;

    wire vsync_rise = cam_vsync & ~vsync_d;
    wire href_rise  = cam_href  & ~href_d;
    wire href_fall  = ~cam_href & href_d;

    // IMPORTANT:
    // ACM5640-V5 reference configuration uses 0x4740=0x01. Its manual states
    // that data is output on the PCLK rising edge. Therefore the safest point
    // to *sample* D[7:0]/HREF/VSYNC is the opposite (falling) edge, about half
    // a PCLK later. Pixel assembly is done on that same falling edge so there
    // is no extra one-cycle HREF/data staging that can shift the RGB565 byte
    // pairing. fb_we/address/data then remain stable until the following PCLK
    // rising edge, where the BRAM write port consumes them.
    always @(negedge pclk) begin
        if (rst) begin
            vsync_d            <= 1'b0;
            href_d             <= 1'b0;
            byte_phase         <= 1'b0;
            first_byte         <= 8'h00;
            x_pix              <= 11'd0;
            y_line             <= 10'd0;
            fb_we              <= 1'b0;
            fb_wr_bank         <= 1'b0;
            fb_wr_addr         <= {ADDR_W{1'b0}};
            fb_wr_data         <= 8'h00;
            sample_count       <= 0;
            frame_seq          <= 1'b0;
            complete_bank      <= 1'b0;
            saw_complete_frame <= 1'b0;
            active_skip_first  <= 1'b0;
            active_swap_bytes  <= 1'b0;
        end else begin
            vsync_d <= cam_vsync;
            href_d  <= cam_href;
            fb_we   <= 1'b0;

            if (vsync_rise) begin
                // Publish only a complete 320x240 frame.
                if (sample_count == FRAME_PIXELS) begin
                    complete_bank      <= fb_wr_bank;
                    frame_seq          <= ~frame_seq;
                    saw_complete_frame <= 1'b1;
                    fb_wr_bank         <= ~fb_wr_bank;
                end

                fb_wr_addr   <= {ADDR_W{1'b0}};
                sample_count <= 0;
                x_pix        <= 11'd0;
                y_line       <= 10'd0;
                byte_phase   <= 1'b0;

                // Apply key-selected rescue modes only at a frame boundary.
                active_skip_first <= cfg_skip_first_byte;
                active_swap_bytes <= cfg_swap_byte_order;
            end else begin
                if (cam_href) begin
                    // Treat the first active sample of every line explicitly.
                    // This prevents a delayed HREF edge from inheriting the
                    // previous line's byte phase. KEY2 can optionally discard
                    // this sample if a particular physical wiring/timing setup
                    // presents HREF one byte late.
                    if (href_rise) begin
                        x_pix      <= 11'd0;
                        byte_phase <= 1'b0;

                        if (!active_skip_first) begin
                            first_byte <= cam_d;
                            byte_phase <= 1'b1;
                        end
                    end else begin
                        if (!byte_phase) begin
                            first_byte <= cam_d;
                            byte_phase <= 1'b1;
                        end else begin
                            byte_phase <= 1'b0;

                            // Source is 800x480. Crop x=80..719 then take every
                            // other x/y sample -> 320x240. Store RGB332 so two
                            // complete frames fit in XC7A35T block RAM.
                            if ((y_line < 10'd480) && !y_line[0] &&
                                (x_pix >= 11'd80) && (x_pix < 11'd720) && !x_pix[0] &&
                                (sample_count < FRAME_PIXELS)) begin
                                fb_we      <= 1'b1;
                                fb_wr_addr <= sample_count[ADDR_W-1:0];

                                if (!active_swap_bytes) begin
                                    // Manual/default RGB565 byte sequence:
                                    // first_byte = LOW  = {G[2:0], B[4:0]}
                                    // cam_d      = HIGH = {R[4:0], G[5:3]}
                                    fb_wr_data <= {cam_d[7:5], cam_d[2:0], first_byte[4:3]};
                                end else begin
                                    // Emergency fallback if the physical stream
                                    // is observed high-byte-first.
                                    fb_wr_data <= {first_byte[7:5], first_byte[2:0], cam_d[4:3]};
                                end

                                sample_count <= sample_count + 1'b1;
                            end
                            x_pix <= x_pix + 1'b1;
                        end
                    end
                end

                if (href_fall) begin
                    y_line     <= y_line + 1'b1;
                    x_pix      <= 11'd0;
                    byte_phase <= 1'b0;
                end
            end
        end
    end
endmodule

