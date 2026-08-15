`timescale 1ns/1ps

module tb_jyd_aht10_controller;
    reg clk = 0, rst_n = 0;
    always #5000 clk = ~clk;
    tri1 scl, sda;
    wire scl_drive_low, sda_drive_low;
    reg slave_sda_low = 0;
    assign scl = scl_drive_low ? 1'b0 : 1'bz;
    assign sda = (sda_drive_low || slave_sda_low) ? 1'b0 : 1'bz;

    wire signed [15:0] temperature_x10;
    wire [15:0] humidity_x10;
    wire data_valid, busy, error_event;
    wire [1:0] error_code;
    reg error_seen = 0;
    reg [7:0] value;
    reg [7:0] reply [0:5];

    jyd_aht10_controller #(
        .CLK_HZ(100_000), .I2C_HZ(10_000), .SAMPLE_INTERVAL_MS(20)
    ) dut (
        .clk(clk), .rst_n(rst_n), .scl_in(scl), .sda_in(sda),
        .scl_drive_low(scl_drive_low), .sda_drive_low(sda_drive_low),
        .temperature_x10(temperature_x10), .humidity_x10(humidity_x10),
        .data_valid(data_valid), .busy(busy), .error_code(error_code),
        .error_event(error_event)
    );

    always @(posedge clk) if (error_event) error_seen <= 1;

    task receive_byte(output reg [7:0] v);
        integer i;
        begin
            for (i = 7; i >= 0; i = i - 1) begin @(posedge scl); v[i] = sda; end
        end
    endtask

    task ack;
        begin
            @(negedge scl); slave_sda_low = 1;
            @(posedge scl);
            @(negedge scl); slave_sda_low = 0;
        end
    endtask

    task wait_start;
        begin : wait_start_block
            reg seen;
            seen = 0;
            while (!seen) begin @(negedge sda); if (scl) seen = 1; end
        end
    endtask

    task wait_stop;
        begin : wait_stop_block
            reg seen;
            seen = 0;
            while (!seen) begin @(posedge sda); if (scl) seen = 1; end
        end
    endtask

    task send_bytes(input integer count);
        integer byte_index, bit_index;
        begin
            @(negedge scl); slave_sda_low = 1;
            @(posedge scl);
            @(negedge scl); slave_sda_low = ~reply[0][7];
            for (byte_index = 0; byte_index < count; byte_index = byte_index + 1) begin
                for (bit_index = 7; bit_index >= 0; bit_index = bit_index - 1) begin
                    if (bit_index != 7) begin
                        @(negedge scl); slave_sda_low = ~reply[byte_index][bit_index];
                    end
                    @(posedge scl);
                end
                @(negedge scl); slave_sda_low = 0;
                @(posedge scl);
                @(negedge scl);
                if (byte_index < count - 1) slave_sda_low = ~reply[byte_index + 1][7];
            end
            slave_sda_low = 0;
        end
    endtask

    initial begin : sensor
        // First status access is deliberately NACKed to exercise recovery.
        wait_start(); receive_byte(value); wait_stop();

        // Recovery sends the AHT10 soft-reset byte BA.
        wait_start(); receive_byte(value); ack();
        if (value !== 8'h70) begin $display("FAIL reset address %h", value); $finish; end
        receive_byte(value); ack();
        if (value !== 8'hBA) begin $display("FAIL reset command %h", value); $finish; end
        wait_stop();

        // Recovered status: calibrated and idle.
        wait_start(); receive_byte(value);
        if (value !== 8'h71) begin $display("FAIL status address %h", value); $finish; end
        reply[0] = 8'h08; send_bytes(1); wait_stop();

        // Trigger command AC 33 00.
        wait_start(); receive_byte(value); ack();
        if (value !== 8'h70) begin $display("FAIL trigger address %h", value); $finish; end
        receive_byte(value); if (value !== 8'hAC) begin $display("FAIL cmd %h", value); $finish; end ack();
        receive_byte(value); if (value !== 8'h33) begin $display("FAIL arg0 %h", value); $finish; end ack();
        receive_byte(value); if (value !== 8'h00) begin $display("FAIL arg1 %h", value); $finish; end ack();
        wait_stop();

        // 50.0 %RH raw=0x80000, 25.0 C raw=0x60000.
        wait_start(); receive_byte(value);
        if (value !== 8'h71) begin $display("FAIL data address %h", value); $finish; end
        reply[0] = 8'h08; reply[1] = 8'h80; reply[2] = 8'h00;
        reply[3] = 8'h06; reply[4] = 8'h00; reply[5] = 8'h00;
        send_bytes(6); wait_stop();
    end

    initial begin
        repeat (5) @(posedge clk); rst_n = 1;
        wait (data_valid);
        #1;
        if (!error_seen || temperature_x10 !== 16'sd250 || humidity_x10 !== 16'd500 || error_code !== 0) begin
            $display("FAIL recovery/conversion seen=%b T=%0d H=%0d E=%0d",
                     error_seen, temperature_x10, humidity_x10, error_code);
            $finish;
        end
        $display("PASS controller: STATUS, NACK recovery/reset, AC3300, busy wait, 6-byte parse, x10 conversion");
        $finish;
    end

    initial begin
        #500_000_000;
        $display("FAIL controller timeout");
        $finish;
    end
endmodule
