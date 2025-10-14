module scpu(
    input [7:0] code,
    input [3:0] pc,
    output [3:0] nxt_pc,
    output [7:0] out,

    input [7:0] rin[4],
    output reg [7:0] rout[4]
);
    parameter ADD=2'b00,LI=2'b10,BNER0=2'b11;
    parameter OUT=2'b01;

    wire [1:0] op = code[7:6];
    wire [1:0] rd = code[5:4];
    wire [1:0] rs1 = code[3:2];
    wire [1:0] rs2 = code[1:0];
    wire [7:0] imm = {4'b0, code[3:0]};
    wire [3:0] addr = code[5:2];

    always @(*) begin
        rout[0] = rin[0];
        rout[1] = rin[1];
        rout[2] = rin[2];
        rout[3] = rin[3];
        case (op)
            ADD: rout[rd] = rin[rs1] + rin[rs2];
            LI: rout[rd] = imm;
            default: ;
        endcase
    end
    assign nxt_pc = (op==BNER0 && rin[rs2]!=rin[0]) ? addr : (pc + 1);

    assign out = (op==OUT) ? rin[rd] : 8'b0;
endmodule
