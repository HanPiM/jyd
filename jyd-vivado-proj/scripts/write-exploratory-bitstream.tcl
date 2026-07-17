set script_dir [file dirname [file normalize [info script]]]
set project_dir [file normalize [file join $script_dir ..]]
open_project [file join $project_dir digital_twin.xpr]
open_run impl_1

set worst_path [get_timing_paths -delay_type max -max_paths 1]
if {[llength $worst_path] != 1} {
  error "could not obtain the worst setup path"
}
set wns [get_property SLACK $worst_path]
puts "Current routed WNS: ${wns} ns"
if {$wns <= -0.100 || $wns >= 0.000} {
  error "exploratory bitstream requires -0.100 ns < WNS < 0.000 ns"
}

set output_file [file join $project_dir digital_twin.runs impl_1 top.bit]
write_bitstream -force $output_file
puts "Exploratory bitstream written: $output_file"
close_project
