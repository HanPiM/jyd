`timescale 1ns/1ps

// Adapted from the hardware-verified AX7035B AHT10 prototype.
// Protocol timing and state transitions are unchanged. The original inout
// ports are exposed as explicit input/drive-low signals so the board top can
// implement the only legal I2C output states: low and high impedance.
module jyd_aht10_i2c_master #(
    parameter integer CLK_HZ = 50_000_000,
    parameter integer I2C_HZ = 100_000
) (
    input  wire        clk,
    input  wire        rst_n,
    input  wire        start,
    input  wire [6:0]  address,
    input  wire        read_not_write,
    input  wire [23:0] tx_data,
    input  wire [1:0]  tx_len,
    input  wire [2:0]  rx_len,
    output reg  [47:0] rx_data,
    output reg         busy,
    output reg         done,
    output reg         nack,
    input  wire        scl_in,
    input  wire        sda_in,
    output wire        scl_drive_low,
    output wire        sda_drive_low
);
    // Each data/ACK bit uses three phases: setup-low, sample-high, hold-low.
    // 50 MHz/(100 kHz*3) truncates to 166 clocks/phase, or about 100.4 kHz.
    localparam integer QUARTER_CYCLES = CLK_HZ / (I2C_HZ * 3);

    localparam [4:0] ST_IDLE       = 5'd0,
                     ST_START_A    = 5'd1,
                     ST_START_B    = 5'd2,
                     ST_TX_SETUP   = 5'd3,
                     ST_TX_HIGH    = 5'd4,
                     ST_TX_LOW     = 5'd5,
                     ST_ACK_SETUP  = 5'd6,
                     ST_ACK_HIGH   = 5'd7,
                     ST_ACK_LOW    = 5'd8,
                     ST_RX_SETUP   = 5'd9,
                     ST_RX_HIGH    = 5'd10,
                     ST_RX_LOW     = 5'd11,
                     ST_MACK_SETUP = 5'd12,
                     ST_MACK_HIGH  = 5'd13,
                     ST_MACK_LOW   = 5'd14,
                     ST_STOP_A     = 5'd15,
                     ST_STOP_B     = 5'd16,
                     ST_STOP_C     = 5'd17;

    reg [4:0] state;
    reg [15:0] tick_count;
    reg scl_low;
    reg sda_low;
    reg [7:0] shift_byte;
    reg [2:0] bit_index;
    reg [1:0] tx_index;
    reg [2:0] rx_index;
    reg [23:0] tx_latched;
    reg [1:0] tx_len_latched;
    reg [2:0] rx_len_latched;
    reg rw_latched;
    reg address_phase;
    reg abort_after_ack;

    assign scl_drive_low = scl_low;
    assign sda_drive_low = sda_low;

    wire quarter_done = ({16'b0, tick_count} == QUARTER_CYCLES - 1);

    function [7:0] select_tx_byte;
        input [23:0] value;
        input [1:0] length;
        input [1:0] index;
        begin
            case (length)
                2'd1: select_tx_byte = value[7:0];
                2'd2: select_tx_byte = (index == 0) ? value[15:8] : value[7:0];
                default: begin
                    case (index)
                        0: select_tx_byte = value[23:16];
                        1: select_tx_byte = value[15:8];
                        default: select_tx_byte = value[7:0];
                    endcase
                end
            endcase
        end
    endfunction

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            state           <= ST_IDLE;
            tick_count      <= 0;
            scl_low         <= 0;
            sda_low         <= 0;
            shift_byte      <= 0;
            bit_index       <= 0;
            tx_index        <= 0;
            rx_index        <= 0;
            tx_latched      <= 0;
            tx_len_latched  <= 0;
            rx_len_latched  <= 0;
            rw_latched      <= 0;
            address_phase   <= 0;
            abort_after_ack <= 0;
            rx_data         <= 0;
            busy            <= 0;
            done            <= 0;
            nack            <= 0;
        end else begin
            done <= 1'b0;
            if (state == ST_IDLE || quarter_done)
                tick_count <= 0;
            else
                tick_count <= tick_count + 1'b1;

            case (state)
                ST_IDLE: begin
                    scl_low <= 1'b0;
                    sda_low <= 1'b0;
                    busy    <= 1'b0;
                    if (start) begin
                        tx_latched      <= tx_data;
                        tx_len_latched  <= tx_len;
                        rx_len_latched  <= rx_len;
                        rw_latched      <= read_not_write;
                        shift_byte      <= {address, read_not_write};
                        bit_index       <= 3'd7;
                        tx_index        <= 0;
                        rx_index        <= 0;
                        rx_data         <= 0;
                        nack            <= 0;
                        abort_after_ack <= 0;
                        address_phase   <= 1'b1;
                        busy            <= 1'b1;
                        state           <= ST_START_A;
                    end
                end

                ST_START_A: begin
                    scl_low <= 1'b0;
                    sda_low <= 1'b1;
                    if (quarter_done && scl_in)
                        state <= ST_START_B;
                end

                ST_START_B: begin
                    scl_low <= 1'b1;
                    sda_low <= 1'b1;
                    if (quarter_done)
                        state <= ST_TX_SETUP;
                end

                ST_TX_SETUP: begin
                    scl_low <= 1'b1;
                    sda_low <= ~shift_byte[bit_index];
                    if (quarter_done)
                        state <= ST_TX_HIGH;
                end

                ST_TX_HIGH: begin
                    scl_low <= 1'b0;
                    if (quarter_done && scl_in)
                        state <= ST_TX_LOW;
                end

                ST_TX_LOW: begin
                    scl_low <= 1'b1;
                    if (quarter_done) begin
                        if (bit_index == 0)
                            state <= ST_ACK_SETUP;
                        else begin
                            bit_index <= bit_index - 1'b1;
                            state <= ST_TX_SETUP;
                        end
                    end
                end

                ST_ACK_SETUP: begin
                    scl_low <= 1'b1;
                    sda_low <= 1'b0;
                    if (quarter_done)
                        state <= ST_ACK_HIGH;
                end

                ST_ACK_HIGH: begin
                    scl_low <= 1'b0;
                    if (quarter_done && scl_in) begin
                        if (sda_in == 1'b1) begin
                            nack <= 1'b1;
                            abort_after_ack <= 1'b1;
                        end
                        state <= ST_ACK_LOW;
                    end
                end

                ST_ACK_LOW: begin
                    scl_low <= 1'b1;
                    if (quarter_done) begin
                        if (abort_after_ack) begin
                            state <= ST_STOP_A;
                        end else if (address_phase) begin
                            address_phase <= 1'b0;
                            if (rw_latched) begin
                                bit_index <= 3'd7;
                                state <= ST_RX_SETUP;
                            end else if (tx_len_latched != 0) begin
                                shift_byte <= select_tx_byte(tx_latched, tx_len_latched, 0);
                                bit_index <= 3'd7;
                                tx_index <= 0;
                                state <= ST_TX_SETUP;
                            end else begin
                                state <= ST_STOP_A;
                            end
                        end else if ((tx_index + 1'b1) < tx_len_latched) begin
                            tx_index <= tx_index + 1'b1;
                            shift_byte <= select_tx_byte(tx_latched, tx_len_latched, tx_index + 1'b1);
                            bit_index <= 3'd7;
                            state <= ST_TX_SETUP;
                        end else begin
                            state <= ST_STOP_A;
                        end
                    end
                end

                ST_RX_SETUP: begin
                    scl_low <= 1'b1;
                    sda_low <= 1'b0;
                    if (quarter_done)
                        state <= ST_RX_HIGH;
                end

                ST_RX_HIGH: begin
                    scl_low <= 1'b0;
                    if (quarter_done && scl_in) begin
                        rx_data <= {rx_data[46:0], sda_in};
                        state <= ST_RX_LOW;
                    end
                end

                ST_RX_LOW: begin
                    scl_low <= 1'b1;
                    if (quarter_done) begin
                        if (bit_index == 0)
                            state <= ST_MACK_SETUP;
                        else begin
                            bit_index <= bit_index - 1'b1;
                            state <= ST_RX_SETUP;
                        end
                    end
                end

                ST_MACK_SETUP: begin
                    scl_low <= 1'b1;
                    sda_low <= ((rx_index + 1'b1) < rx_len_latched);
                    if (quarter_done)
                        state <= ST_MACK_HIGH;
                end

                ST_MACK_HIGH: begin
                    scl_low <= 1'b0;
                    if (quarter_done && scl_in)
                        state <= ST_MACK_LOW;
                end

                ST_MACK_LOW: begin
                    scl_low <= 1'b1;
                    if (quarter_done) begin
                        sda_low <= 1'b0;
                        if ((rx_index + 1'b1) < rx_len_latched) begin
                            rx_index <= rx_index + 1'b1;
                            bit_index <= 3'd7;
                            state <= ST_RX_SETUP;
                        end else begin
                            state <= ST_STOP_A;
                        end
                    end
                end

                ST_STOP_A: begin
                    scl_low <= 1'b1;
                    sda_low <= 1'b1;
                    if (quarter_done)
                        state <= ST_STOP_B;
                end

                ST_STOP_B: begin
                    scl_low <= 1'b0;
                    sda_low <= 1'b1;
                    if (quarter_done && scl_in)
                        state <= ST_STOP_C;
                end

                ST_STOP_C: begin
                    scl_low <= 1'b0;
                    sda_low <= 1'b0;
                    if (quarter_done) begin
                        busy  <= 1'b0;
                        done  <= 1'b1;
                        state <= ST_IDLE;
                    end
                end

                default: state <= ST_IDLE;
            endcase
        end
    end
endmodule
