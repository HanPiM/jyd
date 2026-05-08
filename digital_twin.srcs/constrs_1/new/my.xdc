## dcache 层级约束
#set_property KEEP_HIERARCHY yes [get_cells -hier *dcache*]
#set_property CELL_BLOAT_FACTOR MEDIUM [get_cells -hier *dcache*]

## Pblock 也可以放 XDC
#create_pblock p_dcache_mid
#add_cells_to_pblock [get_pblocks p_dcache_mid] [get_cells -hier *dcache*]
#resize_pblock [get_pblocks p_dcache_mid] -add {SLICE_X0Y0:SLICE_X79Y140}
#set_property IS_SOFT true [get_pblocks p_dcache_mid]