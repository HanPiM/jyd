`timescale 1ns/1ps

module ax7035b_seg_scan #(
    parameter integer CLK_HZ = 50_000_000
) (
    input  wire        clk,
    input  wire        reset,
    input  wire [31:0] bcd_value_cpu,
    output reg  [7:0]  seg_digit,
    output reg  [5:0]  seg_select
);
    localparam integer DIGIT_CYCLES = CLK_HZ / 1000;
    reg [31:0] bcd_sync1;
    reg [31:0] bcd_sync2;
    reg [31:0] scan_count;
    reg [2:0]  scan_index;
    reg [3:0]  digit;
    reg        blank;

    function automatic [7:0] encode;
        input [3:0] value;
        begin
            case (value)
                4'd0: encode=8'b1100_0000; 4'd1: encode=8'b1111_1001;
                4'd2: encode=8'b1010_0100; 4'd3: encode=8'b1011_0000;
                4'd4: encode=8'b1001_1001; 4'd5: encode=8'b1001_0010;
                4'd6: encode=8'b1000_0010; 4'd7: encode=8'b1111_1000;
                4'd8: encode=8'b1000_0000; 4'd9: encode=8'b1001_0000;
                default: encode=8'b1111_1111;
            endcase
        end
    endfunction

    always @(posedge clk) begin
        if (reset) begin
            bcd_sync1 <= 0;
            bcd_sync2 <= 0;
            scan_count <= 0;
            scan_index <= 0;
        end else begin
            bcd_sync1 <= bcd_value_cpu;
            bcd_sync2 <= bcd_sync1;
            if (scan_count == DIGIT_CYCLES - 1) begin
                scan_count <= 0;
                scan_index <= (scan_index == 5) ? 0 : scan_index + 1'b1;
            end else begin
                scan_count <= scan_count + 1'b1;
            end
        end
    end

    always @* begin
        case (scan_index)
            0: begin digit=bcd_sync2[23:20]; blank=(bcd_sync2[23:20] == 0); seg_select=6'b011111; end
            1: begin digit=bcd_sync2[19:16]; blank=(bcd_sync2[23:16] == 0); seg_select=6'b101111; end
            2: begin digit=bcd_sync2[15:12]; blank=(bcd_sync2[23:12] == 0); seg_select=6'b110111; end
            3: begin digit=bcd_sync2[11:8];  blank=(bcd_sync2[23:8]  == 0); seg_select=6'b111011; end
            4: begin digit=bcd_sync2[7:4];   blank=(bcd_sync2[23:4]  == 0); seg_select=6'b111101; end
            default: begin digit=bcd_sync2[3:0]; blank=1'b0; seg_select=6'b111110; end
        endcase
        seg_digit = blank ? 8'hff : encode(digit);
    end
endmodule
