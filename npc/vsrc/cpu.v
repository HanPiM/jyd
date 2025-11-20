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

module branch_jmp_judger(
    input `WORD_RANGE src1,src2,
    input [2:0] func3t,
    output take_branch
);
    wire cond_eq= (src1==src2);
    wire cond_slt= ($signed(src1)<$signed(src2));
    wire cond_ult= (src1<src2);

    wire flag_rev=func3t[0];
    wire flag_usign=func3t[1];
    wire flag_cmp=func3t[2];

    wire cond_calc_tmp = flag_cmp ?
        (flag_usign ? cond_ult : cond_slt) : cond_eq;
    assign take_branch=flag_rev ^ cond_calc_tmp;
endmodule

// only jal is TypeJ
module itype_decoder(
    input [6:0] opcode,
    output [3:0] itype,

    output is_arithmetic,
    output is_load,
    output is_jalr,

    output is_lui,
    output is_auipc,

    output is_system
);

wire [4:0] opcu=opcode[6:2];

// opcode[1:0] should always be 11 for 32bit
wire is_invalid=~&opcode[1:0];

assign is_system=(opcu==5'b11100);

assign is_arithmetic=(opcu==5'b00100);
assign is_jalr=(opcu==5'b11001);
assign is_load=(opcu==5'b00000);
assign is_lui=(opcu==5'b01101);
assign is_auipc=(opcu==5'b00101);

wire is_jal=(opcu==5'b11011);

wire isI=is_arithmetic|is_jalr|is_load|is_system;
wire isR=(opcu==5'b01100);
wire isS=(opcu==5'b01000);
wire isU=is_lui|is_auipc;
wire isJ=is_jal;
wire isB=(opcu==5'b11000);

assign itype=
    is_invalid?TypeN:
    isI?TypeI:
    isR?TypeR:
    isS?TypeS:
    isU?TypeU:
    isJ?TypeJ:
    isB?TypeB:
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
    output is_auipc,
    output is_system
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
        .is_auipc(is_auipc),
        .is_system(is_system)
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
                $display("(decode) UNEXPECTED Unknown inst Type %d",itype);
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
                else if(func7t==7'b0100000)res=src1-src2;
                else begin
                    res=BADCALL_RESVALUE;
                    if(en)$display("(alu) UNKNOWN func7t %d",func7t);
                end
            end
        end
        3'b011:begin // sltu/sltui
            res=(src1<src2)?1:0;
        end
        3'b010:begin // slt/slti
            res=($signed(src1)<$signed(src2))?1:0;
        end
        3'b100:begin
            res=src1^src2;
        end
        3'b001:begin
            // slli/sll
            res=src1<<src2[4:0];
        end
        3'b101:begin
            // srai/sra
            if(func7t==7'b0100000)res=$signed(src1)>>>src2[4:0];
            else res=src1>>src2[4:0]; // srli/srl
        end
        3'b110:begin
            res=src1|src2;
        end
        3'b111:begin
            res=src1&src2;
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


