`timescale 1ns/1ps

module tb_jyd_aht10_cdc;
    reg clk_50mhz = 0;
    reg cpu_clk = 0;
    reg sensor_reset = 1;
    reg cpu_reset = 1;
    always #10 clk_50mhz = ~clk_50mhz;
    always #1.667 cpu_clk = ~cpu_clk;

    tri1 scl, sda;
    wire scl_drive_low, sda_drive_low;
    assign scl = scl_drive_low ? 1'b0 : 1'bz;
    assign sda = sda_drive_low ? 1'b0 : 1'bz;

    wire [2:0] status;
    wire signed [15:0] temperature_x10;
    wire [15:0] humidity_x10;
    wire [31:0] sample_seq;
    wire signed [15:0] local_temperature_x10;
    wire [15:0] local_humidity_x10;
    wire local_data_valid;

    jyd_aht10_fpga dut (
        .clk_50mhz(clk_50mhz), .cpu_clk(cpu_clk),
        .sensor_reset(sensor_reset), .cpu_reset(cpu_reset),
        .scl_in(scl), .sda_in(sda),
        .scl_drive_low(scl_drive_low), .sda_drive_low(sda_drive_low),
        .status(status), .temperature_x10(temperature_x10),
        .humidity_x10(humidity_x10), .sample_seq(sample_seq),
        .local_temperature_x10(local_temperature_x10),
        .local_humidity_x10(local_humidity_x10),
        .local_data_valid(local_data_valid)
    );

    initial begin
        repeat (5) @(posedge cpu_clk);
        sensor_reset = 0;
        cpu_reset = 0;

        @(negedge clk_50mhz);
        dut.temperature_snapshot = 16'sd264;
        dut.humidity_snapshot = 16'd637;
        dut.status_snapshot = 3'b001;
        dut.sample_toggle = 1'b1;
        repeat (12) @(posedge cpu_clk);
        if (sample_seq !== 1 || temperature_x10 !== 16'sd264 || humidity_x10 !== 16'd637 || status[0] !== 1) begin
            $display("FAIL first CDC seq=%0d T=%0d H=%0d status=%b",
                     sample_seq, temperature_x10, humidity_x10, status);
            $finish;
        end

        // Bundled data may remain stable for many clocks; no toggle means no CPU update.
        dut.temperature_snapshot = -16'sd55;
        dut.humidity_snapshot = 16'd812;
        repeat (12) @(posedge cpu_clk);
        if (sample_seq !== 1 || temperature_x10 !== 16'sd264 || humidity_x10 !== 16'd637) begin
            $display("FAIL CDC updated without toggle"); $finish;
        end

        dut.sample_toggle = 1'b0;
        repeat (12) @(posedge cpu_clk);
        if (sample_seq !== 2 || temperature_x10 !== -16'sd55 || humidity_x10 !== 16'd812) begin
            $display("FAIL second CDC seq=%0d T=%0d H=%0d", sample_seq, temperature_x10, humidity_x10);
            $finish;
        end
        repeat (20) @(posedge cpu_clk);
        if (sample_seq !== 2) begin $display("FAIL duplicate CDC update seq=%0d", sample_seq); $finish; end

        if (local_temperature_x10 !== -16'sd55 || local_humidity_x10 !== 16'd812 || !local_data_valid) begin
            $display("FAIL local snapshot outputs"); $finish;
        end
        $display("PASS CDC: stable bundled snapshot, 2FF toggle, coherent data, one seq increment per sample");
        $finish;
    end
endmodule
