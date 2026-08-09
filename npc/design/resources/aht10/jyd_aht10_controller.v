`timescale 1ns/1ps

// Adapted from the hardware-verified AX7035B AHT10 prototype. The protocol,
// wait intervals, retry policy, and fixed-point conversions are unchanged.
module jyd_aht10_controller #(
    parameter integer CLK_HZ = 50_000_000,
    parameter integer I2C_HZ = 100_000,
    parameter integer SAMPLE_INTERVAL_MS = 2000
) (
    input  wire               clk,
    input  wire               rst_n,
    input  wire               scl_in,
    input  wire               sda_in,
    output wire               scl_drive_low,
    output wire               sda_drive_low,
    output reg signed [15:0]  temperature_x10,
    output reg        [15:0]  humidity_x10,
    output reg                data_valid,
    output wire               busy,
    output reg         [1:0]  error_code,
    output reg                error_event
);
    localparam integer CYCLES_PER_MS = CLK_HZ / 1000;
    localparam integer POWER_WAIT_CYCLES = 40 * CYCLES_PER_MS;
    localparam integer INIT_WAIT_CYCLES  = 10 * CYCLES_PER_MS;
    localparam integer MEAS_WAIT_CYCLES  = 80 * CYCLES_PER_MS;
    localparam integer BUSY_RETRY_CYCLES = 5 * CYCLES_PER_MS;
    localparam integer BUSY_TIMEOUT_CYCLES = 250 * CYCLES_PER_MS;
    localparam integer RESET_WAIT_CYCLES = 20 * CYCLES_PER_MS;
    localparam integer SAMPLE_WAIT_CYCLES = SAMPLE_INTERVAL_MS * CYCLES_PER_MS;

    localparam [4:0] ST_POWER_WAIT     = 5'd0,
                     ST_STATUS_START   = 5'd1,
                     ST_STATUS_WAIT    = 5'd2,
                     ST_INIT_START     = 5'd3,
                     ST_INIT_WAIT_TX   = 5'd4,
                     ST_INIT_DELAY     = 5'd5,
                     ST_TRIGGER_START  = 5'd6,
                     ST_TRIGGER_WAIT   = 5'd7,
                     ST_MEASURE_DELAY  = 5'd8,
                     ST_READ_START     = 5'd9,
                     ST_READ_WAIT      = 5'd10,
                     ST_BUSY_DELAY     = 5'd11,
                     ST_CONVERT_1      = 5'd12,
                     ST_CONVERT_2      = 5'd13,
                     ST_SAMPLE_DELAY   = 5'd14,
                     ST_RECOVER_DELAY  = 5'd15,
                     ST_RESET_START    = 5'd16,
                     ST_RESET_WAIT_TX  = 5'd17,
                     ST_RESET_DELAY    = 5'd18;

    reg [4:0] state;
    reg [31:0] wait_count;
    reg [31:0] busy_elapsed;
    reg [1:0] init_attempts;
    reg [19:0] humidity_raw;
    reg [19:0] temperature_raw;
    /* verilator lint_off UNUSEDSIGNAL */
    reg [31:0] humidity_scaled;
    /* verilator lint_on UNUSEDSIGNAL */
    /* verilator lint_off UNUSEDSIGNAL */
    reg [31:0] temperature_scaled;
    /* verilator lint_on UNUSEDSIGNAL */

    reg         i2c_start;
    reg         i2c_rw;
    reg [23:0]  i2c_tx_data;
    reg [1:0]   i2c_tx_len;
    reg [2:0]   i2c_rx_len;
    /* verilator lint_off UNUSEDSIGNAL */
    wire [47:0] i2c_rx_data;
    /* verilator lint_on UNUSEDSIGNAL */
    wire        i2c_busy;
    wire        i2c_done;
    wire        i2c_nack;

    assign busy = (state != ST_SAMPLE_DELAY) && (state != ST_RECOVER_DELAY);

    jyd_aht10_i2c_master #(
        .CLK_HZ(CLK_HZ),
        .I2C_HZ(I2C_HZ)
    ) u_i2c_master (
        .clk(clk),
        .rst_n(rst_n),
        .start(i2c_start),
        .address(7'h38),
        .read_not_write(i2c_rw),
        .tx_data(i2c_tx_data),
        .tx_len(i2c_tx_len),
        .rx_len(i2c_rx_len),
        .rx_data(i2c_rx_data),
        .busy(i2c_busy),
        .done(i2c_done),
        .nack(i2c_nack),
        .scl_in(scl_in),
        .sda_in(sda_in),
        .scl_drive_low(scl_drive_low),
        .sda_drive_low(sda_drive_low)
    );

    task flag_error;
        input [1:0] code;
        begin
            error_code  <= code;
            error_event <= 1'b1;
            wait_count  <= 0;
            state       <= ST_RECOVER_DELAY;
        end
    endtask

    always @(posedge clk or negedge rst_n) begin
        if (!rst_n) begin
            state              <= ST_POWER_WAIT;
            wait_count         <= 0;
            busy_elapsed       <= 0;
            init_attempts      <= 0;
            humidity_raw       <= 0;
            temperature_raw    <= 0;
            humidity_scaled    <= 0;
            temperature_scaled <= 0;
            temperature_x10    <= 0;
            humidity_x10       <= 0;
            data_valid         <= 0;
            error_code         <= 0;
            error_event        <= 0;
            i2c_start          <= 0;
            i2c_rw             <= 0;
            i2c_tx_data        <= 0;
            i2c_tx_len         <= 0;
            i2c_rx_len         <= 0;
        end else begin
            i2c_start   <= 1'b0;
            data_valid  <= 1'b0;
            error_event <= 1'b0;

            case (state)
                ST_POWER_WAIT: begin
                    if (wait_count == POWER_WAIT_CYCLES - 1) begin
                        wait_count <= 0;
                        state <= ST_STATUS_START;
                    end else wait_count <= wait_count + 1'b1;
                end

                ST_STATUS_START: begin
                    if (!i2c_busy) begin
                        i2c_rw      <= 1'b1;
                        i2c_tx_len  <= 0;
                        i2c_rx_len  <= 1;
                        i2c_start   <= 1'b1;
                        state       <= ST_STATUS_WAIT;
                    end
                end

                ST_STATUS_WAIT: begin
                    if (i2c_done) begin
                        if (i2c_nack) begin
                            flag_error(2'd1);
                        end else if (i2c_rx_data[3]) begin
                            init_attempts <= 0;
                            state <= ST_TRIGGER_START;
                        end else if (init_attempts == 2'd3) begin
                            flag_error(2'd3);
                        end else begin
                            state <= ST_INIT_START;
                        end
                    end
                end

                ST_INIT_START: begin
                    if (!i2c_busy) begin
                        i2c_rw      <= 1'b0;
                        i2c_tx_data <= 24'hE1_08_00;
                        i2c_tx_len  <= 3;
                        i2c_rx_len  <= 0;
                        i2c_start   <= 1'b1;
                        state       <= ST_INIT_WAIT_TX;
                    end
                end

                ST_INIT_WAIT_TX: begin
                    if (i2c_done) begin
                        if (i2c_nack)
                            flag_error(2'd1);
                        else begin
                            init_attempts <= init_attempts + 1'b1;
                            wait_count <= 0;
                            state <= ST_INIT_DELAY;
                        end
                    end
                end

                ST_INIT_DELAY: begin
                    if (wait_count == INIT_WAIT_CYCLES - 1) begin
                        wait_count <= 0;
                        state <= ST_STATUS_START;
                    end else wait_count <= wait_count + 1'b1;
                end

                ST_TRIGGER_START: begin
                    if (!i2c_busy) begin
                        i2c_rw      <= 1'b0;
                        i2c_tx_data <= 24'hAC_33_00;
                        i2c_tx_len  <= 3;
                        i2c_rx_len  <= 0;
                        i2c_start   <= 1'b1;
                        state       <= ST_TRIGGER_WAIT;
                    end
                end

                ST_TRIGGER_WAIT: begin
                    if (i2c_done) begin
                        if (i2c_nack)
                            flag_error(2'd1);
                        else begin
                            wait_count   <= 0;
                            busy_elapsed <= 0;
                            state <= ST_MEASURE_DELAY;
                        end
                    end
                end

                ST_MEASURE_DELAY: begin
                    if (wait_count == MEAS_WAIT_CYCLES - 1) begin
                        wait_count <= 0;
                        busy_elapsed <= MEAS_WAIT_CYCLES;
                        state <= ST_READ_START;
                    end else wait_count <= wait_count + 1'b1;
                end

                ST_READ_START: begin
                    if (!i2c_busy) begin
                        i2c_rw      <= 1'b1;
                        i2c_tx_len  <= 0;
                        i2c_rx_len  <= 6;
                        i2c_start   <= 1'b1;
                        state       <= ST_READ_WAIT;
                    end
                end

                ST_READ_WAIT: begin
                    if (i2c_done) begin
                        if (i2c_nack) begin
                            flag_error(2'd1);
                        end else if (i2c_rx_data[47]) begin
                            if (busy_elapsed >= BUSY_TIMEOUT_CYCLES) begin
                                flag_error(2'd2);
                            end else begin
                                wait_count <= 0;
                                state <= ST_BUSY_DELAY;
                            end
                        end else begin
                            humidity_raw    <= {i2c_rx_data[39:32], i2c_rx_data[31:24], i2c_rx_data[23:20]};
                            temperature_raw <= {i2c_rx_data[19:16], i2c_rx_data[15:8], i2c_rx_data[7:0]};
                            state <= ST_CONVERT_1;
                        end
                    end
                end

                ST_BUSY_DELAY: begin
                    if (wait_count == BUSY_RETRY_CYCLES - 1) begin
                        wait_count <= 0;
                        busy_elapsed <= busy_elapsed + BUSY_RETRY_CYCLES;
                        state <= ST_READ_START;
                    end else wait_count <= wait_count + 1'b1;
                end

                ST_CONVERT_1: begin
                    humidity_scaled    <= humidity_raw * 32'd1000 + 32'd524288;
                    temperature_scaled <= temperature_raw * 32'd2000 + 32'd524288;
                    state <= ST_CONVERT_2;
                end

                ST_CONVERT_2: begin
                    humidity_x10    <= {4'b0, humidity_scaled[31:20]};
                    temperature_x10 <= $signed({5'b0, temperature_scaled[30:20]}) - 16'sd500;
                    data_valid      <= 1'b1;
                    error_code      <= 0;
                    wait_count      <= 0;
                    state           <= ST_SAMPLE_DELAY;
                end

                ST_SAMPLE_DELAY: begin
                    if (wait_count == SAMPLE_WAIT_CYCLES - 1) begin
                        wait_count <= 0;
                        state <= ST_TRIGGER_START;
                    end else wait_count <= wait_count + 1'b1;
                end

                ST_RECOVER_DELAY: begin
                    if (wait_count == SAMPLE_WAIT_CYCLES - 1) begin
                        wait_count <= 0;
                        state <= ST_RESET_START;
                    end else wait_count <= wait_count + 1'b1;
                end

                ST_RESET_START: begin
                    if (!i2c_busy) begin
                        i2c_rw      <= 1'b0;
                        i2c_tx_data <= 24'h00_00_BA;
                        i2c_tx_len  <= 1;
                        i2c_rx_len  <= 0;
                        i2c_start   <= 1'b1;
                        state       <= ST_RESET_WAIT_TX;
                    end
                end

                ST_RESET_WAIT_TX: begin
                    if (i2c_done) begin
                        wait_count <= 0;
                        if (i2c_nack) begin
                            error_event <= 1'b1;
                            state <= ST_RECOVER_DELAY;
                        end else begin
                            state <= ST_RESET_DELAY;
                        end
                    end
                end

                ST_RESET_DELAY: begin
                    if (wait_count == RESET_WAIT_CYCLES - 1) begin
                        wait_count <= 0;
                        init_attempts <= 0;
                        state <= ST_POWER_WAIT;
                    end else wait_count <= wait_count + 1'b1;
                end

                default: begin
                    wait_count <= 0;
                    state <= ST_POWER_WAIT;
                end
            endcase
        end
    end
endmodule
