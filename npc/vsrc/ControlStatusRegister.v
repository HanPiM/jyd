parameter
    MCYCLE_ADDR = 12'hB00,
    MCYCLEH_ADDR= 12'hB80;

module ControlStatusRegister(
    input clk,
    input [11:0] addr,
    input [31:0] wdata,
    input wen,ren,
    output reg [31:0] rdata
);
    reg [31:0] rf[5];
    reg [63:0] mcycle;

    reg [2:0] idx;
    always@(*) begin case(addr)
        default:        idx = 3'd4; // unused
    endcase end

    always@(*) if(ren) begin
        case(addr)
            MCYCLE_ADDR:    rdata = mcycle[31:0];
            MCYCLEH_ADDR:   rdata = mcycle[63:32];
            default:        rdata = 32'd0;
        endcase
    end else begin
        rdata = 32'd0;
    end

    always@(posedge clk) begin
        mcycle <= mcycle + 1;

    end
endmodule
