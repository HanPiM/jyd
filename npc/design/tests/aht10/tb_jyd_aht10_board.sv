`timescale 1ns/1ps

module tb_jyd_aht10_board;
    reg clk = 0;
    reg rst_n = 0;
    reg key_n = 1;
    wire press_pulse;
    integer pulse_count = 0;
    reg display_mode = 0;
    reg [15:0] binary;
    wire [15:0] bcd;

    always #500 clk = ~clk;

    jyd_key_debounce #(.CLK_HZ(1_000_000), .DEBOUNCE_MS(1)) debounce (
        .clk(clk), .rst_n(rst_n), .key_n(key_n), .press_pulse(press_pulse)
    );
    jyd_bin16_to_bcd formatter (.binary(binary), .bcd(bcd));

    always @(posedge clk) begin
        if (!rst_n) begin
            pulse_count <= 0;
            display_mode <= 0;
        end else if (press_pulse) begin
            pulse_count <= pulse_count + 1;
            display_mode <= ~display_mode;
        end
    end

    initial begin
        binary = 16'd264;
        #1;
        if (bcd !== 16'h0264) begin $display("FAIL BCD temperature %h", bcd); $finish; end
        binary = 16'd637;
        #1;
        if (bcd !== 16'h0637) begin $display("FAIL BCD humidity %h", bcd); $finish; end

        repeat (4) @(posedge clk); rst_n = 1;
        repeat (5) begin
            repeat (10) @(posedge clk); key_n = ~key_n;
        end
        key_n = 0;
        repeat (2500) @(posedge clk);
        if (pulse_count != 1 || display_mode != 1) begin
            $display("FAIL first press/hold pulses=%0d mode=%b", pulse_count, display_mode); $finish;
        end

        key_n = 1;
        repeat (1200) @(posedge clk);
        key_n = 0;
        repeat (1200) @(posedge clk);
        if (pulse_count != 2 || display_mode != 0) begin
            $display("FAIL second press pulses=%0d mode=%b", pulse_count, display_mode); $finish;
        end
        $display("PASS board: 2FF debounce, bounce rejection, one pulse per press, long-hold immunity, T/H toggle, BCD");
        $finish;
    end
endmodule
