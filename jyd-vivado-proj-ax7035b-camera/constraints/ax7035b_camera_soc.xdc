# AX7035B + ACM5640-V5 + native HDMI OUT
# Target: xc7a35tfgg484-2

set_property CFGBVS VCCO [current_design]
set_property CONFIG_VOLTAGE 3.3 [current_design]

# ---------------------------------------------------------------------------
# Board clock / controls
# ---------------------------------------------------------------------------
set_property PACKAGE_PIN Y18 [get_ports clk_50m]
set_property IOSTANDARD LVCMOS33 [get_ports clk_50m]
create_clock -name clk_50m -period 20.000 [get_ports clk_50m]

set_property PACKAGE_PIN F20 [get_ports reset_n]
set_property IOSTANDARD LVCMOS33 [get_ports reset_n]
set_property PULLUP true [get_ports reset_n]

set_property PACKAGE_PIN M13 [get_ports key1_n]
set_property PACKAGE_PIN K14 [get_ports key2_n]
set_property PACKAGE_PIN K13 [get_ports key3_n]
set_property PACKAGE_PIN L13 [get_ports key4_n]
set_property IOSTANDARD LVCMOS33 [get_ports {key1_n key2_n key3_n key4_n}]
set_property PULLUP true [get_ports {key1_n key2_n key3_n key4_n}]

set_property PACKAGE_PIN F19 [get_ports led1_n]
set_property PACKAGE_PIN E21 [get_ports led2_n]
set_property PACKAGE_PIN D20 [get_ports led3_n]
set_property PACKAGE_PIN C20 [get_ports led4_n]
set_property IOSTANDARD LVCMOS33 [get_ports {led1_n led2_n led3_n led4_n}]
set_property DRIVE 8 [get_ports {led1_n led2_n led3_n led4_n}]
set_property SLEW SLOW [get_ports {led1_n led2_n led3_n led4_n}]

# ---------------------------------------------------------------------------
# AX7035B HDMI OUT, J6 (manual section 9)
# TMDS I/O standard is instantiated directly by the rgb2dvi OBUFDS primitives.
# ---------------------------------------------------------------------------
set_property PACKAGE_PIN E1 [get_ports hdmi_clk_p]
set_property PACKAGE_PIN D1 [get_ports hdmi_clk_n]
set_property PACKAGE_PIN G1 [get_ports hdmi_d0_p]
set_property PACKAGE_PIN F1 [get_ports hdmi_d0_n]
set_property PACKAGE_PIN H2 [get_ports hdmi_d1_p]
set_property PACKAGE_PIN G2 [get_ports hdmi_d1_n]
set_property PACKAGE_PIN K1 [get_ports hdmi_d2_p]
set_property PACKAGE_PIN J1 [get_ports hdmi_d2_n]

set_property PACKAGE_PIN M6 [get_ports hdmi_out_en]
set_property IOSTANDARD LVCMOS33 [get_ports hdmi_out_en]
set_property DRIVE 8 [get_ports hdmi_out_en]
set_property SLEW SLOW [get_ports hdmi_out_en]

set_property PACKAGE_PIN P5 [get_ports hdmi_hpd]
set_property IOSTANDARD LVCMOS33 [get_ports hdmi_hpd]

# ---------------------------------------------------------------------------
# OV5640 V5 on AX7035B J9 (Bank 16, factory/default VCCIO = 3.3 V).
# Wiring follows the requested J9 table exactly:
# SDA J9-20 B15, SCL J9-23 A19, HREF J9-33 E18, VSYNC J9-34 F18,
# XCLK J9-28 B20, PCLK J9-32 E19 (SRCC clock-capable input),
# D7 J9-21 B18, D6 J9-22 B17, D5 J9-29 C17, D4 J9-27 A20,
# D3 J9-24 A18, D2 J9-26 C18, D1 J9-25 C19, D0 J9-31 D19,
# RESET# J9-35 E17.
# Camera PWDN is NOT connected to FPGA: strap ACM5640 PWDN directly to
# J9-38 GND as shown in the wiring table. STROBE/NC remain unconnected.
# ---------------------------------------------------------------------------
set_property PACKAGE_PIN B15 [get_ports ov_sda]
set_property PACKAGE_PIN A19 [get_ports ov_scl]
set_property PACKAGE_PIN E18 [get_ports ov_href]
set_property PACKAGE_PIN F18 [get_ports ov_vsync]
set_property PACKAGE_PIN B20 [get_ports ov_xclk]
set_property PACKAGE_PIN E19 [get_ports ov_pclk]
set_property PACKAGE_PIN B18 [get_ports {ov_d[7]}]
set_property PACKAGE_PIN B17 [get_ports {ov_d[6]}]
set_property PACKAGE_PIN C17 [get_ports {ov_d[5]}]
set_property PACKAGE_PIN A20 [get_ports {ov_d[4]}]
set_property PACKAGE_PIN A18 [get_ports {ov_d[3]}]
set_property PACKAGE_PIN C18 [get_ports {ov_d[2]}]
set_property PACKAGE_PIN C19 [get_ports {ov_d[1]}]
set_property PACKAGE_PIN D19 [get_ports {ov_d[0]}]
set_property PACKAGE_PIN E17 [get_ports ov_reset_n]

set_property IOSTANDARD LVCMOS33 [get_ports {ov_sda ov_scl ov_href ov_vsync ov_xclk ov_pclk ov_d[*] ov_reset_n}]
set_property DRIVE 8 [get_ports {ov_xclk ov_reset_n}]
set_property SLEW SLOW [get_ports {ov_xclk ov_reset_n}]

# The supplied 800x480@30 register table documents a 24 MHz PCLK.
create_clock -name ov_pclk_in -period 41.667 [get_ports ov_pclk]

# PCLK is generated inside the camera from XCLK through the sensor PLL; its phase
# is not guaranteed against the board clock. All crossings use explicit CDC.
set_clock_groups -asynchronous \
    -group [get_clocks -include_generated_clocks clk_50m] \
    -group [get_clocks ov_pclk_in]

# ---------------------------------------------------------------------------
# JYD SoC board I/O, retained from the verified AX7035B CPU port.
# These pins do not overlap the frozen camera/HDMI/KEY/LED assignments above.
# ---------------------------------------------------------------------------
set_property PACKAGE_PIN G15 [get_ports uart_rx]
set_property PACKAGE_PIN G16 [get_ports uart_tx]
set_property IOSTANDARD LVCMOS33 [get_ports {uart_rx uart_tx}]

set_property PACKAGE_PIN P17 [get_ports aht10_scl]
set_property PACKAGE_PIN N17 [get_ports aht10_sda]
set_property IOSTANDARD LVCMOS33 [get_ports {aht10_scl aht10_sda}]

set_property PACKAGE_PIN J5 [get_ports {seg_digit[0]}]
set_property PACKAGE_PIN M3 [get_ports {seg_digit[1]}]
set_property PACKAGE_PIN J6 [get_ports {seg_digit[2]}]
set_property PACKAGE_PIN H5 [get_ports {seg_digit[3]}]
set_property PACKAGE_PIN G4 [get_ports {seg_digit[4]}]
set_property PACKAGE_PIN K6 [get_ports {seg_digit[5]}]
set_property PACKAGE_PIN K3 [get_ports {seg_digit[6]}]
set_property PACKAGE_PIN H4 [get_ports {seg_digit[7]}]
set_property IOSTANDARD LVCMOS33 [get_ports {seg_digit[*]}]

set_property PACKAGE_PIN M2 [get_ports {seg_select[0]}]
set_property PACKAGE_PIN N4 [get_ports {seg_select[1]}]
set_property PACKAGE_PIN L5 [get_ports {seg_select[2]}]
set_property PACKAGE_PIN L4 [get_ports {seg_select[3]}]
set_property PACKAGE_PIN M16 [get_ports {seg_select[4]}]
set_property PACKAGE_PIN M17 [get_ports {seg_select[5]}]
set_property IOSTANDARD LVCMOS33 [get_ports {seg_select[*]}]

# Every multi-clock control crossing in the frozen video RTL and the camera
# wrapper marks its synchronizer flops ASYNC_REG. Cut timing only to those
# synchronization stages; normal CPU and video datapaths remain constrained.
set async_cells [get_cells -hierarchical -filter {ASYNC_REG == TRUE}]
set async_data_pins [get_pins -of_objects $async_cells -filter {REF_PIN_NAME == D}]
set_false_path -to $async_data_pins
