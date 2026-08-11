`timescale 1ns/1ps
module top (
    input  wire        clk_50m,
    input  wire        reset_n,      // AX7035B RESET button, active low
    input  wire        key1_n,       // active low; hold to force color bars
    input  wire        key2_n,       // active low; toggle emergency byte-phase correction
    input  wire        key3_n,       // active low; toggle RGB565 byte-order fallback
    input  wire        key4_n,       // active low; toggle red/blue channel swap fallback
    input  wire        cpu_force_colorbar,

    // OV5640 V5 DVP/SCCB interface (wired to J9)
    input  wire        ov_pclk,
    input  wire        ov_vsync,
    input  wire        ov_href,
    input  wire [7:0]  ov_d,
    output wire        ov_xclk,
    inout  wire        ov_scl,
    inout  wire        ov_sda,
    output wire        ov_reset_n,

    // AX7035B HDMI OUT (J6)
    output wire        hdmi_clk_p,
    output wire        hdmi_clk_n,
    output wire        hdmi_d0_p,
    output wire        hdmi_d0_n,
    output wire        hdmi_d1_p,
    output wire        hdmi_d1_n,
    output wire        hdmi_d2_p,
    output wire        hdmi_d2_n,
    output wire        hdmi_out_en,
    input  wire        hdmi_hpd,

    // Status LEDs, active low on AX7035B
    output wire        led1_n,
    output wire        led2_n,
    output wire        led3_n,
    output wire        led4_n
);
    localparam integer FRAME_PIXELS = 320*240;
    localparam integer ADDR_W = 17;

    wire sys_rst = ~reset_n;

    // ---------------------------------------------------------------------
    // The previous diagnostic build exposed byte-phase/order toggles on
    // KEY2/KEY3. Hardware testing showed KEY2 gives the clean correction: the
    // first sample seen when HREF asserts is a pre-data/phase sample, so it is
    // discarded permanently in the capture instantiation below. KEY3 is not
    // needed. KEY1 remains hold-to-show-color-bars. KEY4 remains an optional
    // R/B display diagnostic and defaults OFF.
    // ---------------------------------------------------------------------
    wire mode_skip_first_50m;
    wire mode_swap_bytes_50m;
    wire mode_swap_rb_50m;

    key_toggle_debounce u_key2_mode (
        .clk(clk_50m), .rst(sys_rst), .key_n(key2_n), .toggle(mode_skip_first_50m)
    );
    key_toggle_debounce u_key3_mode (
        .clk(clk_50m), .rst(sys_rst), .key_n(key3_n), .toggle(mode_swap_bytes_50m)
    );
    key_toggle_debounce u_key4_mode (
        .clk(clk_50m), .rst(sys_rst), .key_n(key4_n), .toggle(mode_swap_rb_50m)
    );

    // ---------------------------------------------------------------------
    // Clock generation: no Clocking Wizard IP, only 7-series primitives.
    // ---------------------------------------------------------------------
    wire pix_clk, ser_clk, video_locked;
    wire cam_xclk_i, cam_clk_locked;

    video_clock_gen u_video_clk (
        .clk_50m(clk_50m),
        .rst(sys_rst),
        .pix_clk(pix_clk),
        .ser_clk(ser_clk),
        .locked(video_locked)
    );

    camera_clock_gen u_cam_clk (
        .clk_50m(clk_50m),
        .rst(sys_rst),
        .cam_xclk(cam_xclk_i),
        .locked(cam_clk_locked)
    );

    // Forward XCLK through an ODDR so both clock edges are generated in the IOB.
    wire ov_xclk_fwd;
    ODDR #(
        .DDR_CLK_EDGE("SAME_EDGE")
    ) u_xclk_oddr (
        .Q(ov_xclk_fwd),
        .C(cam_xclk_i),
        .CE(1'b1),
        .D1(1'b1),
        .D2(1'b0),
        .R(sys_rst),
        .S(1'b0)
    );
    assign ov_xclk = ov_xclk_fwd;

    // Camera PCLK is wired to J9 pin 32 / FPGA E19, an SRCC input.
    // Buffer it explicitly onto the global clock network.
    wire cam_pclk;
    BUFG u_cam_pclk_buf (.I(ov_pclk), .O(cam_pclk));

    (* ASYNC_REG = "TRUE" *) reg mode_skip_meta = 1'b0;
    (* ASYNC_REG = "TRUE" *) reg mode_skip_cam  = 1'b0;
    (* ASYNC_REG = "TRUE" *) reg mode_swap_meta = 1'b0;
    (* ASYNC_REG = "TRUE" *) reg mode_swap_cam  = 1'b0;
    always @(posedge cam_pclk) begin
        mode_skip_meta <= mode_skip_first_50m;
        mode_skip_cam  <= mode_skip_meta;
        mode_swap_meta <= mode_swap_bytes_50m;
        mode_swap_cam  <= mode_swap_meta;
    end

    // ---------------------------------------------------------------------
    // OV5640 hardware power/reset sequence and SCCB register programming.
    // ---------------------------------------------------------------------
    reg [21:0] power_count = 22'd0;
    always @(posedge clk_50m) begin
        if (sys_rst || !cam_clk_locked)
            power_count <= 22'd0;
        else if (power_count < 22'd2_000_000)
            power_count <= power_count + 1'b1;
    end

    // Module PWDN is active high and is strapped directly to GND at J9-38.
    // Hold RESET# low for about 2 ms after XCLK is stable.
    assign ov_reset_n = cam_clk_locked && (power_count >= 22'd100_000);
    wire cfg_enable = cam_clk_locked && (power_count >= 22'd250_000);

    wire scl_drive_low;
    wire sda_drive_low;
    wire cfg_busy, cfg_done, cfg_error;
    wire [7:0] cfg_index;
    wire ov_sda_in = ov_sda;
    assign ov_scl = scl_drive_low ? 1'b0 : 1'bz;
    assign ov_sda = sda_drive_low ? 1'b0 : 1'bz;

    ov5640_config u_cam_cfg (
        .clk(clk_50m),
        .rst(sys_rst),
        .enable(cfg_enable),
        .sda_in(ov_sda_in),
        .scl_drive_low(scl_drive_low),
        .sda_drive_low(sda_drive_low),
        .busy(cfg_busy),
        .done(cfg_done),
        .error(cfg_error),
        .current_index(cfg_index)
    );

    // Synchronize configuration-complete into the camera PCLK domain.
    (* ASYNC_REG = "TRUE" *) reg cfg_done_meta = 1'b0;
    (* ASYNC_REG = "TRUE" *) reg cfg_done_pclk = 1'b0;
    always @(posedge cam_pclk) begin
        cfg_done_meta <= cfg_done;
        cfg_done_pclk <= cfg_done_meta;
    end
    wire capture_rst = ~cfg_done_pclk;

    // ---------------------------------------------------------------------
    // Camera capture: RGB565 800x480 -> center crop -> 320x240 RGB332.
    // Ping-pong BRAM stores two complete frames, avoiding DDR3/MIG.
    // ---------------------------------------------------------------------
    wire fb_we;
    wire fb_wr_bank;
    wire [ADDR_W-1:0] fb_wr_addr;
    wire [7:0] fb_wr_data;
    wire frame_seq_pclk;
    wire complete_bank_pclk;
    wire saw_complete_frame_pclk;

    ov5640_capture_downsample #(
        .FRAME_PIXELS(FRAME_PIXELS),
        .ADDR_W(ADDR_W)
    ) u_capture (
        .pclk(cam_pclk),
        .rst(capture_rst),
        .cam_d(ov_d),
        .cam_href(ov_href),
        .cam_vsync(ov_vsync),
        // Hardware-validated fixed phase: the user's board produced a
        // smooth, correctly colored image when the former KEY2 mode was ON.
        // This drops the pre-data sample seen at each HREF assertion, then
        // keeps the documented RGB565 low-byte-first interpretation.
        .cfg_skip_first_byte(1'b1),
        .cfg_swap_byte_order(1'b0),
        .fb_we(fb_we),
        .fb_wr_bank(fb_wr_bank),
        .fb_wr_addr(fb_wr_addr),
        .fb_wr_data(fb_wr_data),
        .frame_seq(frame_seq_pclk),
        .complete_bank(complete_bank_pclk),
        .saw_complete_frame(saw_complete_frame_pclk)
    );

    // ---------------------------------------------------------------------
    // Frame-publish CDC: metadata is synchronized into the HDMI pixel domain.
    // The completed bank is selected only at vertical blanking.
    // ---------------------------------------------------------------------
    (* ASYNC_REG = "TRUE" *) reg frame_seq_meta = 1'b0;
    (* ASYNC_REG = "TRUE" *) reg frame_seq_sync = 1'b0;
    (* ASYNC_REG = "TRUE" *) reg bank_meta = 1'b0;
    (* ASYNC_REG = "TRUE" *) reg bank_sync = 1'b0;

    wire video_rst = sys_rst | ~video_locked;
    always @(posedge pix_clk) begin
        if (video_rst) begin
            frame_seq_meta <= 1'b0;
            frame_seq_sync <= 1'b0;
            bank_meta      <= 1'b0;
            bank_sync      <= 1'b0;
        end else begin
            frame_seq_meta <= frame_seq_pclk;
            frame_seq_sync <= frame_seq_meta;
            bank_meta      <= complete_bank_pclk;
            bank_sync      <= bank_meta;
        end
    end

    wire [9:0] h_count, v_count;
    wire de, hsync, vsync, vblank_start;
    video_timing_640x480 u_timing (
        .pix_clk(pix_clk),
        .rst(video_rst),
        .h_count(h_count),
        .v_count(v_count),
        .de(de),
        .hsync(hsync),
        .vsync(vsync),
        .vblank_start(vblank_start)
    );

    reg read_bank = 1'b0;
    reg frame_seen = 1'b0;
    reg frame_valid = 1'b0;
    reg pending_frame = 1'b0;

    always @(posedge pix_clk) begin
        if (video_rst) begin
            read_bank     <= 1'b0;
            frame_seen    <= 1'b0;
            frame_valid   <= 1'b0;
            pending_frame <= 1'b0;
        end else begin
            if (frame_seq_sync != frame_seen)
                pending_frame <= 1'b1;

            if (vblank_start && pending_frame) begin
                read_bank     <= bank_sync;
                frame_seen    <= frame_seq_sync;
                frame_valid   <= 1'b1;
                pending_frame <= 1'b0;
            end
        end
    end

    // 2x nearest-neighbor scaling: 320x240 -> 640x480.
    wire [8:0] src_x = h_count[9:1];
    wire [7:0] src_y = v_count[8:1];
    wire [ADDR_W-1:0] fb_rd_addr = de ? (src_y * 17'd320 + src_x) : {ADDR_W{1'b0}};
    wire [7:0] fb_rd_data;

    framebuffer_pingpong_rgb332 #(
        .FRAME_PIXELS(FRAME_PIXELS),
        .ADDR_W(ADDR_W)
    ) u_fb (
        .wr_clk(cam_pclk),
        .wr_en(fb_we),
        .wr_bank(fb_wr_bank),
        .wr_addr(fb_wr_addr),
        .wr_data(fb_wr_data),
        .rd_clk(pix_clk),
        .rd_bank(read_bank),
        .rd_addr(fb_rd_addr),
        .rd_data(fb_rd_data)
    );

    // BRAM read is one pixel-clock latency; delay video timing to match.
    reg de_d = 1'b0;
    reg hsync_d = 1'b1;
    reg vsync_d = 1'b1;
    reg [9:0] h_d = 10'd0;
    reg [9:0] v_d = 10'd0;
    always @(posedge pix_clk) begin
        if (video_rst) begin
            de_d    <= 1'b0;
            hsync_d <= 1'b1;
            vsync_d <= 1'b1;
            h_d     <= 10'd0;
            v_d     <= 10'd0;
        end else begin
            de_d    <= de;
            hsync_d <= hsync;
            vsync_d <= vsync;
            h_d     <= h_count;
            v_d     <= v_count;
        end
    end

    (* ASYNC_REG = "TRUE" *) reg mode_rb_meta = 1'b0;
    (* ASYNC_REG = "TRUE" *) reg mode_rb_pix  = 1'b0;
    (* ASYNC_REG = "TRUE" *) reg force_bar_meta = 1'b0;
    (* ASYNC_REG = "TRUE" *) reg force_bar_pix  = 1'b0;
    always @(posedge pix_clk) begin
        if (video_rst) begin
            mode_rb_meta <= 1'b0;
            mode_rb_pix  <= 1'b0;
            force_bar_meta <= 1'b0;
            force_bar_pix  <= 1'b0;
        end else begin
            mode_rb_meta <= mode_swap_rb_50m;
            mode_rb_pix  <= mode_rb_meta;
            force_bar_meta <= cpu_force_colorbar;
            force_bar_pix  <= force_bar_meta;
        end
    end

    function automatic [23:0] color_bar;
        input [9:0] x;
        begin
            if      (x < 10'd80)  color_bar = 24'hFFFFFF;
            else if (x < 10'd160) color_bar = 24'hFFFF00;
            else if (x < 10'd240) color_bar = 24'h00FFFF;
            else if (x < 10'd320) color_bar = 24'h00FF00;
            else if (x < 10'd400) color_bar = 24'hFF00FF;
            else if (x < 10'd480) color_bar = 24'hFF0000;
            else if (x < 10'd560) color_bar = 24'h0000FF;
            else                  color_bar = 24'h000000;
        end
    endfunction

    wire [7:0] fb_r8 = {fb_rd_data[7:5], fb_rd_data[7:5], fb_rd_data[7:6]};
    wire [7:0] fb_g8 = {fb_rd_data[4:2], fb_rd_data[4:2], fb_rd_data[4:3]};
    wire [7:0] fb_b8 = {fb_rd_data[1:0], fb_rd_data[1:0], fb_rd_data[1:0], fb_rd_data[1:0]};

    reg [23:0] pixel_rgb;
    always @* begin
        if (!de_d)
            pixel_rgb = 24'h000000;
        else if (!frame_valid || !key1_n || force_bar_pix)
            pixel_rgb = color_bar(h_d);
        else
            pixel_rgb = mode_rb_pix ? {fb_b8, fb_g8, fb_r8}
                                     : {fb_r8, fb_g8, fb_b8};
    end

    // ---------------------------------------------------------------------
    // DVI-over-HDMI TMDS output.
    // ---------------------------------------------------------------------
    wire [2:0] tmds_data_p;
    wire [2:0] tmds_data_n;

    dvi_out_wrapper u_dvi (
        .pixel_clk(pix_clk),
        .serial_clk(ser_clk),
        .rst(video_rst),
        .red(pixel_rgb[23:16]),
        .green(pixel_rgb[15:8]),
        .blue(pixel_rgb[7:0]),
        .de(de_d),
        .hsync(hsync_d),
        .vsync(vsync_d),
        .tmds_clk_p(hdmi_clk_p),
        .tmds_clk_n(hdmi_clk_n),
        .tmds_data_p(tmds_data_p),
        .tmds_data_n(tmds_data_n)
    );

    assign hdmi_d0_p = tmds_data_p[0];
    assign hdmi_d0_n = tmds_data_n[0];
    assign hdmi_d1_p = tmds_data_p[1];
    assign hdmi_d1_n = tmds_data_n[1];
    assign hdmi_d2_p = tmds_data_p[2];
    assign hdmi_d2_n = tmds_data_n[2];

    // AX7035B manual: HDMI1_OUT_EN high supplies +5V to the monitor.
    assign hdmi_out_en = video_locked;

    // Status: LED1=config done, LED2=valid frame, LED3=video clock locked,
    // LED4=SCCB ACK error. LEDs are active-low.
    assign led1_n = cfg_done      ? 1'b0 : 1'b1;
    assign led2_n = frame_valid   ? 1'b0 : 1'b1;
    assign led3_n = video_locked  ? 1'b0 : 1'b1;
    assign led4_n = cfg_error     ? 1'b0 : 1'b1;

    // HPD is deliberately not required for fixed 640x480 timing. Keep it as
    // an input for ILA/debug or later CPU-visible status without gating TMDS.
    wire _unused_hpd = hdmi_hpd;
endmodule


// -------------------------------------------------------------------------
// Small active-low pushbutton debouncer with persistent toggle output.
// A mode toggles once per physical press after the input has been stable for
// about 5 ms at 50 MHz.
// -------------------------------------------------------------------------
module key_toggle_debounce #(
    parameter integer STABLE_CYCLES = 250000
) (
    input  wire clk,
    input  wire rst,
    input  wire key_n,
    output reg  toggle
);
    localparam integer CW = (STABLE_CYCLES <= 2) ? 1 : $clog2(STABLE_CYCLES);
    (* ASYNC_REG = "TRUE" *) reg key_meta = 1'b1;
    (* ASYNC_REG = "TRUE" *) reg key_sync = 1'b1;
    reg key_stable = 1'b1;
    reg [CW-1:0] cnt = {CW{1'b0}};

    always @(posedge clk) begin
        if (rst) begin
            key_meta   <= 1'b1;
            key_sync   <= 1'b1;
            key_stable <= 1'b1;
            cnt        <= {CW{1'b0}};
            toggle     <= 1'b0;
        end else begin
            key_meta <= key_n;
            key_sync <= key_meta;

            if (key_sync == key_stable) begin
                cnt <= {CW{1'b0}};
            end else if (cnt == STABLE_CYCLES-1) begin
                cnt        <= {CW{1'b0}};
                key_stable <= key_sync;
                if (!key_sync)
                    toggle <= ~toggle;
            end else begin
                cnt <= cnt + 1'b1;
            end
        end
    end
endmodule
