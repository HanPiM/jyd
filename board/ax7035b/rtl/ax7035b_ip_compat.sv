`timescale 1ns/1ps

// Board-local, inference-based replacements for the contest project's memory
// and multiplier IP.  Module names and cycle latencies match JYDFPGATop.
module blk_mem_gen_irom (
    input wire clka, input wire ena, input wire [17:0] addra,
    output reg [31:0] douta
);
    (* rom_style = "block" *) reg [31:0] storage [0:8191];
    integer i;
    initial begin
        for (i=0; i<8192; i=i+1) storage[i]=0;
        $readmemh("irom.mem", storage);
    end
    always @(posedge clka) if (ena) douta <= storage[addra[12:0]];
    wire unused_addra = |addra[17:13];
endmodule

module blk_mem_gen_dram (
    input wire clka, input wire ena, input wire [3:0] wea,
    input wire [17:0] addra, input wire [31:0] dina,
    output reg [31:0] douta
);
    (* ram_style = "block" *) reg [31:0] storage [0:16383];
    reg [31:0] memory_output;
    integer i;
    initial begin
        for (i=0; i<16384; i=i+1) storage[i]=0;
        $readmemh("dram.mem", storage);
    end
    always @(posedge clka) if (ena) begin
        memory_output <= storage[addra[13:0]];
        douta <= memory_output;
        if (wea[0]) storage[addra[13:0]][7:0]   <= dina[7:0];
        if (wea[1]) storage[addra[13:0]][15:8]  <= dina[15:8];
        if (wea[2]) storage[addra[13:0]][23:16] <= dina[23:16];
        if (wea[3]) storage[addra[13:0]][31:24] <= dina[31:24];
    end
    wire unused_addra = |addra[17:14];
endmodule

module blk_mem_gen_2KB (
    input wire clka, input wire ena, input wire [3:0] wea,
    input wire [8:0] addra, input wire [31:0] dina,
    input wire clkb, input wire enb, input wire [8:0] addrb,
    output reg [31:0] doutb
);
    (* ram_style = "block" *) reg [31:0] storage [0:511];
    always @(posedge clka) if (ena) begin
        if (wea[0]) storage[addra][7:0]   <= dina[7:0];
        if (wea[1]) storage[addra][15:8]  <= dina[15:8];
        if (wea[2]) storage[addra][23:16] <= dina[23:16];
        if (wea[3]) storage[addra][31:24] <= dina[31:24];
    end
    always @(posedge clkb) if (enb) doutb <= storage[addrb];
endmodule

module dist_mem_gen_512x8 (
    input wire [8:0] a, input wire [7:0] d, input wire [8:0] dpra,
    input wire clk, input wire we, output wire [7:0] dpo
);
    (* ram_style = "distributed" *) reg [7:0] storage [0:511];
    always @(posedge clk) if (we) storage[a] <= d;
    assign dpo = storage[dpra];
endmodule

module dist_mem_gen_32x32 (
    input wire [5:0] a, input wire [31:0] d, input wire [5:0] dpra,
    input wire clk, input wire we, output wire [31:0] dpo
);
    (* ram_style = "distributed" *) reg [31:0] storage [0:63];
    always @(posedge clk) if (we) storage[a] <= d;
    assign dpo = storage[dpra];
endmodule

module mult_gen_0 (
    input wire CLK, input wire signed [32:0] A, input wire signed [32:0] B,
    output wire signed [65:0] P
);
    reg signed [65:0] pipe0, pipe1, pipe2, pipe3;
    always @(posedge CLK) begin
        pipe0 <= A * B;
        pipe1 <= pipe0;
        pipe2 <= pipe1;
        pipe3 <= pipe2;
    end
    assign P = pipe3;
endmodule

module mult_gen_mul32_fast (
    input wire CLK, input wire [31:0] A, input wire [31:0] B,
    output wire [31:0] P
);
    reg [31:0] pipe0, pipe1, pipe2;
    always @(posedge CLK) begin
        pipe0 <= A * B;
        pipe1 <= pipe0;
        pipe2 <= pipe1;
    end
    assign P = pipe2;
endmodule

module mult_gen_mul16_fast (
    input wire CLK, input wire [15:0] A, input wire [15:0] B,
    output reg [31:0] P
);
    always @(posedge CLK) P <= A * B;
endmodule
