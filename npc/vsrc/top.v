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
initial begin
    {seg0,seg1,seg2,seg3,seg4,seg5,seg6,seg7}=64'hff_ff_ff_ff_ff_ff_ff_ff;
end
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
    wire [7:0] ascii_seglow, ascii_seghigh;
    bcd7seg _ascii_high(
        .bcd(ascii_out[7:4]),
        .seg(ascii_seghigh)
    );
    bcd7seg _ascii_low(
        .bcd(ascii_out[3:0]),
        .seg(ascii_seglow)
    );

    reg [7:0] hit_count;

    bcd7seg _hitlow(
        .bcd(hit_count[3:0]),
        .seg(seg4)
    );
    bcd7seg _hithigh(
        .bcd(hit_count[7:4]),
        .seg(seg5)
    );

    reg is_released,wait_code_after_f0;
    reg [31:0] remain_ticks;
    always@(posedge clk or posedge rst)begin
        //$display("remain_ticks=%d",remain_ticks);
        if(ps2_ready)begin
            //$display("ps2_ready key %h",ps2_out);
            {seg0, seg1} <= {seglow, seghigh};
            {seg2, seg3} <= {ascii_seglow, ascii_seghigh};
            remain_ticks <= 32'h000f_ffff;
            if(ps2_out==8'hf0) begin
                is_released<=1;
                wait_code_after_f0<=1;
                hit_count <= hit_count + 1;
            end else begin
                if(wait_code_after_f0) wait_code_after_f0<=0;
                else is_released<=0;
            end
        end
        else if(remain_ticks>0) begin
            remain_ticks <= remain_ticks - 1;
            if(remain_ticks==1&&is_released) begin
                {seg0, seg1} <= 16'hff_ff;
                {seg2, seg3} <= 16'hff_ff;
            end
        end
    end
    assign ledr[3]=idle;
endmodule
