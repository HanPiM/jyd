`timescale 1ns/1ps
module camera_clock_gen (
    input  wire clk_50m,
    input  wire rst,
    output wire cam_xclk,     // 24 MHz
    output wire locked
);
    wire clkfb_raw, clkfb;
    wire cam_raw;

    // 50 MHz * 12 / 1 = 600 MHz VCO; 600 / 25 = 24 MHz.
    MMCME2_BASE #(
        .BANDWIDTH("OPTIMIZED"),
        .CLKFBOUT_MULT_F(12.0),
        .CLKFBOUT_PHASE(0.0),
        .CLKIN1_PERIOD(20.0),
        .CLKOUT0_DIVIDE_F(25.0),
        .CLKOUT0_PHASE(0.0),
        .DIVCLK_DIVIDE(1),
        .STARTUP_WAIT("FALSE")
    ) u_mmcm (
        .CLKIN1(clk_50m),
        .CLKFBIN(clkfb),
        .RST(rst),
        .PWRDWN(1'b0),
        .CLKFBOUT(clkfb_raw),
        .CLKOUT0(cam_raw),
        .CLKOUT1(), .CLKOUT2(), .CLKOUT3(), .CLKOUT4(), .CLKOUT5(), .CLKOUT6(),
        .LOCKED(locked)
    );

    BUFG u_bufg_fb  (.I(clkfb_raw), .O(clkfb));
    BUFG u_bufg_cam (.I(cam_raw),   .O(cam_xclk));
endmodule

