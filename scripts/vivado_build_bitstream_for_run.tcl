proc fail {msg} {
  puts stderr $msg
  exit 1
}

if {$argc != 3} {
  fail "usage: vivado_build_bitstream_for_run.tcl <project_path> <run_name> <bit_output_path>"
}

set project_path [file normalize [lindex $argv 0]]
set run_name [lindex $argv 1]
set bit_output_path [file normalize [lindex $argv 2]]

if {![file exists $project_path]} {
  fail "project not found: $project_path"
}

proc reset_run_safe {run_name} {
  set run_obj [get_runs -quiet $run_name]
  if {[llength $run_obj] == 0} {
    puts "reset_runs $run_name skipped: run not found"
    return
  }
  if {[catch {reset_runs $run_name} reset_err]} {
    puts "reset_runs $run_name skipped: $reset_err"
  }
}

proc wait_for_run_complete {run_name} {
  puts "Waiting for $run_name to finish"
  if {[catch {wait_on_run $run_name} wait_err]} {
    fail "wait_on_run $run_name failed: $wait_err"
  }
  set final_status [get_property STATUS [get_runs $run_name]]
  puts "Final $run_name status: $final_status"
  if {![string match "*Complete*" $final_status]} {
    fail "$run_name did not complete successfully: $final_status"
  }
}

puts "Opening project: $project_path"
if {[catch {open_project $project_path} open_err]} {
  fail "open_project failed: $open_err"
}

set run_obj [get_runs -quiet $run_name]
if {[llength $run_obj] == 0} {
  fail "run '$run_name' not found in project"
}

set mem_ips [list]
foreach ip_name {blk_mem_gen_irom blk_mem_gen_dram} {
  set ip_obj [get_ips -quiet $ip_name]
  if {[llength $ip_obj] == 0} {
    fail "IP '$ip_name' not found"
  }
  lappend mem_ips [lindex $ip_obj 0]
}

puts "Regenerating memory IP targets"
if {[catch {generate_target all $mem_ips} gen_err]} {
  fail "generate_target failed: $gen_err"
}

foreach ip_name {blk_mem_gen_irom blk_mem_gen_dram} {
  reset_run_safe "${ip_name}_synth_1"
}

reset_run_safe synth_1
reset_run_safe $run_name

puts "Launching $run_name through write_bitstream"
if {[catch {launch_runs $run_name -to_step write_bitstream} launch_err]} {
  fail "launch_runs $run_name failed: $launch_err"
}
wait_for_run_complete $run_name

set project_dir [file dirname $project_path]
set bit_dir [file join $project_dir digital_twin.runs $run_name]
set bit_candidates [glob -nocomplain -directory $bit_dir *.bit]
if {[llength $bit_candidates] == 0} {
  fail "no bitstream found under: $bit_dir"
}
if {[llength $bit_candidates] > 1} {
  puts "multiple bitstreams found under $bit_dir; using first sorted candidate"
}
set bit_candidates [lsort $bit_candidates]
set bit_file [lindex $bit_candidates 0]

file mkdir [file dirname $bit_output_path]
puts "Copying $bit_file to $bit_output_path"
if {[catch {file copy -force $bit_file $bit_output_path} copy_err]} {
  fail "copy bitstream failed: $copy_err"
}

close_project
exit 0
