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
    output reg [7:0] seg0,
    output reg [7:0] seg1,
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
        .ready(ps2_ready),
        .idle(idle)
    );
    assign ledr[0]=clk;
    assign ledr[1]=ps2_clk;

    wire [7:0] seglow, seghigh;

    bcd7seg _high(
        .bcd(ps2_out[7:4]),
        .seg(seghigh)
    );
    bcd7seg _low(
        .bcd(ps2_out[3:0]),
        .seg(seglow)
    );

    wire [7:0] ascii_out;
    keyv2ascii _key2ascii(
        .keyv(ps2_out),
        .asciiv(ascii_out)
    );
    bcd7seg _ascii_high(
        .bcd(ascii_out[7:4]),
        .seg(seg3)
    );
    bcd7seg _ascii_low(
        .bcd(ascii_out[3:0]),
        .seg(seg2)
    );
    reg [31:0] remain_ticks;
    always@(posedge clk or posedge rst)begin
        //$display("remain_ticks=%d",remain_ticks);
        if(rst)remain_ticks<=0;
        else if(ps2_ready)begin
            remain_ticks<=32'h003f_ffff;
            seg0<=seglow;
            seg1<=seghigh;
            if(ps2_ready)$display("ps2_out=%h %h",ps2_out[7:4],ps2_out[3:0]);
        end
        else if(remain_ticks>0)remain_ticks<=remain_ticks-1;
        else begin
            seg0<=8'hff;
            seg1<=8'hff;
        end
    end
    assign ledr[3]=idle;
endmodule
