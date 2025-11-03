`define WORD_RANGE [WORD_BITWIDTH-1:0]

parameter int WORD_BITWIDTH=32;
parameter int REG_ADDRWIDTH=5;

parameter int INST_EBREAK=32'h00100073;


localparam TypeN=0,TypeR=1,TypeI=2,TypeS=3,TypeU=4,TypeJ=5,TypeB=6;

module cpu(
    input `WORD_RANGE pc,
    output `WORD_RANGE nxt_pc
);
endmodule

module itype_decoder(
    input [6:0] opcode,
    output [3:0] itype,

    output is_arithmetic,
    output is_load,
    output is_jalr,

    output is_lui,
    output is_auipc
);

wire [4:0] opcu=opcode[6:2];

// opcode[1:0] should always be 11 for 32bit
wire is_invalid=~&opcode[1:0];

assign is_arithmetic=(opcu==5'b00100);
assign is_jalr=(opcu==5'b11001);
assign is_load=(opcu==5'b00000);
assign is_lui=(opcu==5'b01101);
assign is_auipc=(opcu==5'b00101);

wire isI=is_arithmetic|is_jalr|is_load;
wire isR=(opcu==5'b01100);
wire isS=(opcu==5'b01000);
wire isU=is_lui|is_auipc;

assign itype=
    is_invalid?TypeN:
    isI?TypeI:
    isR?TypeR:
    isS?TypeS:
    isU?TypeU:
    TypeN;

endmodule

module decode_operand(
    input `WORD_RANGE inst,
    output [3:0] itype,
    output reg `WORD_RANGE imm,
    output [REG_ADDRWIDTH-1:0] rd,rs1,rs2,

    output is_arithmetic,
    output is_load,
    output is_jalr,

    output is_lui,
    output is_auipc
);

    wire [6:0] opcode=inst[6:0];

    assign rd=inst[11:7];
    assign rs1=inst[19:15];
    assign rs2=inst[24:20];

    itype_decoder _idc(.opcode(opcode),.itype(itype),
        .is_jalr(is_jalr),
        .is_arithmetic(is_arithmetic),
        .is_load(is_load),
        .is_lui(is_lui),
        .is_auipc(is_auipc)
    );

    wire [31:0] immI={{21{inst[31]}},inst[30:20]};
    wire [31:0] immS={immI[31:5],inst[11:8],inst[7]};
    wire [31:0] immB={immI[31:12],inst[7],immS[10:1],1'b0};
    wire [31:0] immU={inst[31:12],12'b0};
    wire [31:0] immJ={immI[31:20],inst[19:12],inst[20],inst[30:21],1'b0};

    always@(*)begin
        case(itype)
            TypeI:imm=immI;
            TypeJ:imm=immJ;
            TypeS:imm=immS;
            TypeB:imm=immB;
            TypeU:imm=immU;
            TypeR:;
            TypeN:;
            default:begin
                $display("(decode) UNEXPECTED UNKNOWN inst Type %d",itype);
                imm=32'hBAADF00D;
            end
        endcase
    end

endmodule

parameter int BADCALL_RESVALUE=32'hBAADCA11;

module alu(
    input en, // now only effect display
    input is_imm, // in imm mode unuse func7t
    input [2:0] func3t,
    input [6:0] func7t,
    input `WORD_RANGE src1,src2,
    output reg `WORD_RANGE res
);

always@(*)begin
    case(func3t)
        3'b000:begin
            if(is_imm)res=src1+src2;
            else begin
                if(func7t==7'b0)res=src1+src2;
                else begin
                    res=BADCALL_RESVALUE;
                    if(en)$display("(alu) UNKNOWN func7t %d",func7t);
                end
            end
        end
        default:begin
            res=BADCALL_RESVALUE;
            if(en)$display("(alu) UNKNOWN func3t %d",func3t);
        end
    endcase

    `ifdef DISPLAY_TRACE
        $display("(alu) src1:%08X src2:%08X -> res=%08X",src1,src2,res);
    `endif
end

endmodule


