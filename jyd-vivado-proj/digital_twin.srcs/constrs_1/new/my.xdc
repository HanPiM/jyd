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

# Cut only the Gray-pointer transfers into the first synchronizer stages. Keep
# FIFO-local synchronous paths and unrelated CPU/50 MHz crossings timed.
set_false_path \
  -from [get_cells -hier -regexp {.*uart_subsystem_inst/tx_fifo/wr_gray_reg\[[0-9]+\]$}] \
  -to   [get_cells -hier -regexp {.*uart_subsystem_inst/tx_fifo/wr_gray_rd_sync1_reg\[[0-9]+\]$}]
set_false_path \
  -from [get_cells -hier -regexp {.*uart_subsystem_inst/tx_fifo/rd_gray_reg\[[0-9]+\]$}] \
  -to   [get_cells -hier -regexp {.*uart_subsystem_inst/tx_fifo/rd_gray_wr_sync1_reg\[[0-9]+\]$}]
set_false_path \
  -from [get_cells -hier -regexp {.*uart_subsystem_inst/rx_fifo/wr_gray_reg\[[0-9]+\]$}] \
  -to   [get_cells -hier -regexp {.*uart_subsystem_inst/rx_fifo/wr_gray_rd_sync1_reg\[[0-9]+\]$}]
set_false_path \
  -from [get_cells -hier -regexp {.*uart_subsystem_inst/rx_fifo/rd_gray_reg\[[0-9]+\]$}] \
  -to   [get_cells -hier -regexp {.*uart_subsystem_inst/rx_fifo/rd_gray_wr_sync1_reg\[[0-9]+\]$}]

set_property ASYNC_REG TRUE [get_cells -hier -regexp \
  {.*uart_subsystem_inst/tx_fifo/(wr_gray_rd|rd_gray_wr)_sync[12]_reg\[[0-9]+\]$}]
set_property ASYNC_REG TRUE [get_cells -hier -regexp \
  {.*uart_subsystem_inst/rx_fifo/(wr_gray_rd|rd_gray_wr)_sync[12]_reg\[[0-9]+\]$}]
# Keep the ID/EX operand banks near their naturally clustered placement.  This
# is intentionally soft so implementation can move registers when congestion
# or other critical paths require it.
create_pblock p_exu_operands
add_cells_to_pblock [get_pblocks p_exu_operands] \
  [get_cells -quiet -hier -regexp {.*payloadReg_4_info_reg[12]_reg\[[0-9]+\]$}]
resize_pblock [get_pblocks p_exu_operands] -add {SLICE_X98Y98:SLICE_X119Y119}
set_property IS_SOFT true [get_pblocks p_exu_operands]
