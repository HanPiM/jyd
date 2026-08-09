`timescale 1ns/1ps

module ax7035b_uart #(
    parameter integer CLK_HZ = 100_000_000,
    parameter integer BAUD_RATE = 115200
) (
    input  wire       clk,
    input  wire       reset,
    input  wire       uart_rx,
    output wire       uart_tx,
    input  wire       tx_push,
    input  wire [7:0] tx_data,
    output wire       tx_full,
    output wire [7:0] rx_data,
    output wire       rx_empty,
    input  wire       rx_pop
);
    localparam integer CYCLES_PER_BIT = CLK_HZ / BAUD_RATE;
    localparam integer HALF_BIT = CYCLES_PER_BIT / 2;
    reg        tx_busy;
    reg [31:0] tx_count;
    reg [3:0]  tx_bit;
    reg [9:0]  tx_shift;

    assign tx_full = tx_busy;
    assign uart_tx = tx_busy ? tx_shift[0] : 1'b1;

    always @(posedge clk) begin
        if (reset) begin
            tx_busy  <= 1'b0;
            tx_count <= 0;
            tx_bit   <= 0;
            tx_shift <= 10'h3ff;
        end else if (!tx_busy) begin
            if (tx_push) begin
                tx_busy  <= 1'b1;
                tx_count <= CYCLES_PER_BIT - 1;
                tx_bit   <= 0;
                tx_shift <= {1'b1, tx_data, 1'b0};
            end
        end else if (tx_count != 0) begin
            tx_count <= tx_count - 1'b1;
        end else if (tx_bit == 9) begin
            tx_busy <= 1'b0;
        end else begin
            tx_count <= CYCLES_PER_BIT - 1;
            tx_bit   <= tx_bit + 1'b1;
            tx_shift <= {1'b1, tx_shift[9:1]};
        end
    end

    (* ASYNC_REG = "TRUE" *) reg rx_sync1;
    (* ASYNC_REG = "TRUE" *) reg rx_sync2;
    localparam [2:0] RX_IDLE=3'd0, RX_START=3'd1, RX_DATA=3'd2,
                     RX_STOP=3'd3, RX_HOLD=3'd4;
    reg [2:0]  rx_state;
    reg [31:0] rx_count;
    reg [2:0]  rx_bit;
    reg [7:0]  rx_shift;
    reg [7:0]  rx_data_reg;
    reg        rx_valid;

    assign rx_data  = rx_data_reg;
    assign rx_empty = ~rx_valid;

    always @(posedge clk) begin
        if (reset) begin
            rx_sync1    <= 1'b1;
            rx_sync2    <= 1'b1;
            rx_state    <= RX_IDLE;
            rx_count    <= 0;
            rx_bit      <= 0;
            rx_shift    <= 0;
            rx_data_reg <= 0;
            rx_valid    <= 1'b0;
        end else begin
            rx_sync1 <= uart_rx;
            rx_sync2 <= rx_sync1;
            case (rx_state)
                RX_IDLE: begin
                    if (!rx_sync2 && !rx_valid) begin
                        rx_count <= HALF_BIT - 1;
                        rx_state <= RX_START;
                    end
                end
                RX_START: begin
                    if (rx_count != 0)
                        rx_count <= rx_count - 1'b1;
                    else if (!rx_sync2) begin
                        rx_count <= CYCLES_PER_BIT - 1;
                        rx_bit   <= 0;
                        rx_state <= RX_DATA;
                    end else begin
                        rx_state <= RX_IDLE;
                    end
                end
                RX_DATA: begin
                    if (rx_count != 0)
                        rx_count <= rx_count - 1'b1;
                    else begin
                        rx_shift[rx_bit] <= rx_sync2;
                        rx_count <= CYCLES_PER_BIT - 1;
                        if (rx_bit == 7)
                            rx_state <= RX_STOP;
                        else
                            rx_bit <= rx_bit + 1'b1;
                    end
                end
                RX_STOP: begin
                    if (rx_count != 0)
                        rx_count <= rx_count - 1'b1;
                    else begin
                        if (rx_sync2) begin
                            rx_data_reg <= rx_shift;
                            rx_valid    <= 1'b1;
                            rx_state    <= RX_HOLD;
                        end else begin
                            rx_state <= RX_IDLE;
                        end
                    end
                end
                RX_HOLD: begin
                    if (rx_pop) begin
                        rx_valid <= 1'b0;
                        rx_state <= RX_IDLE;
                    end
                end
                default: rx_state <= RX_IDLE;
            endcase
        end
    end
endmodule
