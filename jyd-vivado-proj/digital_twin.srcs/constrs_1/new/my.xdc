## dcache 层级约束
#set_property KEEP_HIERARCHY yes [get_cells -hier *dcache*]
#set_property CELL_BLOAT_FACTOR MEDIUM [get_cells -hier *dcache*]

## Pblock 也可以放 XDC
#create_pblock p_dcache_mid
#add_cells_to_pblock [get_pblocks p_dcache_mid] [get_cells -hier *dcache*]
#resize_pblock [get_pblocks p_dcache_mid] -add {SLICE_X0Y0:SLICE_X79Y140}
#set_property IS_SOFT true [get_pblocks p_dcache_mid]

# CPU -> twin_controller 50MHz: 状态显示/LED/SEG 被低速域采样
set_false_path \
-from [get_cells -hier -regexp {.*student_top_inst/mytop/(segReg|ledReg)/dataReg_reg.*}] \
-to   [get_cells -hier -regexp {.*twin_controller_inst/status_buffer_reg.*}]

# 50MHz counter -> CPU: 灰码总线进入第一级同步寄存器
set_false_path \
-from [get_cells -hier {student_top_inst/mytop/cnt/counter/cnt_ms_bin_reg[*]}] \
-to   [get_cells -hier {student_top_inst/mytop/cnt/counter/cnt_gray_cpu_d1_reg[*]}]

# CPU -> 50MHz counter: enable 进入第一级同步寄存器
set_false_path \
-from [get_cells -hier *cnt_enable_cpu*] \
-to   [get_cells -hier {student_top_inst/mytop/cnt/counter/cnt_enable_cnt_d1_reg}]

# 50MHz counter -> CPU: routed design may replicate cnt_ms_bin source flops
set_false_path \
-from [get_cells -hier -regexp {.*student_top_inst/mytop/cnt/counter/cnt_ms_bin_reg\[[0-9]+\](_replica)?$}] \
-to   [get_cells -hier -regexp {.*student_top_inst/mytop/cnt/counter/cnt_gray_cpu_d1_reg\[[0-9]+\]$}]

# CPU -> 50MHz counter: implementation source register for cnt_enable_cpu
set_false_path \
-from [get_cells -hier -regexp {.*student_top_inst/mytop/perip/cntEnableReg_reg$}] \
-to   [get_cells -hier -regexp {.*student_top_inst/mytop/cnt/counter/cnt_enable_cnt_d1_reg$}]
