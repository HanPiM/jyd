`timescale 1ns/1ps

module ax7035b_camera_clock_gen (
    input  wire sys_clk,
    output wire cpu_clk,
    output wire clk_50mhz,
    output wire reset,
    output wire locked
);
    wire clkfb_raw;
    wire clkfb;
    wire cpu_clk_raw;
    wire clk_50mhz_raw;

    // Camera/RT-Thread demonstrator: 50 MHz input, 600 MHz VCO,
    // 75 MHz CPU and 50 MHz peripheral/tick clocks.
    MMCME2_BASE #(
        .BANDWIDTH("OPTIMIZED"),
        .CLKIN1_PERIOD(20.000),
        .DIVCLK_DIVIDE(1),
        .CLKFBOUT_MULT_F(12.000),
        .CLKOUT0_DIVIDE_F(8.000),
        .CLKOUT1_DIVIDE(12),
        .STARTUP_WAIT("FALSE")
    ) u_mmcm (
        .CLKIN1(sys_clk), .CLKFBIN(clkfb), .RST(1'b0), .PWRDWN(1'b0),
        .CLKFBOUT(clkfb_raw), .CLKFBOUTB(),
        .CLKOUT0(cpu_clk_raw), .CLKOUT1(clk_50mhz_raw),
        .CLKOUT0B(), .CLKOUT1B(), .CLKOUT2(), .CLKOUT2B(),
        .CLKOUT3(), .CLKOUT3B(), .CLKOUT4(), .CLKOUT5(), .CLKOUT6(),
        .LOCKED(locked)
    );

    BUFG u_clkfb_buf (.I(clkfb_raw), .O(clkfb));
    BUFG u_cpu_buf   (.I(cpu_clk_raw), .O(cpu_clk));
    BUFG u_50m_buf   (.I(clk_50mhz_raw), .O(clk_50mhz));

    reg [7:0] por_count;
    always @(posedge clk_50mhz or negedge locked) begin
        if (!locked)
            por_count <= 0;
        else if (!(&por_count))
            por_count <= por_count + 1'b1;
    end
    assign reset = ~(&por_count);
endmodule
