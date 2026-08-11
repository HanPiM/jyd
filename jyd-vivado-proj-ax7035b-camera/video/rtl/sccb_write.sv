`timescale 1ns/1ps
// Minimal SCCB/I2C-compatible write-only master for OV5640 register writes.
// One transaction sends: 0x78, register[15:8], register[7:0], data[7:0].
// SDA is open-drain: this module only requests pulling SDA low.
module sccb_write #(
    parameter integer CLK_HZ = 50_000_000,
    parameter integer SCL_HZ = 100_000
) (
    input  wire        clk,
    input  wire        rst,
    input  wire        start,
    input  wire [15:0] reg_addr,
    input  wire [7:0]  reg_data,
    input  wire        sda_in,

    output reg         busy,
    output reg         done,
    output reg         ack_error,
    output reg         scl_drive_low,
    output reg         sda_drive_low
);
    localparam integer HALF_TICKS = CLK_HZ / (SCL_HZ * 2);
    localparam integer DIV_W = (HALF_TICKS <= 2) ? 1 : $clog2(HALF_TICKS);

    localparam [3:0]
        ST_IDLE          = 4'd0,
        ST_START         = 4'd1,
        ST_BIT_LOW       = 4'd2,
        ST_BIT_HIGH      = 4'd3,
        ST_ACK_LOW       = 4'd4,
        ST_ACK_HIGH      = 4'd5,
        ST_STOP_LOW      = 4'd6,
        ST_STOP_HIGH     = 4'd7,
        ST_STOP_RELEASE  = 4'd8;

    reg [3:0] state = ST_IDLE;
    reg [DIV_W-1:0] div_count = {DIV_W{1'b0}};
    reg [7:0] tx_byte = 8'h00;
    reg [2:0] bit_idx = 3'd7;
    reg [2:0] byte_idx = 3'd0;
    reg [15:0] addr_latched = 16'h0000;
    reg [7:0] data_latched = 8'h00;

    wire half_tick = (div_count == HALF_TICKS-1);

    function automatic [7:0] byte_for_index;
        input [2:0] idx;
        begin
            case (idx)
                3'd0: byte_for_index = 8'h78; // OV5640 SCCB write address (7-bit 0x3C)
                3'd1: byte_for_index = addr_latched[15:8];
                3'd2: byte_for_index = addr_latched[7:0];
                default: byte_for_index = data_latched;
            endcase
        end
    endfunction

    always @* begin
        // Both SCCB lines are treated as open-drain. The ACM5640-V5 module
        // provides pull-ups, so the FPGA only ever pulls a line low or releases it.
        scl_drive_low = 1'b0;
        sda_drive_low = 1'b0;
        case (state)
            ST_IDLE: begin
                scl_drive_low = 1'b0;
                sda_drive_low = 1'b0;
            end
            ST_START: begin
                // START: SDA falls while SCL remains released/high.
                scl_drive_low = 1'b0;
                sda_drive_low = 1'b1;
            end
            ST_BIT_LOW,
            ST_BIT_HIGH: begin
                scl_drive_low = (state == ST_BIT_LOW);
                // Open-drain: drive zero, release for one.
                sda_drive_low = ~tx_byte[bit_idx];
            end
            ST_ACK_LOW,
            ST_ACK_HIGH: begin
                scl_drive_low = (state == ST_ACK_LOW);
                sda_drive_low = 1'b0; // release for ACK
            end
            ST_STOP_LOW: begin
                scl_drive_low = 1'b1;
                sda_drive_low = 1'b1;
            end
            ST_STOP_HIGH: begin
                scl_drive_low = 1'b0;
                sda_drive_low = 1'b1;
            end
            ST_STOP_RELEASE: begin
                // STOP: SDA rises while SCL is released/high.
                scl_drive_low = 1'b0;
                sda_drive_low = 1'b0;
            end
            default: begin
                scl_drive_low = 1'b0;
                sda_drive_low = 1'b0;
            end
        endcase
    end

    always @(posedge clk) begin
        if (rst) begin
            state        <= ST_IDLE;
            div_count    <= {DIV_W{1'b0}};
            tx_byte      <= 8'h00;
            bit_idx      <= 3'd7;
            byte_idx     <= 3'd0;
            addr_latched <= 16'h0000;
            data_latched <= 8'h00;
            busy         <= 1'b0;
            done         <= 1'b0;
            ack_error    <= 1'b0;
        end else begin
            done <= 1'b0;

            if (state == ST_IDLE) begin
                div_count <= {DIV_W{1'b0}};
                if (start) begin
                    addr_latched <= reg_addr;
                    data_latched <= reg_data;
                    tx_byte      <= 8'h78;
                    bit_idx      <= 3'd7;
                    byte_idx     <= 3'd0;
                    busy         <= 1'b1;
                    ack_error    <= 1'b0;
                    state        <= ST_START;
                end
            end else begin
                if (half_tick)
                    div_count <= {DIV_W{1'b0}};
                else
                    div_count <= div_count + 1'b1;

                if (half_tick) begin
                    case (state)
                        ST_START: begin
                            state <= ST_BIT_LOW;
                        end
                        ST_BIT_LOW: begin
                            state <= ST_BIT_HIGH;
                        end
                        ST_BIT_HIGH: begin
                            if (bit_idx == 3'd0) begin
                                state <= ST_ACK_LOW;
                            end else begin
                                bit_idx <= bit_idx - 1'b1;
                                state <= ST_BIT_LOW;
                            end
                        end
                        ST_ACK_LOW: begin
                            state <= ST_ACK_HIGH;
                        end
                        ST_ACK_HIGH: begin
                            if (sda_in)
                                ack_error <= 1'b1;
                            if (byte_idx == 3'd3) begin
                                state <= ST_STOP_LOW;
                            end else begin
                                byte_idx <= byte_idx + 1'b1;
                                bit_idx  <= 3'd7;
                                tx_byte  <= byte_for_index(byte_idx + 1'b1);
                                state    <= ST_BIT_LOW;
                            end
                        end
                        ST_STOP_LOW: begin
                            state <= ST_STOP_HIGH;
                        end
                        ST_STOP_HIGH: begin
                            state <= ST_STOP_RELEASE;
                        end
                        ST_STOP_RELEASE: begin
                            state <= ST_IDLE;
                            busy  <= 1'b0;
                            done  <= 1'b1;
                        end
                        default: begin
                            state <= ST_IDLE;
                            busy  <= 1'b0;
                        end
                    endcase
                end
            end
        end
    end
endmodule

