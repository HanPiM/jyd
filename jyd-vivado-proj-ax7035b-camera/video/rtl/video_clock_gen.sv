`timescale 1ns/1ps
module video_clock_gen (
    input  wire clk_50m,
    input  wire rst,
    output wire pix_clk,      // 25.2 MHz
    output wire ser_clk,      // 126 MHz = 5x pixel clock
    output wire locked
);
    wire clkfb_raw, clkfb;
    wire pix_raw, ser_raw;

    // 50 MHz / 5 * 63 = 630 MHz VCO
    // 630 / 25 = 25.2 MHz, 630 / 5 = 126 MHz.
    MMCME2_BASE #(
        .BANDWIDTH("OPTIMIZED"),
        .CLKFBOUT_MULT_F(63.0),
        .CLKFBOUT_PHASE(0.0),
        .CLKIN1_PERIOD(20.0),
        .CLKOUT0_DIVIDE_F(25.0),
        .CLKOUT0_PHASE(0.0),
        .CLKOUT1_DIVIDE(5),
        .CLKOUT1_PHASE(0.0),
        .DIVCLK_DIVIDE(5),
        .STARTUP_WAIT("FALSE")
    ) u_mmcm (
        .CLKIN1(clk_50m),
        .CLKFBIN(clkfb),
        .RST(rst),
        .PWRDWN(1'b0),
        .CLKFBOUT(clkfb_raw),
        .CLKOUT0(pix_raw),
        .CLKOUT1(ser_raw),
        .CLKOUT2(), .CLKOUT3(), .CLKOUT4(), .CLKOUT5(), .CLKOUT6(),
        .LOCKED(locked)
    );

    BUFG u_bufg_fb  (.I(clkfb_raw), .O(clkfb));
    BUFG u_bufg_pix (.I(pix_raw),   .O(pix_clk));
    BUFG u_bufg_ser (.I(ser_raw),   .O(ser_clk));
endmodule

