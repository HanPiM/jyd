set_property CFGBVS VCCO [current_design]
set_property CONFIG_VOLTAGE 3.3 [current_design]
set_property BITSTREAM.CONFIG.SPI_BUSWIDTH 4 [current_design]
set_property CONFIG_MODE SPIx4 [current_design]
set_property BITSTREAM.CONFIG.CONFIGRATE 50 [current_design]

create_clock -name sys_clk -period 20.000 [get_ports sys_clk]
set_property PACKAGE_PIN Y18 [get_ports sys_clk]
set_property IOSTANDARD LVCMOS33 [get_ports sys_clk]

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

# The AHT10 module supplies the pull-ups.  No internal pull-up and no
# push-pull high drive are used by the top-level RTL.
