module ps2test(
    input clk,
    input ps2_clk,
    input d,
    output reg [7:0] data,
    output ready,
    output idle
);
    wire done;
    _my_hdl_sol fsm(
        .clk(ps2_clk),
        .in(d),
        .reset(1'b0),
        .out_byte(data),
        .done(done),
        .idle(idle)
    );

    reg [2:0] ps2_clk_sync;

    always @(posedge clk) begin
        ps2_clk_sync <=  {ps2_clk_sync[1:0],ps2_clk};
    end

    wire sampling = ps2_clk_sync[2] & ~ps2_clk_sync[1];

    always @(posedge clk) begin
        if (sampling) begin
            ready <= done;
            $display("sampling ready=%b done=%b",ready,done);
        end
        else begin
             ready <= 0;
//             $display("not sampling ready=%b",ready);
        end
    end
    /*
    reg last_psclk;
    reg [31:0] ready_mantain;
    reg first;
    always@(posedge clk) begin
        last_psclk<=ps2_clk;
        if(ps2_clk==last_psclk)begin
            if(ready_mantain>0)ready_mantain<=ready_mantain-1;
            else if(~first)ready<=0;
            else begin
                ready<=0;
                first<=1;
            end
        end else begin
            ready<=done;
            ready_mantain<=32'h002F_FFFF;
        end
    end
    */
endmodule

// I had finished it on HDLbits
// https://hdlbits.01xz.net/wiki/Fsm_serialdata

module parity (
    input clk,
    input reset,
    input in,
    output reg odd);

    always @(posedge clk)
        if (reset) odd <= 0;
        else if (in) odd <= ~odd;

endmodule

module _my_hdl_sol(
    input clk,
    input in,
    input reset,    // Synchronous reset
    output reg [7:0] out_byte,
    output done,
    output idle
);

    parameter BEG=0,B1=1,B8=8,BP=9,END=10,D=11,INV=12;

    reg [3:0] s,ns;
    reg bytep;

    reg ppreset;
    reg ppout;
    parity pp(clk,ppreset,in,ppout);

    always@(*)begin
        case(s)
            BEG:ns=in?BEG:B1;
            END:ns=in?D:INV;
            INV:ns=in?BEG:INV;
            D:ns=in?BEG:B1;
            default:ns=s+1;
        endcase
    end
    reg [15:0] cnt;

    always@(posedge clk)begin
        if(reset)begin
            s<=BEG;
            ppreset<=1;
        end
        else begin
            s<=ns;
            if(B1<=s&&s<=B8)out_byte<={in,out_byte[7:1]};
            if(s==BEG)ppreset<=1;
            else ppreset<=0;
            if(s==BP)bytep<=in;
        end
        //if(s!=0) $display("state=%d ns=%d",s,ns);
    end
    assign done=(s==D)&((^out_byte)^bytep);
    assign idle=(s==BEG)|(s==INV);
endmodule
