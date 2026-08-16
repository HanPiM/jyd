`timescale 1ns/1ps

module tb_jyd_aht10_i2c;
    reg clk = 0;
    reg rst_n = 0;
    always #50 clk = ~clk;

    reg start = 0;
    reg rw = 0;
    reg [23:0] tx_data = 0;
    reg [1:0] tx_len = 0;
    reg [2:0] rx_len = 0;
    wire [47:0] rx_data;
    wire busy, done, nack;
    tri1 scl;
    tri1 sda;
    wire scl_drive_low;
    wire sda_drive_low;
    reg slave_sda_low = 0;

    assign scl = scl_drive_low ? 1'b0 : 1'bz;
    assign sda = (sda_drive_low || slave_sda_low) ? 1'b0 : 1'bz;

    reg [7:0] got_addr, got0, got1, got2;
    reg [7:0] reply [0:5];
    integer byte_no, bit_no;
    time last_rise = 0;
    time measured_period = 0;

    jyd_aht10_i2c_master #(.CLK_HZ(10_000_000), .I2C_HZ(100_000)) dut (
        .clk(clk), .rst_n(rst_n), .start(start), .address(7'h38),
        .read_not_write(rw), .tx_data(tx_data), .tx_len(tx_len),
        .rx_len(rx_len), .rx_data(rx_data), .busy(busy), .done(done),
        .nack(nack), .scl_in(scl), .sda_in(sda),
        .scl_drive_low(scl_drive_low), .sda_drive_low(sda_drive_low)
    );

    task receive_byte(output reg [7:0] value);
        integer n;
        begin
            for (n = 7; n >= 0; n = n - 1) begin
                @(posedge scl);
                value[n] = sda;
            end
        end
    endtask

    task acknowledge;
        begin
            @(negedge scl); slave_sda_low = 1;
            @(posedge scl);
            @(negedge scl); slave_sda_low = 0;
        end
    endtask

    initial begin : slave_model
        reply[0] = 8'h08; reply[1] = 8'h80; reply[2] = 8'h00;
        reply[3] = 8'h06; reply[4] = 8'h66; reply[5] = 8'h66;

        wait (rst_n === 1'b1);
        wait (scl === 1'b1 && sda === 1'b1);
        @(negedge sda);
        if (!scl) begin $display("FAIL START write"); $finish; end
        receive_byte(got_addr); acknowledge();
        receive_byte(got0); acknowledge();
        receive_byte(got1); acknowledge();
        receive_byte(got2); acknowledge();
        begin : wait_for_write_stop
            reg stop_seen;
            stop_seen = 0;
            while (!stop_seen) begin
                @(posedge sda);
                if (scl) stop_seen = 1;
            end
        end

        @(negedge sda);
        if (!scl) begin $display("FAIL START read"); $finish; end
        receive_byte(got_addr);
        @(negedge scl); slave_sda_low = 1;
        @(posedge scl);
        @(negedge scl); slave_sda_low = ~reply[0][7];

        for (byte_no = 0; byte_no < 6; byte_no = byte_no + 1) begin
            for (bit_no = 7; bit_no >= 0; bit_no = bit_no - 1) begin
                if (bit_no != 7) begin
                    @(negedge scl); slave_sda_low = ~reply[byte_no][bit_no];
                end
                @(posedge scl);
            end
            @(negedge scl); slave_sda_low = 0;
            @(posedge scl);
            if (byte_no < 5 && sda !== 1'b0) begin
                $display("FAIL master ACK byte %0d", byte_no); $finish;
            end
            if (byte_no == 5 && sda !== 1'b1) begin
                $display("FAIL master final NACK"); $finish;
            end
            @(negedge scl);
            if (byte_no < 5) slave_sda_low = ~reply[byte_no + 1][7];
        end
        slave_sda_low = 0;
    end

    always @(posedge scl) begin
        if (busy && last_rise != 0) measured_period = $time - last_rise;
        if (busy) last_rise = $time;
    end

    initial begin
        repeat (5) @(posedge clk); rst_n = 1;
        repeat (5) @(posedge clk);

        rw = 0; tx_data = 24'hAC_33_00; tx_len = 3; rx_len = 0;
        start = 1; @(posedge clk); start = 0;
        wait (done);
        if (nack || got_addr !== 8'h70 || got0 !== 8'hAC || got1 !== 8'h33 || got2 !== 8'h00) begin
            $display("FAIL write nack=%b addr=%h data=%h_%h_%h", nack, got_addr, got0, got1, got2);
            $finish;
        end

        repeat (10) @(posedge clk);
        rw = 1; tx_len = 0; rx_len = 6;
        start = 1; @(posedge clk); start = 0;
        wait (done);
        if (nack || got_addr !== 8'h71 || rx_data !== 48'h08_80_00_06_66_66) begin
            $display("FAIL read nack=%b addr=%h rx=%h", nack, got_addr, rx_data); $finish;
        end
        if (measured_period < 9800 || measured_period > 10200) begin
            $display("FAIL SCL period %0t", measured_period); $finish;
        end
        if (scl !== 1'b1 || sda !== 1'b1 || scl_drive_low || sda_drive_low) begin
            $display("FAIL bus not released after STOP"); $finish;
        end

        // With no slave ACK, the master must report NACK and still issue STOP.
        repeat (10) @(posedge clk);
        rw = 0; tx_len = 0;
        start = 1; @(posedge clk); start = 0;
        wait (done);
        if (!nack || scl !== 1'b1 || sda !== 1'b1) begin
            $display("FAIL slave NACK/release nack=%b scl=%b sda=%b", nack, scl, sda); $finish;
        end

        $display("PASS I2C: START/STOP, ACK/NACK, 3-byte write, 6-byte read, open-drain, 100kHz");
        $finish;
    end
endmodule
