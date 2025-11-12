import "DPI-C" function void raise_break(input int a0);
// always read addr & ~0x3u
import "DPI-C" function int pmem_read(input int raddr);
// always Write addr & ~0x3u
import "DPI-C" function void pmem_write(
  input int waddr, input int wdata, input byte wmask);

parameter int INIT_PC=32'h8000_0000;

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

    output reg [WORD_BITWIDTH-1:0] pc
);

initial begin
    pc=INIT_PC;
end

    wire [WORD_BITWIDTH-1:0] inst=pmem_read(pc);

    wire wen;
    wire [3:0] itype;
    wire [WORD_BITWIDTH-1:0] imm,src1,src2,alu_s1,alu_s2,alu_res,a0;
    reg [WORD_BITWIDTH-1:0] wdata,nxt_pc;
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
        .wen(wen),
        .dump_info(inst==INST_EBREAK)
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
    // pass pc(a always valid addr)
    // so
    // NOTICE!!! mem_read result only meaningful
    //           when is_load is true
    assign safe_maddr=is_load?s1pi_addr:pc;


    assign nxt_pc=is_jalr?(s1pi_addr&~1):(pc+4);
    assign wen=(itype!=TypeS)&&(itype!=TypeN);


    always@(*)begin
        if(inst==INST_EBREAK)begin
            $display("--> @[0x%08X]: ebreak",pc);
            raise_break(a0);
        end

        wdata=32'hCDCDCDCD;
        case(itype)
            TypeI:begin
                if(is_jalr)begin
                    wdata=pc+4;
                end else if(is_arithmetic)begin
                    wdata=alu_res;
                end else if(is_load)begin
                    //$display("Load data since inst=%08X",inst);
                    case(func3t)
                        // lbu zero ext
                        3'b100: wdata={24'b0,pmem_read(safe_maddr)[
                            s1pi_addr_unalign_part*8+:8
                        ]};
                        // lw
                        3'b010: wdata=pmem_read(safe_maddr);
                        default: begin
                            wdata=BADCALL_RESVALUE;
                            $display("(load) UNKNOWN func3t %d",func3t);
                        end
                    endcase
                end
            end
            TypeR:wdata=alu_res;
            TypeU:wdata=is_lui?imm:(imm+pc);
            TypeS:begin
                case(func3t)
                    3'b010: pmem_write(s1pi_addr,src2,8'b00001111);
                    3'b000: pmem_write(s1pi_addr,
                        src2<<(s1pi_addr_unalign_part*8),
                        8'b00000001<<s1pi_addr_unalign_part);
                    default:$display("(store) UNKNOWN func3t %d",func3t);
                endcase
            end
            default:;

        endcase
//        $display("pc %08X nxt_pc %08X",pc,nxt_pc);
    end

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
            pc<=INIT_PC;
        end else begin
            pc<=nxt_pc;
        end
    end

endmodule
