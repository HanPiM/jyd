if {$argc != 1} {
  error "Usage: configure-dcache-ips.tcl <words>"
}

set words [lindex $argv 0]
if {$words ni {512 1024 2048 4096 8192}} {
  error "DCache words must be one of 512, 1024, 2048, 4096, 8192: $words"
}

set addr_width [expr {int(log($words) / log(2))}]
set tag_width [expr {16 - $addr_width}]
set tag_entry_width [expr {$tag_width + 1}]
set tag_depth $words
set project_dir [file normalize [file join [file dirname [info script]] ..]]

open_project [file join $project_dir digital_twin.xpr]

set data_name blk_mem_gen_dcache_data
if {[llength [get_ips -quiet $data_name]] == 0} {
  create_ip -name blk_mem_gen -vendor xilinx.com -library ip -version 8.4 \
    -module_name $data_name -dir [file join $project_dir digital_twin.srcs sources_1 ip]
}
set_property -dict [list \
  CONFIG.Memory_Type {Simple_Dual_Port_RAM} \
  CONFIG.Use_Byte_Write_Enable {true} \
  CONFIG.Byte_Size {8} \
  CONFIG.Write_Width_A {32} \
  CONFIG.Write_Depth_A $words \
  CONFIG.Write_Width_B {32} \
  CONFIG.Read_Width_B {32} \
  CONFIG.Enable_A {Use_ENA_Pin} \
  CONFIG.Enable_B {Use_ENB_Pin} \
  CONFIG.Assume_Synchronous_Clk {true} \
  CONFIG.Operating_Mode_A {READ_FIRST} \
] [get_ips $data_name]

set tag_name blk_mem_gen_dcache_tag
if {[llength [get_ips -quiet $tag_name]] == 0} {
  create_ip -name blk_mem_gen -vendor xilinx.com -library ip -version 8.4 \
    -module_name $tag_name -dir [file join $project_dir digital_twin.srcs sources_1 ip]
}
set_property -dict [list \
  CONFIG.Memory_Type {Simple_Dual_Port_RAM} \
  CONFIG.Use_Byte_Write_Enable {false} \
  CONFIG.Write_Width_A $tag_entry_width \
  CONFIG.Write_Depth_A $tag_depth \
  CONFIG.Write_Width_B $tag_entry_width \
  CONFIG.Read_Width_B $tag_entry_width \
  CONFIG.Enable_A {Use_ENA_Pin} \
  CONFIG.Enable_B {Use_ENB_Pin} \
  CONFIG.Assume_Synchronous_Clk {true} \
  CONFIG.Operating_Mode_A {READ_FIRST} \
  CONFIG.Register_PortB_Output_of_Memory_Primitives {false} \
  CONFIG.Register_PortB_Output_of_Memory_Core {false} \
  CONFIG.Fill_Remaining_Memory_Locations {true} \
  CONFIG.Remaining_Memory_Locations {0} \
] [get_ips $tag_name]

foreach ip [list $data_name $tag_name] {
  reset_target all [get_ips $ip]
  generate_target all [get_ips $ip]
  export_ip_user_files -of_objects [get_ips $ip] -no_script -sync -force -quiet
  create_ip_run [get_ips $ip]
}

foreach run_name [list ${data_name}_synth_1 ${tag_name}_synth_1] {
  set ip_run [get_runs -quiet $run_name]
  if {[llength $ip_run] != 1} {
    error "expected exactly one OOC run: $run_name"
  }
  reset_run $ip_run
  launch_runs $ip_run -jobs 4
  wait_on_run $ip_run
  if {[get_property PROGRESS $ip_run] ne "100%" ||
      [string match -nocase {*failed*} [get_property STATUS $ip_run]]} {
    error "$run_name failed: [get_property STATUS $ip_run]"
  }
}

update_compile_order -fileset sources_1
puts "Configured DCache IPs: words=$words addr_width=$addr_width tag_depth=$tag_depth tag_entry_width=$tag_entry_width"
close_project
