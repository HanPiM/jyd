# Extracted from E:\FPGA\AX7035B_AHT10_DEMO\work\constr\aht10_demo.xdc
# AHT10 on J10: pin 3=P17 SCL, pin 4=N17 SDA.  Module pull-ups are required.
set_property PACKAGE_PIN P17 [get_ports aht10_scl]
set_property IOSTANDARD LVCMOS33 [get_ports aht10_scl]
set_property PACKAGE_PIN N17 [get_ports aht10_sda]
set_property IOSTANDARD LVCMOS33 [get_ports aht10_sda]
