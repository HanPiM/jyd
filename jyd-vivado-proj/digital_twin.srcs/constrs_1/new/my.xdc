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

# UART TX/RX crossings are implemented and constrained by FIFO Generator IP.
# Keep the ID/EX operand banks near their naturally clustered placement.  This
# is intentionally soft so implementation can move registers when congestion
# or other critical paths require it.
create_pblock p_exu_operands
add_cells_to_pblock [get_pblocks p_exu_operands] \
  [get_cells -quiet -hier -regexp {.*payloadReg_4_info_reg[12]_reg\[[0-9]+\]$}]
resize_pblock [get_pblocks p_exu_operands] -add {SLICE_X98Y98:SLICE_X119Y119}
set_property IS_SOFT true [get_pblocks p_exu_operands]

# Keep the branch prediction lookup and its IFU response mailbox in the west
# side region they naturally occupy. This avoids the observed X102 -> X85 ->
# X106 redirect/prediction detour without constraining the rest of the IFU.
create_pblock p_ifu_prediction
add_cells_to_pblock [get_pblocks p_ifu_prediction] \
  [get_cells -quiet -hier -regexp {.*student_top_inst/mytop/cpu/(btb|bp)/.*|.*student_top_inst/mytop/cpu/ifu/predNextReg_.*}]
resize_pblock [get_pblocks p_ifu_prediction] -add {SLICE_X82Y100:SLICE_X108Y118}
set_property IS_SOFT true [get_pblocks p_ifu_prediction]
