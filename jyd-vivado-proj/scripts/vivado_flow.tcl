proc fail {msg} {
  puts stderr $msg
  exit 1
}

if {$argc != 4} {
  fail "usage: vivado_flow.tcl <synth|impl|bitstream> <project_path> <pre_hook> <report_dir>"
}

set mode [lindex $argv 0]
set project_path [file normalize [lindex $argv 1]]
set pre_hook [file normalize [lindex $argv 2]]
set report_dir [file normalize [lindex $argv 3]]

if {$mode ne "synth" && $mode ne "impl" && $mode ne "bitstream"} {
  fail "unsupported mode '$mode', expected 'synth', 'impl', or 'bitstream'"
}

foreach required [list $project_path $pre_hook] {
  if {![file exists $required]} {
    fail "required file not found: $required"
  }
}

proc configure_run_pre_hooks {run_name pre_hook step_names} {
  set run_obj [get_runs $run_name]
  if {[llength $run_obj] == 0} {
    fail "run '$run_name' not found in project"
  }
  foreach step_name $step_names {
    set prop_name "STEPS.${step_name}.TCL.PRE"
    if {[catch {set_property $prop_name $pre_hook [lindex $run_obj 0]} hook_err]} {
      puts "skip setting $prop_name on $run_name: $hook_err"
    }
  }
}

proc reset_run_safe {run_name} {
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

puts "Regenerating IP targets"
set mem_ips [concat [get_ips blk_mem_gen_irom] [get_ips blk_mem_gen_dram]]
if {[catch {generate_target all $mem_ips} gen_err]} {
  fail "generate_target failed: $gen_err"
}

configure_run_pre_hooks synth_1 $pre_hook {SYNTH_DESIGN}
configure_run_pre_hooks impl_1 $pre_hook {INIT_DESIGN OPT_DESIGN PLACE_DESIGN PHYS_OPT_DESIGN ROUTE_DESIGN POST_ROUTE_PHYS_OPT_DESIGN WRITE_BITSTREAM}

file mkdir $report_dir

if {$mode eq "synth"} {
  reset_run_safe synth_1
  puts "Launching synth_1"
  if {[catch {launch_runs synth_1} launch_err]} {
    fail "launch_runs synth_1 failed: $launch_err"
  }
  wait_for_run_complete synth_1
  exit 0
}

reset_run_safe impl_1
reset_run_safe synth_1

if {$mode eq "impl"} {
  puts "Launching impl_1 to route_design"
  if {[catch {launch_runs impl_1 -to_step route_design} launch_err]} {
    fail "launch_runs impl_1 failed: $launch_err"
  }
} else {
  puts "Launching impl_1 through write_bitstream"
  if {[catch {launch_runs impl_1 -to_step write_bitstream} launch_err]} {
    fail "launch_runs impl_1 failed: $launch_err"
  }
}

wait_for_run_complete impl_1

puts "Opening implementation run impl_1"
if {[catch {open_run impl_1} open_err]} {
  fail "open_run impl_1 failed: $open_err"
}

set rpt_file [file join $report_dir "top_timing_summary_routed.rpt"]
set pb_file [file join $report_dir "top_timing_summary_routed.pb"]
set rpx_file [file join $report_dir "top_timing_summary_routed.rpx"]

puts "Writing routed timing summary to $rpt_file"
if {[catch {
  report_timing_summary \
    -max_paths 10 \
    -report_unconstrained \
    -file $rpt_file \
    -pb $pb_file \
    -rpx $rpx_file \
    -warn_on_violation
} report_err]} {
  fail "report_timing_summary failed: $report_err"
}

set project_dir [file dirname $project_path]
set bit_candidates [glob -nocomplain -directory [file join $project_dir digital_twin.runs impl_1] *.bit]
foreach bit_file $bit_candidates {
  file copy -force $bit_file [file join $report_dir [file tail $bit_file]]
}

# open_run impl_1 reports the routed checkpoint, which is intentionally kept
# as the pre-post-route reference.  When post-route physopt ran, report the
# final checkpoint separately so CI and local inspection do not mistake the
# pre-physopt WNS for the accepted result.
set postroute_checkpoint [file join $project_dir digital_twin.runs impl_1 top_postroute_physopt.dcp]
if {[file exists $postroute_checkpoint]} {
  close_design
  puts "Opening post-route physopt checkpoint: $postroute_checkpoint"
  if {[catch {open_checkpoint $postroute_checkpoint} postroute_open_err]} {
    fail "open_checkpoint post-route physopt checkpoint failed: $postroute_open_err"
  }

  set postroute_rpt_file [file join $report_dir "top_timing_summary_postroute_physopted.rpt"]
  set postroute_pb_file [file join $report_dir "top_timing_summary_postroute_physopted.pb"]
  set postroute_rpx_file [file join $report_dir "top_timing_summary_postroute_physopted.rpx"]
  puts "Writing post-route physopt timing summary to $postroute_rpt_file"
  if {[catch {
    report_timing_summary \
      -max_paths 10 \
      -report_unconstrained \
      -file $postroute_rpt_file \
      -pb $postroute_pb_file \
      -rpx $postroute_rpx_file \
      -warn_on_violation
  } postroute_report_err]} {
    fail "post-route physopt report_timing_summary failed: $postroute_report_err"
  }
}

exit 0
