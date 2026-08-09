if {$argc != 3} { error "Expected: <image-name> <input-dir> <jobs>" }
set image_name [lindex $argv 0]
set input_dir [file normalize [lindex $argv 1]]
set jobs [lindex $argv 2]
set script_dir [file dirname [file normalize [info script]]]
set project_dir [file normalize [file join $script_dir ..]]
set repo_root [file normalize [file join $project_dir ..]]
set board_dir [file join $repo_root board ax7035b]
set pack_dir [file join $repo_root npc build pack-fpga]
set build_dir [file join $project_dir build $image_name]
set report_dir [file join $project_dir reports $image_name]
set bit_dir [file join $project_dir bitstreams]

foreach required [list "$input_dir/irom.mem" "$input_dir/dram.mem" $pack_dir $board_dir] {
  if {![file exists $required]} { error "Required input does not exist: $required" }
}
file mkdir $build_dir
file mkdir $report_dir
file mkdir $bit_dir
set_param general.maxThreads $jobs

create_project -force "ax7035b_${image_name}" [file join $build_dir project] -part xc7a35tfgg484-2
set_property target_language Verilog [current_project]
set_property simulator_language Mixed [current_project]

foreach subdir {cpu fpgawrap} {
  foreach pattern {*.v *.sv} {
    set files [glob -nocomplain -directory "$pack_dir/$subdir" $pattern]
    if {[llength $files] > 0} { add_files -fileset sources_1 -norecurse $files }
  }
}
add_files -fileset sources_1 -norecurse [glob -directory "$board_dir/rtl" *.sv]
add_files -fileset sources_1 -norecurse [list "$input_dir/irom.mem" "$input_dir/dram.mem"]
add_files -fileset constrs_1 -norecurse "$board_dir/constr/ax7035b.xdc"
set_property top top_ax7035b [current_fileset]
update_compile_order -fileset sources_1

puts "AX7035B_SYNTH_BEGIN image=$image_name part=xc7a35tfgg484-2"
synth_design -top top_ax7035b -part xc7a35tfgg484-2
report_utilization -hierarchical -hierarchical_depth 5 -file "$report_dir/utilization_synth.rpt"
report_timing_summary -file "$report_dir/timing_synth.rpt"
write_checkpoint -force "$build_dir/synth.dcp"

puts "AX7035B_IMPL_BEGIN"
opt_design
place_design
phys_opt_design
route_design
report_utilization -hierarchical -hierarchical_depth 5 -file "$report_dir/utilization_routed.rpt"
report_timing_summary -file "$report_dir/timing_routed.rpt"
report_drc -file "$report_dir/drc_routed.rpt"
report_methodology -file "$report_dir/methodology_routed.rpt"
report_route_status -file "$report_dir/route_status.rpt"
report_io -file "$report_dir/io.rpt"
write_checkpoint -force "$build_dir/routed.dcp"

puts "AX7035B_BITSTREAM_BEGIN"
write_bitstream -force "$bit_dir/${image_name}.bit"
puts "AX7035B_BUILD_COMPLETE image=$image_name bit=$bit_dir/${image_name}.bit"
close_project
