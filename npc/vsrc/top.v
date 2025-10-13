module top(
    input clk,
    input rst,
    input [4:0] btn,
    input [15:0] sw,
    input ps2_clk,
    input ps2_data,
    input uart_rx,
    output uart_tx,
    output [15:0] ledr,
    output VGA_CLK,
    output VGA_HSYNC,
    output VGA_VSYNC,
    output VGA_BLANK_N,
    output [7:0] VGA_R,
    output [7:0] VGA_G,
    output [7:0] VGA_B,
    output [7:0] seg0,
    output [7:0] seg1,
    output [7:0] seg2,
    output [7:0] seg3,
    output [7:0] seg4,
    output [7:0] seg5,
    output [7:0] seg6,
    output [7:0] seg7
);

    wire [7:0] ps2_out;
    wire idle;
    wire ps2_ready;
    ps2test _keyboard(
        .clk(clk),
        .ps2_clk(ps2_clk),
        .d(ps2_data),
        .data(ps2_out),
        .ready(ps2_ready)
    );
    /*
    always@(*)begin
        if(ps2_ready)$display("ps2_out=%h %h",ps2_out[7:4],ps2_out[3:0]);
        //$display("psready=%b",ps2_ready);
        //if(ps2_clk)$display("psclk=%b",ps2_clk);
    end
    */
    assign ledr[0]=idle;
    assign ledr[1]=ps2_ready;

    wire [7:0] seglow, seghigh;

    bcd7seg _high(
        .bcd(ps2_out[7:4]),
        .seg(seghigh)
    );
    bcd7seg _low(
        .bcd(ps2_out[3:0]),
        .seg(seglow)
    );

    assign seg0=ps2_ready?seglow:8'hff;
    assign seg1=ps2_ready?seghigh:8'hff;
endmodule
