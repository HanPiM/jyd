`timescale 1ns/1ps

module top_ax7035b_camera_soc (
    input  wire        clk_50m,
    input  wire        reset_n,
    input  wire        key1_n,
    input  wire        key2_n,
    input  wire        key3_n,
    input  wire        key4_n,
    input  wire        uart_rx,
    output wire        uart_tx,
    inout  wire        aht10_scl,
    inout  wire        aht10_sda,
    output wire [7:0]  seg_digit,
    output wire [5:0]  seg_select,
    input  wire        ov_pclk,
    input  wire        ov_vsync,
    input  wire        ov_href,
    input  wire [7:0]  ov_d,
    output wire        ov_xclk,
    inout  wire        ov_scl,
    inout  wire        ov_sda,
    output wire        ov_reset_n,
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
    output wire        led1_n,
    output wire        led2_n,
    output wire        led3_n,
    output wire        led4_n
);
    wire cpu_clk;
    wire clk_50mhz;
    wire cpu_por_reset;
    wire cpu_reset = cpu_por_reset | ~reset_n;
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
    wire [31:0] camera_status;
    wire [31:0] camera_frame_count;
    wire [7:0] camera_sample_rgb;
    wire camera_force_colorbar;

    // The hardware-validated video top is instantiated unchanged. Its LED
    // status outputs also provide the four matching MMIO status bits.
    top u_video (
        .clk_50m(clk_50m), .reset_n(reset_n),
        .key1_n(key1_n), .key2_n(key2_n),
        .key3_n(key3_n), .key4_n(key4_n),
        .cpu_force_colorbar(camera_force_colorbar),
        .ov_pclk(ov_pclk), .ov_vsync(ov_vsync), .ov_href(ov_href), .ov_d(ov_d),
        .ov_xclk(ov_xclk), .ov_scl(ov_scl), .ov_sda(ov_sda), .ov_reset_n(ov_reset_n),
        .hdmi_clk_p(hdmi_clk_p), .hdmi_clk_n(hdmi_clk_n),
        .hdmi_d0_p(hdmi_d0_p), .hdmi_d0_n(hdmi_d0_n),
        .hdmi_d1_p(hdmi_d1_p), .hdmi_d1_n(hdmi_d1_n),
        .hdmi_d2_p(hdmi_d2_p), .hdmi_d2_n(hdmi_d2_n),
        .hdmi_out_en(hdmi_out_en), .hdmi_hpd(hdmi_hpd),
        .led1_n(led1_n), .led2_n(led2_n), .led3_n(led3_n), .led4_n(led4_n)
    );

    ax7035b_camera_clock_gen u_cpu_clock (
        .sys_clk(clk_50m), .cpu_clk(cpu_clk), .clk_50mhz(clk_50mhz),
        .reset(cpu_por_reset), .locked(clocks_locked)
    );

    camera_monitor_cdc u_camera_monitor (
        .cpu_clk(cpu_clk), .cpu_reset(cpu_reset), .reset_n(reset_n),
        .ov_pclk(ov_pclk), .ov_vsync(ov_vsync), .ov_href(ov_href), .ov_d(ov_d),
        .cfg_done_async(~led1_n), .frame_valid_async(~led2_n),
        .video_locked_async(~led3_n), .cfg_error_async(~led4_n),
        .hdmi_hpd_async(hdmi_hpd), .camera_status(camera_status),
        .camera_frame_count(camera_frame_count), .camera_sample_rgb(camera_sample_rgb)
    );

    JYDFPGATop u_soc (
        .clock(cpu_clk), .reset(cpu_reset), .clk_50Mhz(clk_50mhz),
        .led(led_unused), .seg(seg_value),
        .uartTxPush(uart_tx_push), .uartTxData(uart_tx_data),
        .uartTxFull(uart_tx_full), .uartRxData(uart_rx_data),
        .uartRxEmpty(uart_rx_empty), .uartRxPop(uart_rx_pop),
        .aht10SclIn(aht10_scl), .aht10SdaIn(aht10_sda),
        .aht10SclDriveLow(aht10_scl_drive_low),
        .aht10SdaDriveLow(aht10_sda_drive_low),
        .cameraStatus(camera_status), .cameraFrameCount(camera_frame_count),
        .cameraSampleRgb(camera_sample_rgb),
        .cameraForceColorbar(camera_force_colorbar)
    );

    ax7035b_uart #(.CLK_HZ(75_000_000), .BAUD_RATE(115200)) u_uart (
        .clk(cpu_clk), .reset(cpu_reset), .uart_rx(uart_rx), .uart_tx(uart_tx),
        .tx_push(uart_tx_push), .tx_data(uart_tx_data), .tx_full(uart_tx_full),
        .rx_data(uart_rx_data), .rx_empty(uart_rx_empty), .rx_pop(uart_rx_pop)
    );

    ax7035b_seg_scan u_seg (
        .clk(clk_50mhz), .reset(cpu_reset), .bcd_value_cpu(seg_value),
        .seg_digit(seg_digit), .seg_select(seg_select)
    );

    assign aht10_scl = aht10_scl_drive_low ? 1'b0 : 1'bz;
    assign aht10_sda = aht10_sda_drive_low ? 1'b0 : 1'bz;
    wire unused_ok = &{1'b0, clocks_locked, led_unused};
endmodule
