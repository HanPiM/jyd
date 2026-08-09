`timescale 1ns/1ps

// 50 MHz AHT10 controller plus bundled-data snapshot/toggle CDC into the CPU
// clock domain. Snapshot data changes only when a sample completes and remains
// stable for the roughly two-second interval before the next sample.
module jyd_aht10_fpga (
    input  wire        clk_50mhz,
    input  wire        cpu_clk,
    input  wire        sensor_reset,
    input  wire        cpu_reset,
    input  wire        scl_in,
    input  wire        sda_in,
    output wire        scl_drive_low,
    output wire        sda_drive_low,
    output wire [2:0]  status,
    output wire signed [31:0] temperature_x10,
    output wire [31:0] humidity_x10,
    output wire [31:0] sample_seq
);
    wire signed [15:0] sensor_temperature_x10;
    wire        [15:0] sensor_humidity_x10;
    wire               sensor_data_valid;
    wire               sensor_busy;
    wire         [1:0] sensor_error_code;
    wire               sensor_error_event;

    jyd_aht10_controller #(
        .CLK_HZ(50_000_000),
        .I2C_HZ(100_000),
        .SAMPLE_INTERVAL_MS(2000)
    ) u_controller (
        .clk(clk_50mhz),
        .rst_n(~sensor_reset),
        .scl_in(scl_in),
        .sda_in(sda_in),
        .scl_drive_low(scl_drive_low),
        .sda_drive_low(sda_drive_low),
        .temperature_x10(sensor_temperature_x10),
        .humidity_x10(sensor_humidity_x10),
        .data_valid(sensor_data_valid),
        .busy(sensor_busy),
        .error_code(sensor_error_code),
        .error_event(sensor_error_event)
    );

    reg signed [15:0] temperature_snapshot;
    reg        [15:0] humidity_snapshot;
    reg         [2:0] status_snapshot;
    reg               sample_toggle;

    always @(posedge clk_50mhz or posedge sensor_reset) begin
        if (sensor_reset) begin
            temperature_snapshot <= 0;
            humidity_snapshot    <= 0;
            status_snapshot      <= 0;
            sample_toggle        <= 0;
        end else if (sensor_data_valid) begin
            temperature_snapshot <= sensor_temperature_x10;
            humidity_snapshot    <= sensor_humidity_x10;
            status_snapshot      <= {|sensor_error_code, sensor_busy, 1'b1};
            sample_toggle        <= ~sample_toggle;
        end
    end

    // The toggle is the coherency event. Busy and error are independent,
    // slowly-changing one-bit status signals and therefore use their own
    // conventional two-flop synchronizers.
    (* ASYNC_REG = "TRUE" *) reg sample_toggle_sync1;
    (* ASYNC_REG = "TRUE" *) reg sample_toggle_sync2;
    (* ASYNC_REG = "TRUE" *) reg busy_sync1;
    (* ASYNC_REG = "TRUE" *) reg busy_sync2;
    (* ASYNC_REG = "TRUE" *) reg error_sync1;
    (* ASYNC_REG = "TRUE" *) reg error_sync2;

    reg sample_toggle_seen;
    reg signed [31:0] temperature_cpu;
    reg        [31:0] humidity_cpu;
    reg         [2:0] status_cpu;
    reg        [31:0] sample_seq_cpu;

    always @(posedge cpu_clk or posedge cpu_reset) begin
        if (cpu_reset) begin
            sample_toggle_sync1 <= 0;
            sample_toggle_sync2 <= 0;
            busy_sync1          <= 0;
            busy_sync2          <= 0;
            error_sync1         <= 0;
            error_sync2         <= 0;
            sample_toggle_seen  <= 0;
            temperature_cpu     <= 0;
            humidity_cpu        <= 0;
            status_cpu          <= 0;
            sample_seq_cpu      <= 0;
        end else begin
            sample_toggle_sync1 <= sample_toggle;
            sample_toggle_sync2 <= sample_toggle_sync1;
            busy_sync1          <= sensor_busy;
            busy_sync2          <= busy_sync1;
            error_sync1         <= |sensor_error_code;
            error_sync2         <= error_sync1;

            if (sample_toggle_sync2 != sample_toggle_seen) begin
                // The bundled snapshot has already been stable for at least
                // two CPU clocks when the synchronized toggle is observed.
                temperature_cpu    <= {{16{temperature_snapshot[15]}}, temperature_snapshot};
                humidity_cpu       <= {16'b0, humidity_snapshot};
                status_cpu         <= status_snapshot;
                sample_seq_cpu     <= sample_seq_cpu + 1'b1;
                sample_toggle_seen <= sample_toggle_sync2;
            end
        end
    end

    assign status          = {error_sync2 | status_cpu[2], busy_sync2 | status_cpu[1], status_cpu[0]};
    assign temperature_x10 = temperature_cpu;
    assign humidity_x10    = humidity_cpu;
    assign sample_seq      = sample_seq_cpu;

    // error_event is diagnostic-only in the verified controller; error_code
    // is held long enough to synchronize and is the MMIO error source.
    wire unused_error_event = sensor_error_event;
endmodule
