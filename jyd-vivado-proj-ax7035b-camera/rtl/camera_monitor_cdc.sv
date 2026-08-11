`timescale 1ns/1ps

// Passive DVP monitor. It never drives or gates camera signals and mirrors the
// hardware-validated FINAL_STABLE byte phase: negedge PCLK, skip first sample
// of each HREF interval, RGB565 low byte first.
module camera_monitor_cdc (
    input  wire        cpu_clk,
    input  wire        cpu_reset,
    input  wire        reset_n,
    input  wire        ov_pclk,
    input  wire        ov_vsync,
    input  wire        ov_href,
    input  wire [7:0]  ov_d,
    input  wire        cfg_done_async,
    input  wire        frame_valid_async,
    input  wire        video_locked_async,
    input  wire        cfg_error_async,
    input  wire        hdmi_hpd_async,
    output wire [31:0] camera_status,
    output reg  [31:0] camera_frame_count,
    output reg  [7:0]  camera_sample_rgb
);
    (* ASYNC_REG = "TRUE" *) reg cfg_done_pclk_meta = 1'b0;
    (* ASYNC_REG = "TRUE" *) reg cfg_done_pclk = 1'b0;
    reg vsync_d = 1'b0;
    reg href_d = 1'b0;
    reg byte_phase = 1'b0;
    reg [7:0] first_byte = 8'h00;
    reg [10:0] x_pixel = 11'd0;
    reg [9:0] y_line = 10'd0;
    reg frame_toggle_pclk = 1'b0;
    reg sample_toggle_pclk = 1'b0;
    reg [7:0] sample_data_pclk = 8'h00;

    wire vsync_rise = ov_vsync && !vsync_d;
    wire href_rise = ov_href && !href_d;
    wire href_fall = !ov_href && href_d;

    always @(negedge ov_pclk or negedge reset_n) begin
        if (!reset_n) begin
            cfg_done_pclk_meta <= 1'b0;
            cfg_done_pclk      <= 1'b0;
            vsync_d            <= 1'b0;
            href_d             <= 1'b0;
            byte_phase         <= 1'b0;
            first_byte         <= 8'h00;
            x_pixel            <= 11'd0;
            y_line             <= 10'd0;
            frame_toggle_pclk  <= 1'b0;
            sample_toggle_pclk <= 1'b0;
            sample_data_pclk   <= 8'h00;
        end else begin
            cfg_done_pclk_meta <= cfg_done_async;
            cfg_done_pclk      <= cfg_done_pclk_meta;
            vsync_d            <= ov_vsync;
            href_d             <= ov_href;

            if (vsync_rise) begin
                if (cfg_done_pclk)
                    frame_toggle_pclk <= ~frame_toggle_pclk;
                x_pixel    <= 11'd0;
                y_line     <= 10'd0;
                byte_phase <= 1'b0;
            end else begin
                if (ov_href) begin
                    if (href_rise) begin
                        // FINAL_STABLE permanently discards this phase sample.
                        x_pixel    <= 11'd0;
                        byte_phase <= 1'b0;
                    end else if (!byte_phase) begin
                        first_byte <= ov_d;
                        byte_phase <= 1'b1;
                    end else begin
                        byte_phase <= 1'b0;
                        if (cfg_done_pclk && y_line == 10'd240 && x_pixel == 11'd400) begin
                            sample_data_pclk   <= {ov_d[7:5], ov_d[2:0], first_byte[4:3]};
                            sample_toggle_pclk <= ~sample_toggle_pclk;
                        end
                        x_pixel <= x_pixel + 1'b1;
                    end
                end

                if (href_fall) begin
                    y_line     <= y_line + 1'b1;
                    x_pixel    <= 11'd0;
                    byte_phase <= 1'b0;
                end
            end
        end
    end

    (* ASYNC_REG = "TRUE" *) reg cfg_done_meta, cfg_done_sync;
    (* ASYNC_REG = "TRUE" *) reg frame_valid_meta, frame_valid_sync;
    (* ASYNC_REG = "TRUE" *) reg video_locked_meta, video_locked_sync;
    (* ASYNC_REG = "TRUE" *) reg cfg_error_meta, cfg_error_sync;
    (* ASYNC_REG = "TRUE" *) reg hdmi_hpd_meta, hdmi_hpd_sync;
    (* ASYNC_REG = "TRUE" *) reg frame_toggle_meta, frame_toggle_sync;
    (* ASYNC_REG = "TRUE" *) reg sample_toggle_meta, sample_toggle_sync;
    (* ASYNC_REG = "TRUE" *) reg [7:0] sample_data_meta, sample_data_sync;
    reg frame_toggle_seen;
    reg sample_toggle_seen;
    reg sample_valid;

    always @(posedge cpu_clk) begin
        if (cpu_reset) begin
            cfg_done_meta     <= 1'b0;
            cfg_done_sync     <= 1'b0;
            frame_valid_meta  <= 1'b0;
            frame_valid_sync  <= 1'b0;
            video_locked_meta <= 1'b0;
            video_locked_sync <= 1'b0;
            cfg_error_meta    <= 1'b0;
            cfg_error_sync    <= 1'b0;
            hdmi_hpd_meta     <= 1'b0;
            hdmi_hpd_sync     <= 1'b0;
            frame_toggle_meta <= 1'b0;
            frame_toggle_sync <= 1'b0;
            sample_toggle_meta <= 1'b0;
            sample_toggle_sync <= 1'b0;
            sample_data_meta  <= 8'h00;
            sample_data_sync  <= 8'h00;
            frame_toggle_seen <= 1'b0;
            sample_toggle_seen <= 1'b0;
            sample_valid      <= 1'b0;
            camera_frame_count <= 32'd0;
            camera_sample_rgb <= 8'h00;
        end else begin
            cfg_done_meta      <= cfg_done_async;
            cfg_done_sync      <= cfg_done_meta;
            frame_valid_meta   <= frame_valid_async;
            frame_valid_sync   <= frame_valid_meta;
            video_locked_meta  <= video_locked_async;
            video_locked_sync  <= video_locked_meta;
            cfg_error_meta     <= cfg_error_async;
            cfg_error_sync     <= cfg_error_meta;
            hdmi_hpd_meta      <= hdmi_hpd_async;
            hdmi_hpd_sync      <= hdmi_hpd_meta;
            frame_toggle_meta  <= frame_toggle_pclk;
            frame_toggle_sync  <= frame_toggle_meta;
            sample_toggle_meta <= sample_toggle_pclk;
            sample_toggle_sync <= sample_toggle_meta;
            sample_data_meta   <= sample_data_pclk;
            sample_data_sync   <= sample_data_meta;

            if (frame_toggle_sync != frame_toggle_seen) begin
                frame_toggle_seen  <= frame_toggle_sync;
                camera_frame_count <= camera_frame_count + 1'b1;
            end
            if (sample_toggle_sync != sample_toggle_seen) begin
                sample_toggle_seen <= sample_toggle_sync;
                camera_sample_rgb  <= sample_data_sync;
                sample_valid       <= 1'b1;
            end
        end
    end

    assign camera_status = {26'd0, sample_valid, hdmi_hpd_sync,
                            cfg_error_sync, video_locked_sync,
                            frame_valid_sync, cfg_done_sync};
endmodule
