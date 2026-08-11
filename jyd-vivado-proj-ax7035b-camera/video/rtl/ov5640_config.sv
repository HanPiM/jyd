`timescale 1ns/1ps
// Programs the OV5640 with the 800x480@30 RGB565 table copied from the
// uploaded ACM5640-V5 manual. The table itself is in ov5640_reg_rom.sv.
module ov5640_config #(
    parameter integer CLK_HZ = 50_000_000,
    parameter integer REG_COUNT = 252
) (
    input  wire clk,
    input  wire rst,
    input  wire enable,
    input  wire sda_in,

    output wire scl_drive_low,
    output wire sda_drive_low,
    output reg  busy,
    output reg  done,
    output reg  error,
    output reg [7:0] current_index
);
    localparam integer POWER_WAIT_CYCLES = CLK_HZ / 50;     // 20 ms
    localparam integer RESET_WAIT_CYCLES = CLK_HZ / 100;    // 10 ms after 0x3008=0x82
    localparam integer GAP_CYCLES        = CLK_HZ / 50_000; // 20 us
    localparam integer WAIT_W = $clog2(POWER_WAIT_CYCLES + 1);

    localparam [3:0]
        ST_DISABLED = 4'd0,
        ST_POWER_WAIT = 4'd1,
        ST_ISSUE = 4'd2,
        ST_WAIT_TX = 4'd3,
        ST_GAP = 4'd4,
        ST_DONE = 4'd5;

    reg [3:0] state = ST_DISABLED;
    reg [WAIT_W-1:0] wait_count = 0;
    wire [23:0] reg_word;
    reg sccb_start = 1'b0;
    wire sccb_busy;
    wire sccb_done;
    wire sccb_ack_error;

    ov5640_reg_rom u_rom (
        .index(current_index),
        .reg_word(reg_word)
    );

    sccb_write #(
        .CLK_HZ(CLK_HZ),
        .SCL_HZ(100_000)
    ) u_sccb (
        .clk(clk),
        .rst(rst),
        .start(sccb_start),
        .reg_addr(reg_word[23:8]),
        .reg_data(reg_word[7:0]),
        .sda_in(sda_in),
        .busy(sccb_busy),
        .done(sccb_done),
        .ack_error(sccb_ack_error),
        .scl_drive_low(scl_drive_low),
        .sda_drive_low(sda_drive_low)
    );

    always @(posedge clk) begin
        if (rst) begin
            state         <= ST_DISABLED;
            wait_count    <= 0;
            sccb_start    <= 1'b0;
            busy          <= 1'b0;
            done          <= 1'b0;
            error         <= 1'b0;
            current_index <= 8'd0;
        end else begin
            sccb_start <= 1'b0;

            case (state)
                ST_DISABLED: begin
                    busy <= 1'b0;
                    done <= 1'b0;
                    wait_count <= 0;
                    current_index <= 8'd0;
                    if (enable) begin
                        busy <= 1'b1;
                        error <= 1'b0;
                        state <= ST_POWER_WAIT;
                    end
                end

                ST_POWER_WAIT: begin
                    busy <= 1'b1;
                    if (!enable) begin
                        state <= ST_DISABLED;
                    end else if (wait_count >= POWER_WAIT_CYCLES-1) begin
                        wait_count <= 0;
                        state <= ST_ISSUE;
                    end else begin
                        wait_count <= wait_count + 1'b1;
                    end
                end

                ST_ISSUE: begin
                    if (!sccb_busy) begin
                        sccb_start <= 1'b1;
                        state <= ST_WAIT_TX;
                    end
                end

                ST_WAIT_TX: begin
                    if (sccb_done) begin
                        if (sccb_ack_error)
                            error <= 1'b1;

                        if (current_index == REG_COUNT-1) begin
                            state <= ST_DONE;
                        end else begin
                            current_index <= current_index + 1'b1;
                            wait_count <= 0;
                            state <= ST_GAP;
                        end
                    end
                end

                ST_GAP: begin
                    // Entry #1 is the sensor software reset. Give it a long
                    // recovery period before writing the next register.
                    if ((current_index == 8'd2 && wait_count >= RESET_WAIT_CYCLES-1) ||
                        (current_index != 8'd2 && wait_count >= GAP_CYCLES-1)) begin
                        wait_count <= 0;
                        state <= ST_ISSUE;
                    end else begin
                        wait_count <= wait_count + 1'b1;
                    end
                end

                ST_DONE: begin
                    busy <= 1'b0;
                    done <= 1'b1;
                    if (!enable)
                        state <= ST_DISABLED;
                end

                default: state <= ST_DISABLED;
            endcase
        end
    end
endmodule

