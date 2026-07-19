if {$argc != 3} {
  error "usage: generate_ips.tcl <repo-root> <work-dir> <coe-dir>"
}

set repo_root [file normalize [lindex $argv 0]]
set work_dir [file normalize [lindex $argv 1]]
set coe_dir [file normalize [lindex $argv 2]]
set ip_root "$repo_root/jyd-vivado-proj/digital_twin.srcs/sources_1/ip"

create_project -force xsim_seg37_ips "$work_dir/ip-project" -part xc7k325tffg900-2

set ip_names [list \
  blk_mem_gen_2KB \
  blk_mem_gen_dram \
  blk_mem_gen_irom \
  dist_mem_gen_32x32 \
  dist_mem_gen_512x8 \
  div_gen_uradix2 \
  mult_gen_0 \
  mult_gen_mul32_fast]

set copied_sources "$work_dir/digital_twin.srcs/sources_1"
file mkdir "$copied_sources/ip"
file mkdir "$copied_sources/imports/cur_coe"
file copy -force "$coe_dir/irom.coe" "$copied_sources/imports/cur_coe/irom.coe"
file copy -force "$coe_dir/dram.coe" "$copied_sources/imports/cur_coe/dram.coe"
foreach ip_name $ip_names {
  set source_xci "$ip_root/$ip_name/$ip_name.xci"
  if {![file exists $source_xci]} {
    error "missing IP configuration: $source_xci"
  }
  file mkdir "$copied_sources/ip/$ip_name"
  set copied_xci "$copied_sources/ip/$ip_name/$ip_name.xci"
  file copy -force $source_xci $copied_xci
  read_ip $copied_xci
}

set divider_ip [get_ips div_gen_uradix2]
puts "SEG37_DIV_XCI clocks_per_division=[get_property CONFIG.clocks_per_division $divider_ip] latency=[get_property CONFIG.latency $divider_ip] flow_control=[get_property CONFIG.FlowControl $divider_ip]"

generate_target simulation [get_ips]
export_ip_user_files -of_objects [get_ips] -no_script -sync -force -quiet

foreach ip_obj [get_ips] {
  puts "SEG37_IP name=[get_property NAME $ip_obj] xci=[get_property IP_FILE $ip_obj]"
}
close_project
