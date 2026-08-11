library ieee;
use ieee.std_logic_1164.all;

-- Thin wrapper around Xilinx/PYNQ rgb2dvi pure RTL.
-- rgb2dvi internally expects its 24-bit bus in R-B-G byte order.
entity dvi_out_wrapper is
    port (
        pixel_clk   : in  std_logic;
        serial_clk  : in  std_logic;
        rst         : in  std_logic;
        red         : in  std_logic_vector(7 downto 0);
        green       : in  std_logic_vector(7 downto 0);
        blue        : in  std_logic_vector(7 downto 0);
        de          : in  std_logic;
        hsync       : in  std_logic;
        vsync       : in  std_logic;
        tmds_clk_p  : out std_logic;
        tmds_clk_n  : out std_logic;
        tmds_data_p : out std_logic_vector(2 downto 0);
        tmds_data_n : out std_logic_vector(2 downto 0)
    );
end entity;

architecture rtl of dvi_out_wrapper is
begin
    u_rgb2dvi : entity work.rgb2dvi
        generic map (
            kGenerateSerialClk => false,
            kClkPrimitive      => "MMCM",
            kClkRange          => 5,
            kRstActiveHigh     => true
        )
        port map (
            TMDS_Clk_p  => tmds_clk_p,
            TMDS_Clk_n  => tmds_clk_n,
            TMDS_Data_p => tmds_data_p,
            TMDS_Data_n => tmds_data_n,
            aRst        => rst,
            aRst_n      => '1',
            vid_pData   => red & blue & green,
            vid_pVDE    => de,
            vid_pHSync  => hsync,
            vid_pVSync  => vsync,
            PixelClk    => pixel_clk,
            SerialClk   => serial_clk
        );
end architecture;

