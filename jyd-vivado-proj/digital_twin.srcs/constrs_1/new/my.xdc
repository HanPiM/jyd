## dcache 层级约束
#set_property KEEP_HIERARCHY yes [get_cells -hier *dcache*]
#set_property CELL_BLOAT_FACTOR MEDIUM [get_cells -hier *dcache*]

## Pblock 也可以放 XDC
#create_pblock p_dcache_mid
#add_cells_to_pblock [get_pblocks p_dcache_mid] [get_cells -hier *dcache*]
#resize_pblock [get_pblocks p_dcache_mid] -add {SLICE_X0Y0:SLICE_X79Y140}
#set_property IS_SOFT true [get_pblocks p_dcache_mid]

# CPU -> 50MHz tick counter: enable enters the first synchronizer stage.
set_false_path \
-from [get_cells -hier -regexp {.*student_top_inst/mytop/perip/cntEnableReg_reg$}] \
-to   [get_cells -hier -regexp {.*student_top_inst/mytop/cnt/counter/enableSync1_reg$}]

# 50MHz tick counter -> CPU: Gray bus enters the first synchronizer stage.
set_false_path \
-from [get_cells -hier -regexp {.*student_top_inst/mytop/cnt/counter/ticks_reg\[[0-9]+\](_replica)?$}] \
-to   [get_cells -hier -regexp {.*student_top_inst/mytop/cnt/counter/tickGraySync1_reg\[[0-9]+\]$}]

set_property ASYNC_REG TRUE [get_cells -hier -regexp \
  {.*student_top_inst/mytop/cnt/counter/(enableSync1|enableSync2)_reg$}]
set_property ASYNC_REG TRUE [get_cells -hier -regexp \
  {.*student_top_inst/mytop/cnt/counter/tickGraySync[12]_reg\[[0-9]+\]$}]

# The UART subsystem exchanges bytes solely through its two asynchronous FIFOs.
# Their Gray-pointer synchronizers (and the TX FIFO's dual-port RAM read) are
# intentional CPU/50 MHz CDC paths.  Do not mark the entire clocks asynchronous:
# the SEG output remains a separately reviewable crossing.
set_false_path \
  -from [get_cells -hier -regexp {.*uart_subsystem_inst/tx_fifo/.*}] \
  -to   [get_clocks clk_out1_mypll]
set_false_path \
  -from [get_cells -hier -regexp {.*uart_subsystem_inst/tx_fifo/.*}] \
  -to   [get_clocks clk_out2_mypll]
set_false_path \
  -from [get_cells -hier -regexp {.*uart_subsystem_inst/rx_fifo/.*}] \
  -to   [get_clocks clk_out2_mypll]
set_false_path \
  -from [get_cells -hier -regexp {.*uart_subsystem_inst/rx_fifo/.*}] \
  -to   [get_clocks clk_out1_mypll]
