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
    reg [7:0] rom[16];
    reg [7:0] r[4];
    initial begin
        // r[0] = 10 set as loop end condition
        // r[1] act as i
        // r[2] act as istep
        // r[3] act as sum
        rom[0] = 8'b10_00_1010; // LI r[0], 10
        rom[1] = 8'b10_01_0000; // LI r[1], 0
        rom[2] = 8'b10_10_0001; // LI r[2], 1
        rom[3] = 8'b00_01_01_10; // ADD r[1], r[1], r[2]
        rom[4] = 8'b00_11_11_01; // ADD r[3], r[3], r[1]
        rom[5] = 8'b11_0011_01; // JUMP 3 if r[1] != r[0]
        rom[6] = 8'b01_11_0000; // OUT r[3]
    end
    reg [3:0] pc;
    wire [7:0] code = rom[pc];
    wire [3:0] nxt_pc;
    wire [7:0] res;
    wire [7:0] nxt_r[4];
    scpu cpu(
        .code(code),
        .pc(pc),
        .nxt_pc(nxt_pc),
        .out(res),
        .rin(r),
        .rout(nxt_r)
    );
    always @(posedge clk) begin
        if (rst) pc <= 0;
        else begin 
            pc <= (pc == 6)?pc:nxt_pc;
            r[0] <= nxt_r[0];
            r[1] <= nxt_r[1];
            r[2] <= nxt_r[2];
            r[3] <= nxt_r[3];
            if(pc!=6) $display("pc=%d code=%b r0=%d r1=%d r2=%d r3=%d out=%d", pc, code, r[0], r[1], r[2], r[3], res);
        end
    end

    bcd7seg _low(res[3:0], seg0);
    bcd7seg _high(res[7:4], seg1);

endmodule
