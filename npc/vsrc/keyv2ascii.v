module keyv2ascii(
    input [7:0] keyv,
    output reg [7:0] asciiv
);
always @ (*) begin
    case (keyv)
        8'h45: asciiv = 8'h30;   //0
        8'h16: asciiv = 8'h31;   //1
        8'h1e: asciiv = 8'h32;   //2
        8'h26: asciiv = 8'h33;   //3
        8'h25: asciiv = 8'h34;   //4
        8'h2e: asciiv = 8'h35;   //5
        8'h36: asciiv = 8'h36;   //6
        8'h3d: asciiv = 8'h37;   //7
        8'h3e: asciiv = 8'h38;   //8
        8'h46: asciiv = 8'h39;   //9
       8'h15: asciiv = 8'h51;   //Q
       8'h1d: asciiv = 8'h57;   //W
       8'h24: asciiv = 8'h45;   //E
       8'h2d: asciiv = 8'h52;   //R
       8'h2c: asciiv = 8'h54;   //T
       8'h35: asciiv = 8'h59;   //Y
       8'h3c: asciiv = 8'h55;   //U
       8'h43: asciiv = 8'h49;   //I
       8'h44: asciiv = 8'h4f;   //O
       8'h4d: asciiv = 8'h50;   //P
       8'h1c: asciiv = 8'h41;   //A
       8'h1b: asciiv = 8'h53;   //S
       8'h23: asciiv = 8'h44;   //D
       8'h2b: asciiv = 8'h46;   //F
       8'h34: asciiv = 8'h47;   //G
       8'h33: asciiv = 8'h48;   //H
       8'h3b: asciiv = 8'h4a;   //J
       8'h42: asciiv = 8'h4b;   //K
       8'h4b: asciiv = 8'h4c;   //L
       8'h1a: asciiv = 8'h5a;   //Z
       8'h22: asciiv = 8'h58;   //X
       8'h21: asciiv = 8'h43;   //C
       8'h2a: asciiv = 8'h56;   //V
       8'h32: asciiv = 8'h42;   //B
       8'h31: asciiv = 8'h4e;   //N
       8'h3a: asciiv = 8'h4d;   //M
       default: asciiv = 8'h00; //null

       endcase

end
endmodule
