`timescale 1ns / 1ps

// First-word-fall-through asynchronous byte FIFO implemented by the vendor
// CDC macro.  XPM owns the pointer synchronization and dual-clock storage.
module jyd_async_byte_fifo #(
    parameter integer ADDR_BITS = 4
) (
    input  wire       wr_clk,
    input  wire       wr_resetn,
    input  wire       wr_en,
    input  wire [7:0] wr_data,
    output wire       wr_full,
    input  wire       rd_clk,
    input  wire       rd_resetn,
    input  wire       rd_en,
    output wire [7:0] rd_data,
    output wire       rd_empty
);
    localparam integer FIFO_DEPTH = 1 << ADDR_BITS;
    localparam integer COUNT_BITS = ADDR_BITS + 1;

    xpm_fifo_async #(
        .CDC_SYNC_STAGES(2), .DOUT_RESET_VALUE("0"), .ECC_MODE("no_ecc"),
        .FIFO_MEMORY_TYPE("block"), .FIFO_READ_LATENCY(0), .FIFO_WRITE_DEPTH(FIFO_DEPTH),
        .FULL_RESET_VALUE(0), .PROG_EMPTY_THRESH(10), .PROG_FULL_THRESH(FIFO_DEPTH - 5),
        .RD_DATA_COUNT_WIDTH(COUNT_BITS), .READ_DATA_WIDTH(8), .READ_MODE("fwft"),
        .RELATED_CLOCKS(0), .USE_ADV_FEATURES("0707"), .WAKEUP_TIME(0),
        .WRITE_DATA_WIDTH(8), .WR_DATA_COUNT_WIDTH(COUNT_BITS)
    ) fifo_inst (
        .sleep(1'b0), .rst(!wr_resetn),
        .wr_clk(wr_clk), .wr_en(wr_en), .din(wr_data), .full(wr_full),
        .prog_full(), .wr_data_count(), .overflow(), .wr_rst_busy(), .almost_full(), .wr_ack(),
        .rd_clk(rd_clk), .rd_en(rd_en), .dout(rd_data), .empty(rd_empty),
        .prog_empty(), .rd_data_count(), .underflow(), .rd_rst_busy(), .almost_empty(), .data_valid(),
        .injectsbiterr(1'b0), .injectdbiterr(1'b0), .sbiterr(), .dbiterr()
    );
endmodule

module jyd_uart_subsystem (
    input  wire       cpu_clk,
    input  wire       uart_clk,
    input  wire       resetn,
    input  wire       tx_push,
    input  wire [7:0] tx_data,
    output wire       tx_full,
    output wire [7:0] rx_data,
    output wire       rx_empty,
    input  wire       rx_pop,
    input  wire       uart_rx,
    output wire       uart_tx
);
    wire       tx_fifo_full;
    wire       tx_empty;
    wire [7:0] tx_fifo_data;
    reg        tx_pop;
    wire       rx_full;
    reg        rx_push;
    reg [7:0]  rx_push_data;

    // The CPU polls tx_full through UART+1 before each byte write, so this
    // compact FIFO only absorbs AXI UARTLite service latency.
    // putch polls tx_full before every byte. XPM requires a minimum depth of
    // 16, so keep this queue shallow and preserve the polling protocol.
    jyd_async_byte_fifo #(.ADDR_BITS(4)) tx_fifo (
        .wr_clk(cpu_clk), .wr_resetn(resetn), .wr_en(tx_push), .wr_data(tx_data), .wr_full(tx_fifo_full),
        .rd_clk(uart_clk), .rd_resetn(resetn), .rd_en(tx_pop), .rd_data(tx_fifo_data), .rd_empty(tx_empty)
    );
    jyd_async_byte_fifo #(.ADDR_BITS(4)) rx_fifo (
        .wr_clk(uart_clk), .wr_resetn(resetn), .wr_en(rx_push), .wr_data(rx_push_data), .wr_full(rx_full),
        .rd_clk(cpu_clk), .rd_resetn(resetn), .rd_en(rx_pop), .rd_data(rx_data), .rd_empty(rx_empty)
    );

`ifndef SYNTHESIS
    always @(posedge cpu_clk) begin
        if (resetn && tx_push && tx_fifo_full)
            $error("JYD UART TX FIFO overflow: CoreMark-capacity contract violated");
    end
`endif

    assign tx_full = tx_fifo_full;

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
    reg just_popped = 1'b0;
    (* DONT_TOUCH = "TRUE" *) reg [7:0] tx_byte = 8'd0;

    assign m_axi_awaddr = 4'h4;
    assign m_axi_wdata = {24'd0, tx_byte};
    assign m_axi_wstrb = 4'b0001;
    assign m_axi_bready = (state == TX_WRITE_B);
    assign m_axi_araddr =
    (state == TX_STATUS_AR || state == TX_STATUS_R || state == RX_STATUS_AR || state == RX_STATUS_R) ? 4'h8 : 4'h0;
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
            just_popped <= 1'b0;
            tx_byte <= 8'd0;
        end else begin
            tx_pop <= 1'b0;
            rx_push <= 1'b0;
            case (state)
                IDLE: begin
                    // tx_pop is registered, so the FIFO pop lands one cycle
                    // after TX_WRITE_B and rd_empty updates one cycle later
                    // still.  Skipping the TX check for one cycle after a pop
                    // prevents a spurious TX sequence that would transmit the
                    // stale output of the just-emptied FIFO.
                    just_popped <= 1'b0;
                    if (!tx_empty && !just_popped) begin
                        // Latch the FIFO byte here: the AXI status poll and
                        // write handshake below take many cycles, during which
                        // the CPU-side writer may wrap the FIFO and overwrite
                        // the slot this byte is read from.  A live read would
                        // then transmit a stale/duplicated byte.
                        tx_byte <= tx_fifo_data;
                        state <= TX_STATUS_AR;
                    end
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
                    just_popped <= 1'b1;
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
