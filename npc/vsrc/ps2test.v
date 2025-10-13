module ps2test(
    input clk,
    input d,
    output reg [7:0] data,
    output ready
);
    _my_hdl_sol fsm(
        .clk(clk),
        .in(d),
        .reset(1'b0),
        .out_byte(data),
        .done(ready)
    );
endmodule

// I had finished it on HDLbits
// https://hdlbits.01xz.net/wiki/Fsm_serialdata

module _my_hdl_sol(
    input clk,
    input in,
    input reset,    // Synchronous reset
    output reg [7:0] out_byte,
    output done
); //

    // Use FSM from Fsm_serial

    // New: Datapath to latch input bits.

        parameter BEG=0,B1=1,END=9,D=10,INV=11;
    reg [3:0] s,ns;
    always@(*)begin
        case(s)
            BEG:ns=in?BEG:B1;
            END:ns=in?D:INV;
            INV:ns=in?BEG:INV;
            D:ns=in?BEG:B1;
            default:ns=s+1;
        endcase
    end

    always@(posedge clk)begin
        if(reset)s<=BEG;
        else begin
            s<=ns;
            if(1<=s&&s<=8)out_byte<={in,out_byte[7:1]};
        end
    end
    assign done=(s==D);

endmodule


