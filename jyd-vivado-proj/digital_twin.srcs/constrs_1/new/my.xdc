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

# UART TX/RX crossings are implemented and constrained by FIFO Generator IP.
