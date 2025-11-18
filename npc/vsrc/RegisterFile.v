import "DPI-C" function void reg_upadted(input int idx, input int data);
module RegisterFile #(ADDR_WIDTH = 1, DATA_WIDTH = 1) (
  input clk,
  input [ADDR_WIDTH-1:0] waddr,
  input [DATA_WIDTH-1:0] wdata,
  input [ADDR_WIDTH-1:0] raddr1,raddr2,
  output [DATA_WIDTH-1:0] rdata1,rdata2,
  output [DATA_WIDTH-1:0] a0,
  input wen
);
  reg [DATA_WIDTH-1:0] rf [2**ADDR_WIDTH-1:0];

  assign rdata1=(raddr1!=0)?rf[raddr1]:0;
  assign rdata2=(raddr2!=0)?rf[raddr2]:0;
  assign a0=rf[10];

  always@(*)begin
      if(wen&&waddr!=0)begin
          reg_upadted({27'b0,waddr}, wdata);
      end
  end

  always @(posedge clk) begin
    if (wen)begin
      rf[waddr] <= wdata;
    end
  end
endmodule
