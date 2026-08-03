`timescale 1ns / 1ps

// First-word-fall-through asynchronous byte FIFO.  The synchronised Gray
// pointers make the data side of a crossing local to its owning clock; the
// CPU-side UART adapter never waits for a UARTLite AXI response.
module jyd_async_byte_fifo #(
    parameter integer ADDR_BITS = 4
) (
    input  wire       wr_clk,
    input  wire       wr_resetn,
    input  wire       wr_en,
    input  wire [7:0] wr_data,
    output reg        wr_full,
    input  wire       rd_clk,
    input  wire       rd_resetn,
    input  wire       rd_en,
    output wire [7:0] rd_data,
    output reg        rd_empty
);
    localparam integer PTR_BITS = ADDR_BITS + 1;

    (* ram_style = "block" *) reg [7:0] memory [0:(1 << ADDR_BITS) - 1];
    reg [PTR_BITS-1:0] wr_bin = {PTR_BITS{1'b0}};
    reg [PTR_BITS-1:0] wr_gray = {PTR_BITS{1'b0}};
    reg [PTR_BITS-1:0] rd_bin = {PTR_BITS{1'b0}};
    reg [PTR_BITS-1:0] rd_gray = {PTR_BITS{1'b0}};
    (* ASYNC_REG = "TRUE" *) reg [PTR_BITS-1:0] rd_gray_wr_sync1 = {PTR_BITS{1'b0}};
    (* ASYNC_REG = "TRUE" *) reg [PTR_BITS-1:0] rd_gray_wr_sync2 = {PTR_BITS{1'b0}};
    (* ASYNC_REG = "TRUE" *) reg [PTR_BITS-1:0] wr_gray_rd_sync1 = {PTR_BITS{1'b0}};
    (* ASYNC_REG = "TRUE" *) reg [PTR_BITS-1:0] wr_gray_rd_sync2 = {PTR_BITS{1'b0}};

    function [PTR_BITS-1:0] bin_to_gray;
        input [PTR_BITS-1:0] value;
        begin
            bin_to_gray = (value >> 1) ^ value;
        end
    endfunction

    wire [PTR_BITS-1:0] wr_bin_next = wr_bin + ((wr_en && !wr_full) ? 1'b1 : 1'b0);
    wire [PTR_BITS-1:0] wr_gray_next = bin_to_gray(wr_bin_next);
    wire [PTR_BITS-1:0] rd_bin_next = rd_bin + ((rd_en && !rd_empty) ? 1'b1 : 1'b0);
    wire [PTR_BITS-1:0] rd_gray_next = bin_to_gray(rd_bin_next);
    wire [PTR_BITS-1:0] wr_full_compare = {
        ~rd_gray_wr_sync2[PTR_BITS-1:PTR_BITS-2],
        rd_gray_wr_sync2[PTR_BITS-3:0]
    };

    assign rd_data = memory[rd_bin[ADDR_BITS-1:0]];

    always @(posedge wr_clk) begin
        if (!wr_resetn) begin
            wr_bin <= {PTR_BITS{1'b0}};
            wr_gray <= {PTR_BITS{1'b0}};
            wr_full <= 1'b0;
            rd_gray_wr_sync1 <= {PTR_BITS{1'b0}};
            rd_gray_wr_sync2 <= {PTR_BITS{1'b0}};
        end else begin
            rd_gray_wr_sync1 <= rd_gray;
            rd_gray_wr_sync2 <= rd_gray_wr_sync1;
            if (wr_en && !wr_full)
                memory[wr_bin[ADDR_BITS-1:0]] <= wr_data;
            wr_bin <= wr_bin_next;
            wr_gray <= wr_gray_next;
            wr_full <= (wr_gray_next == wr_full_compare);
        end
    end

    always @(posedge rd_clk) begin
        if (!rd_resetn) begin
            rd_bin <= {PTR_BITS{1'b0}};
            rd_gray <= {PTR_BITS{1'b0}};
            rd_empty <= 1'b1;
            wr_gray_rd_sync1 <= {PTR_BITS{1'b0}};
            wr_gray_rd_sync2 <= {PTR_BITS{1'b0}};
        end else begin
            wr_gray_rd_sync1 <= wr_gray;
            wr_gray_rd_sync2 <= wr_gray_rd_sync1;
            rd_bin <= rd_bin_next;
            rd_gray <= rd_gray_next;
            rd_empty <= (rd_gray_next == wr_gray_rd_sync2);
        end
    end
endmodule

module jyd_uart_subsystem (
    input  wire       cpu_clk,
    input  wire       uart_clk,
    input  wire       resetn,
    input  wire       tx_push,
    input  wire [7:0] tx_data,
    output wire [7:0] rx_data,
    output wire       rx_empty,
    input  wire       rx_pop,
    input  wire       uart_rx,
    output wire       uart_tx
);
    wire       tx_full;
    wire       tx_empty;
    wire [7:0] tx_fifo_data;
    reg        tx_pop;
    wire       rx_full;
    reg        rx_push;
    reg [7:0]  rx_push_data;

    // CoreMark's validated report is 639 bytes.  1024 bytes preserves the
    // whole burst even if the 9600-baud transmitter has not drained anything.
    jyd_async_byte_fifo #(.ADDR_BITS(10)) tx_fifo (
        .wr_clk(cpu_clk), .wr_resetn(resetn), .wr_en(tx_push), .wr_data(tx_data), .wr_full(tx_full),
        .rd_clk(uart_clk), .rd_resetn(resetn), .rd_en(tx_pop), .rd_data(tx_fifo_data), .rd_empty(tx_empty)
    );
    jyd_async_byte_fifo #(.ADDR_BITS(4)) rx_fifo (
        .wr_clk(uart_clk), .wr_resetn(resetn), .wr_en(rx_push), .wr_data(rx_push_data), .wr_full(rx_full),
        .rd_clk(cpu_clk), .rd_resetn(resetn), .rd_en(rx_pop), .rd_data(rx_data), .rd_empty(rx_empty)
    );

`ifndef SYNTHESIS
    always @(posedge cpu_clk) begin
        if (resetn && tx_push && tx_full)
            $error("JYD UART TX FIFO overflow: CoreMark-capacity contract violated");
    end
`endif

    wire [3:0]  m_axi_awaddr;
    wire        m_axi_awvalid;
    wire        m_axi_awready;
    wire [31:0] m_axi_wdata;
    wire [3:0]  m_axi_wstrb;
    wire        m_axi_wvalid;
    wire        m_axi_wready;
    wire [1:0]  m_axi_bresp;
    wire        m_axi_bvalid;
    wire        m_axi_bready;
    wire [3:0]  m_axi_araddr;
    wire        m_axi_arvalid;
    wire        m_axi_arready;
    wire [31:0] m_axi_rdata;
    wire [1:0]  m_axi_rresp;
    wire        m_axi_rvalid;
    wire        m_axi_rready;

    jyd_axi_uartlite uartlite_inst (
        .s_axi_aclk(uart_clk), .s_axi_aresetn(resetn), .interrupt(),
        .s_axi_awaddr(m_axi_awaddr), .s_axi_awvalid(m_axi_awvalid), .s_axi_awready(m_axi_awready),
        .s_axi_wdata(m_axi_wdata), .s_axi_wstrb(m_axi_wstrb), .s_axi_wvalid(m_axi_wvalid), .s_axi_wready(m_axi_wready),
        .s_axi_bresp(m_axi_bresp), .s_axi_bvalid(m_axi_bvalid), .s_axi_bready(m_axi_bready),
        .s_axi_araddr(m_axi_araddr), .s_axi_arvalid(m_axi_arvalid), .s_axi_arready(m_axi_arready),
        .s_axi_rdata(m_axi_rdata), .s_axi_rresp(m_axi_rresp), .s_axi_rvalid(m_axi_rvalid), .s_axi_rready(m_axi_rready),
        .rx(uart_rx), .tx(uart_tx)
    );

    localparam [3:0] IDLE = 4'd0, TX_STATUS_AR = 4'd1, TX_STATUS_R = 4'd2,
                     TX_WRITE_AW_W = 4'd3, TX_WRITE_B = 4'd4, RX_STATUS_AR = 4'd5,
                     RX_STATUS_R = 4'd6, RX_DATA_AR = 4'd7, RX_DATA_R = 4'd8;
    reg [3:0] state = IDLE;
    reg aw_sent = 1'b0;
    reg w_sent = 1'b0;
    reg [7:0] rx_poll_divider = 8'd0;

    assign m_axi_awaddr = 4'h4;
    assign m_axi_wdata = {24'd0, tx_fifo_data};
    assign m_axi_wstrb = 4'b0001;
    assign m_axi_bready = (state == TX_WRITE_B);
    assign m_axi_araddr = (state == RX_STATUS_AR || state == RX_STATUS_R) ? 4'h8 : 4'h0;
    assign m_axi_awvalid = (state == TX_WRITE_AW_W) && !aw_sent;
    assign m_axi_wvalid = (state == TX_WRITE_AW_W) && !w_sent;
    assign m_axi_arvalid = (state == TX_STATUS_AR) || (state == RX_STATUS_AR) || (state == RX_DATA_AR);
    assign m_axi_rready = (state == TX_STATUS_R) || (state == RX_STATUS_R) || (state == RX_DATA_R);

    always @(posedge uart_clk) begin
        if (!resetn) begin
            state <= IDLE;
            aw_sent <= 1'b0;
            w_sent <= 1'b0;
            tx_pop <= 1'b0;
            rx_push <= 1'b0;
            rx_push_data <= 8'd0;
            rx_poll_divider <= 8'd0;
        end else begin
            tx_pop <= 1'b0;
            rx_push <= 1'b0;
            case (state)
                IDLE: begin
                    if (!tx_empty)
                        state <= TX_STATUS_AR;
                    else if (!rx_full && rx_poll_divider == 8'hff) begin
                        rx_poll_divider <= 8'd0;
                        state <= RX_STATUS_AR;
                    end else
                        rx_poll_divider <= rx_poll_divider + 1'b1;
                end
                TX_STATUS_AR: if (m_axi_arready) state <= TX_STATUS_R;
                TX_STATUS_R: if (m_axi_rvalid) begin
                    if (m_axi_rresp != 2'b00)
                        $error("JYD UART Lite TX status read failed");
                    else if (m_axi_rdata[3])
                        state <= TX_STATUS_AR;
                    else begin
                        aw_sent <= 1'b0;
                        w_sent <= 1'b0;
                        state <= TX_WRITE_AW_W;
                    end
                end
                TX_WRITE_AW_W: begin
                    if (m_axi_awvalid && m_axi_awready) aw_sent <= 1'b1;
                    if (m_axi_wvalid && m_axi_wready) w_sent <= 1'b1;
                    if ((aw_sent || (m_axi_awvalid && m_axi_awready)) &&
                        (w_sent || (m_axi_wvalid && m_axi_wready)))
                        state <= TX_WRITE_B;
                end
                TX_WRITE_B: if (m_axi_bvalid) begin
                    if (m_axi_bresp != 2'b00)
                        $error("JYD UART Lite TX write failed");
                    tx_pop <= 1'b1;
                    state <= IDLE;
                end
                RX_STATUS_AR: if (m_axi_arready) state <= RX_STATUS_R;
                RX_STATUS_R: if (m_axi_rvalid) begin
                    if (m_axi_rresp != 2'b00)
                        $error("JYD UART Lite RX status read failed");
                    else if (m_axi_rdata[0])
                        state <= RX_DATA_AR;
                    else
                        state <= IDLE;
                end
                RX_DATA_AR: if (m_axi_arready) state <= RX_DATA_R;
                RX_DATA_R: if (m_axi_rvalid) begin
                    if (m_axi_rresp != 2'b00)
                        $error("JYD UART Lite RX data read failed");
                    else begin
                        rx_push <= 1'b1;
                        rx_push_data <= m_axi_rdata[7:0];
                    end
                    state <= IDLE;
                end
                default: state <= IDLE;
            endcase
        end
    end
endmodule
