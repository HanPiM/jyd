if {$argc != 2} { error "Expected: <input-dir> <jobs>" }
set input_dir [file normalize [lindex $argv 0]]
set jobs [lindex $argv 1]
set script_dir [file dirname [file normalize [info script]]]
set project_dir [file normalize [file join $script_dir ..]]
set repo_root [file normalize [file join $project_dir ..]]
set pack_dir [file join $repo_root npc build pack-fpga]
set board_rtl [file join $repo_root board ax7035b rtl]
set rtl_dir [file join $project_dir rtl]
set video_dir [file join $project_dir video]
set build_dir [file join $project_dir build]
set output_dir [file join $project_dir output]

foreach required [list "$input_dir/irom.mem" "$input_dir/dram.mem" $pack_dir $board_rtl $rtl_dir $video_dir] {
  if {![file exists $required]} { error "Required input does not exist: $required" }
}
file mkdir $build_dir
file mkdir $output_dir
set_param general.maxThreads $jobs

create_project -force ax7035b_jyd_ov5640_soc [file join $build_dir project] -part xc7a35tfgg484-2
set_property target_language Verilog [current_project]
set_property simulator_language Mixed [current_project]

foreach subdir {cpu fpgawrap} {
  foreach pattern {*.v *.sv} {
    set files [glob -nocomplain -directory "$pack_dir/$subdir" $pattern]
    if {[llength $files] > 0} { add_files -fileset sources_1 -norecurse $files }
  }
}

set board_files [list \
  [file join $board_rtl ax7035b_uart.sv] \
  [file join $board_rtl ax7035b_seg_scan.sv]]
set integration_files [list \
  [file join $rtl_dir ax7035b_camera_clock_gen.sv] \
  [file join $rtl_dir ax7035b_camera_ip_compat.sv] \
  [file join $rtl_dir camera_monitor_cdc.sv] \
  [file join $rtl_dir top_ax7035b_camera_soc.sv]]
set video_sv_files [list \
  [file join $video_dir rtl video_clock_gen.sv] \
  [file join $video_dir rtl camera_clock_gen.sv] \
  [file join $video_dir rtl sccb_write.sv] \
  [file join $video_dir rtl ov5640_reg_rom.sv] \
  [file join $video_dir rtl ov5640_config.sv] \
  [file join $video_dir rtl ov5640_capture_downsample.sv] \
  [file join $video_dir rtl framebuffer_pingpong_rgb332.sv] \
  [file join $video_dir rtl video_timing_640x480.sv] \
  [file join $video_dir rtl top.sv]]
set video_vhdl_files [list \
  [file join $video_dir third_party rgb2dvi DVI_Constants.vhd] \
  [file join $video_dir third_party rgb2dvi SyncAsync.vhd] \
  [file join $video_dir third_party rgb2dvi SyncAsyncReset.vhd] \
  [file join $video_dir third_party rgb2dvi TMDS_Encoder.vhd] \
  [file join $video_dir third_party rgb2dvi OutputSERDES.vhd] \
  [file join $video_dir third_party rgb2dvi ClockGen.vhd] \
  [file join $video_dir third_party rgb2dvi rgb2dvi.vhd] \
  [file join $video_dir rtl dvi_out_wrapper.vhd]]

add_files -fileset sources_1 -norecurse $board_files
add_files -fileset sources_1 -norecurse $integration_files
add_files -fileset sources_1 -norecurse $video_sv_files
add_files -fileset sources_1 -norecurse $video_vhdl_files
add_files -fileset sources_1 -norecurse [list "$input_dir/irom.mem" "$input_dir/dram.mem"]
add_files -fileset constrs_1 -norecurse [file join $project_dir constraints ax7035b_camera_soc.xdc]
foreach f [concat $board_files $integration_files $video_sv_files] {
  set_property file_type SystemVerilog [get_files $f]
}
set_property top top_ax7035b_camera_soc [current_fileset]
update_compile_order -fileset sources_1

puts "CAMERA_SOC_SYNTH_BEGIN part=xc7a35tfgg484-2"
synth_design -top top_ax7035b_camera_soc -part xc7a35tfgg484-2
report_utilization -hierarchical -hierarchical_depth 6 -file [file join $output_dir utilization_synth.rpt]
report_timing_summary -file [file join $output_dir timing_synth.rpt]
write_checkpoint -force [file join $build_dir synth.dcp]

puts "CAMERA_SOC_IMPL_BEGIN"
opt_design
place_design
phys_opt_design
route_design
report_utilization -hierarchical -hierarchical_depth 6 -file [file join $output_dir utilization_impl.rpt]
report_timing_summary -file [file join $output_dir timing_summary.rpt]
report_drc -file [file join $output_dir drc.rpt]
report_methodology -file [file join $output_dir methodology.rpt]
report_route_status -file [file join $output_dir route_status.rpt]
write_checkpoint -force [file join $build_dir routed.dcp]

puts "CAMERA_SOC_BITSTREAM_BEGIN"
write_bitstream -force [file join $output_dir ax7035b_jyd_ov5640_soc.bit]
puts "CAMERA_SOC_BUILD_COMPLETE bit=[file join $output_dir ax7035b_jyd_ov5640_soc.bit]"
close_project
