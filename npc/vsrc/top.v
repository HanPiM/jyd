import "DPI-C" function void raise_break(input int a0);
import "DPI-C" function void sim_panic();

// always read addr & ~0x3u
import "DPI-C" function int pmem_read(input int raddr);
// always Write addr & ~0x3u
import "DPI-C" function void pmem_write(
  input int waddr, input int wdata, input byte wmask);

import "DPI-C" function int fetch_inst(input int pc);

`define WORD_RANGE [WORD_BITWIDTH-1:0]

parameter int INIT_PC=32'h8000_0000;
parameter int PC_BEFORE_START=INIT_PC-4;

parameter int NOP_INST_ADDR = PC_BEFORE_START;


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
    output [7:0] seg7,

    output reg `WORD_RANGE pc,
    output reg [WORD_BITWIDTH-1:0] nxt_pc
);

    wire [WORD_BITWIDTH-1:0] inst=fetch_inst(pc);

    wire wen;
    wire [3:0] itype;
    wire [WORD_BITWIDTH-1:0] imm,src1,src2,alu_s1,alu_s2,alu_res,a0;
    reg [WORD_BITWIDTH-1:0] wdata;
    wire [REG_ADDRWIDTH-1:0] rd,rs1,rs2;

    wire is_arithmetic, is_load, is_jalr;
    wire is_lui,is_auipc;

    RegisterFile #(
        .ADDR_WIDTH(REG_ADDRWIDTH),
        .DATA_WIDTH(WORD_BITWIDTH)
    ) regs(
        .clk(clk),
        .waddr(rd),
        .wdata(wdata),
        .raddr1(rs1),.raddr2(rs2),
        .rdata1(src1),.rdata2(src2),
        .a0(a0),
        .wen(wen)
    );

    decode_operand dec_opr(
        .inst(inst),
        .itype(itype),
        .imm(imm),
        .rd(rd),
        .rs1(rs1),
        .rs2(rs2),

        .is_jalr(is_jalr),
        .is_arithmetic(is_arithmetic),
        .is_load(is_load),
        .is_lui(is_lui),
        .is_auipc(is_auipc)
    );

    wire [6:0] opcode=inst[6:0];
    wire [2:0] func3t=inst[14:12];
    wire [6:0] func7t=inst[31:25];

    // use alu only when TypeR/TypeI
    assign alu_s1=src1;
    assign alu_s2=(itype==TypeR)?src2:imm;
    wire is_imm;
    assign is_imm=(itype==TypeI);

    alu _alu(
        .en(is_arithmetic),
        .is_imm(is_imm),
        .func3t(func3t),.func7t(func7t),
        .src1(alu_s1),.src2(alu_s2),.res(alu_res)
    );

    // src1+imm
    wire `WORD_RANGE s1pi_addr,safe_maddr;
    wire [1:0] s1pi_addr_unalign_part;
    assign s1pi_addr=src1+imm;
    assign s1pi_addr_unalign_part=s1pi_addr[1:0];

    // pmem_read is always called
    // so for non-load instructions
    // use MAGIC_ADDR_IGNORE to tell pmem_read to ignore
    assign safe_maddr=is_load?s1pi_addr:NOP_INST_ADDR;

    wire take_branch;
    branch_jmp_judger _brjmp(
        .func3t(func3t),
        .src1(src1),.src2(src2),
        .take_branch(take_branch)
    );

    // nxt_pc
    always@(*) begin
        if(is_jalr)nxt_pc=(s1pi_addr&~1);
        else if(itype==TypeJ)nxt_pc=pc+imm;
        else if(itype==TypeB)begin
            if(take_branch) nxt_pc=pc+imm;
            else nxt_pc=pc+4;
        end else nxt_pc=pc+4;
    end


    assign wen=(itype!=TypeS)&&(itype!=TypeN)&&(itype!=TypeB);

    reg `WORD_RANGE mem_rdata;
//    always@(safe_maddr)begin
//        $display("Memory access at addr %08X",safe_maddr);
//    end

    // wdata
    always@(*) begin
        if(inst==INST_EBREAK)begin
            raise_break(a0);
        end
       // $display("Decode inst %08X @ %08X",inst,pc);

        wdata=32'hCDCDCDCD;
        case(itype)
            TypeI:begin
                if(is_jalr)begin
                    wdata=pc+4;
                end else if(is_arithmetic)begin
                    wdata=alu_res;
                end else if(is_load)begin
                   // $display("Load data since inst=%08X",inst);
                    mem_rdata=pmem_read(safe_maddr);

                    case(func3t)
                        // lbu zero ext
                        3'b100: wdata={24'b0,mem_rdata[
                            s1pi_addr_unalign_part*8+:8
                        ]};
                        // lb sign ext
                        3'b000: wdata={{24{mem_rdata[
                            s1pi_addr_unalign_part*8+7]}},
                            mem_rdata[s1pi_addr_unalign_part*8+:8]};
                        // lhu zero ext
                        3'b101: wdata={16'b0,mem_rdata[
                            s1pi_addr_unalign_part*8+:16
                        ]};
                        // lh sign ext
                        3'b001: wdata={{16{mem_rdata[
                            s1pi_addr_unalign_part*8+15]}},
                            mem_rdata[s1pi_addr_unalign_part*8+:16]};
                        // lw
                        3'b010: wdata=mem_rdata;
                        default: begin
                            wdata=BADCALL_RESVALUE;
                            $display("(load) UNKNOWN func3t %d",func3t);
                            sim_panic();
                        end
                    endcase
                end
            end
            TypeR:wdata=alu_res;
            TypeU:wdata=is_lui?imm:(imm+pc);
            TypeS:begin
                case(func3t)
                    3'b010: pmem_write(s1pi_addr,src2,8'b00001111); // sw
                    3'b001: pmem_write(s1pi_addr,
                        src2<<(s1pi_addr_unalign_part*8),
                        8'b00000011<<s1pi_addr_unalign_part); // sh
                    3'b000: pmem_write(s1pi_addr,
                        src2<<(s1pi_addr_unalign_part*8),
                        8'b00000001<<s1pi_addr_unalign_part); // sb
                    default:begin
                        $display("(store) UNKNOWN func3t %d",func3t);
                        sim_panic();
                    end
                endcase
            end
            TypeJ:begin
                // jal
                wdata=pc+4;
            end
            TypeB:; // no wirite
            default:$display("(top) UNKNOWN itype %d",itype);

        endcase
        //$display("pc %08X nxt_pc %08X",pc,nxt_pc);
    end

//    `define DISPLAY_TRACE

    always@(posedge clk,posedge rst)begin

    `ifdef DISPLAY_TRACE
        $display("--> @pc [%08x:] inst %08X",pc,inst);
        $display("rs1(r%d)=%08X(%d) rs2(r%d)=%08X(%d) imm=%08X(%d)",
            rs1,src1,src1,
            rs2,src2,src2,
            imm,imm);
        if(is_jalr)$display("JALR %08X",nxt_pc);
        if(is_arithmetic)$display("Write arithemtic result");
        if(is_load)$display("Load");
        if(is_lui)$display("LUI");

        if(wen)$display("update r%d <- %08X(%d)",rd,wdata,wdata);
    `endif

        if(rst)begin
            pc<=PC_BEFORE_START;
        end else begin
            pc<=nxt_pc;
        end
    end

endmodule
