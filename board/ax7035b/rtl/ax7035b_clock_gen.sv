`timescale 1ns/1ps

module ax7035b_clock_gen (
    input  wire sys_clk,
    output wire cpu_clk,
    output wire clk_50mhz,
    output wire reset,
    output wire locked
);
    wire clkfb_raw;
    wire clkfb;
    wire clkfboutb_unused;
    wire cpu_clk_raw;
    wire clk_50mhz_raw;
    wire clkout0b_unused;
    wire clkout1b_unused;
    wire clkout2_unused;
    wire clkout2b_unused;
    wire clkout3_unused;
    wire clkout3b_unused;
    wire clkout4_unused;
    wire clkout5_unused;
    wire clkout6_unused;

    // 50 MHz input, 1 GHz VCO, 100 MHz CPU and 50 MHz peripheral clocks.
    MMCME2_BASE #(
        .BANDWIDTH("OPTIMIZED"),
        .CLKIN1_PERIOD(20.000),
        .DIVCLK_DIVIDE(1),
        .CLKFBOUT_MULT_F(20.000),
        .CLKOUT0_DIVIDE_F(10.000),
        .CLKOUT1_DIVIDE(20),
        .STARTUP_WAIT("FALSE")
    ) u_mmcm (
        .CLKIN1(sys_clk),
        .CLKFBIN(clkfb),
        .RST(1'b0),
        .PWRDWN(1'b0),
        .CLKFBOUT(clkfb_raw),
        .CLKFBOUTB(clkfboutb_unused),
        .CLKOUT0(cpu_clk_raw),
        .CLKOUT1(clk_50mhz_raw),
        .CLKOUT0B(clkout0b_unused),
        .CLKOUT1B(clkout1b_unused),
        .CLKOUT2(clkout2_unused),
        .CLKOUT2B(clkout2b_unused),
        .CLKOUT3(clkout3_unused),
        .CLKOUT3B(clkout3b_unused),
        .CLKOUT4(clkout4_unused),
        .CLKOUT5(clkout5_unused),
        .CLKOUT6(clkout6_unused),
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
    wire unused_ok = &{1'b0, clkfboutb_unused, clkout0b_unused, clkout1b_unused,
                       clkout2_unused, clkout2b_unused, clkout3_unused,
                       clkout3b_unused, clkout4_unused, clkout5_unused,
                       clkout6_unused};
endmodule
