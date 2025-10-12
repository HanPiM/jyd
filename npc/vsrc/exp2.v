module priority_enc83(
    input [7:0] in,
    input en,
    output reg [2:0] out,
    output has_value
);

    always @(*) begin
        if (!en) begin
            out = 3'bxxx; // output undefined when not enabled
        end
        else begin
            casez (in)
                8'b1???????: out = 3'b111; // highest priority
                8'b01??????: out = 3'b110;
                8'b001?????: out = 3'b101;
                8'b0001????: out = 3'b100;
                8'b00001???: out = 3'b011;
                8'b000001??: out = 3'b010;
                8'b0000001?: out = 3'b001;
                8'b00000001: out = 3'b000; // lowest priority
                8'b00000000: out = 3'bxxx;
            endcase
        end
    end
    assign has_value = en & (|in);
endmodule

module bcd7seg(
    input [3:0] bcd,
    output reg [7:0] seg
);
    always @(*) begin
        case (bcd)
            default: seg = 8'b11111111; // off
            0: seg = 8'b0000_0011;
            1: seg = 8'b1001_1111;
            2: seg = 8'b0010_0101;
            3: seg = 8'b0000_1101;
            4: seg = 8'b1001_1001;
            5: seg = 8'b0100_1001;
            6: seg = 8'b0100_0001;
            7: seg = 8'b0001_1111;
            8: seg = 8'b0000_0001;
            9: seg = 8'b0000_1001;
            10: seg = 8'b0001_0001; // A
            11: seg = 8'b1100_0001; // b
            12: seg = 8'b0110_0011; // C
            13: seg = 8'b1000_0101; // d
            14: seg = 8'b0110_0001; // E
            15: seg = 8'b0111_0001; // F
        endcase
    end
endmodule

// 输入使用拨动开关SW7-SW0。使能端用SW8。输出为LED2-0，输出指示是LED4，数码管输出为Reg0。
module exp2(
    input [7:0] in,
    input en,
    output has_value,
    output [2:0] out,
    output [7:0] seg_out
);
    bcd7seg seg1 (
        .bcd({1'b0, out}),
        .seg(seg_out)
    );
    priority_enc83 pe (
        .in(in),
        .en(en),
        .out(out),
        .has_value(has_value)
    );
endmodule

