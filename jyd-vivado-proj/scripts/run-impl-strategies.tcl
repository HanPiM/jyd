if {$argc == 0} {
  error "usage: vivado -mode batch -source run-impl-strategies.tcl -tclargs <run>..."
}

open_project digital_twin.xpr
set_param general.maxThreads 32

foreach run_name $argv {
  set run_obj [get_runs $run_name]
  if {[llength $run_obj] == 0} {
    error "implementation run not found: $run_name"
  }
  if {[get_property IS_SYNTHESIS $run_obj]} {
    error "refusing to launch synthesis run: $run_name"
  }
  puts "Launching $run_name with strategy [get_property STRATEGY $run_obj]"
  reset_run $run_name
  launch_runs $run_name -jobs 32
  wait_on_run $run_name
  puts "$run_name STATUS: [get_property STATUS $run_obj]"
}

close_project
