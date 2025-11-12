module RegisterFile #(ADDR_WIDTH = 1, DATA_WIDTH = 1) (
  input clk,
  input [ADDR_WIDTH-1:0] waddr,
  input [DATA_WIDTH-1:0] wdata,
  input [ADDR_WIDTH-1:0] raddr1,raddr2,
  output [DATA_WIDTH-1:0] rdata1,rdata2,
  output [DATA_WIDTH-1:0] a0,
  input wen,
  input dump_info
);
  reg [DATA_WIDTH-1:0] rf [2**ADDR_WIDTH-1:0];

  assign rdata1=(raddr1!=0)?rf[raddr1]:0;
  assign rdata2=(raddr2!=0)?rf[raddr2]:0;
  assign a0=rf[10];

  function int read_reg(int idx);
      read_reg=rf[idx];
  endfunction

  export "DPI-C" function read_reg;
  import "DPI-C" context task reg_upadted();

  always@(rf)begin
      reg_upadted();
  end

  always @(posedge clk) begin
      if (wen)begin
          rf[waddr] <= wdata;
        end
    if(dump_info)begin
        for(integer i=0;i<2**ADDR_WIDTH;i=i+1)begin
            $display("r%d : %08X %d",i,rf[i],rf[i]);
        end
    end
  end
endmodule
