# Reconfigure clk_out2 of the digital_twin clock-wizard IP without changing
# the other requested outputs.  Vivado chooses a legal MMCM VCO/multiplier and
# output-divider combination for the selected 7-series device.
#
# Usage:
#   vivado -mode batch -source scripts/configure-cpu-clock.tcl -tclargs 270
#   vivado -mode batch -source scripts/configure-cpu-clock.tcl -tclargs 265

set script_dir [file dirname [file normalize [info script]]]
set project_dir [file normalize [file join $script_dir ..]]
set project_file [file join $project_dir digital_twin.xpr]

if {[llength $argv] > 1} {
  error "usage: configure-cpu-clock.tcl ?frequency_MHz?"
}
set requested_mhz 270.0
if {[llength $argv] == 1} {
  set requested_mhz [lindex $argv 0]
}
if {![string is double -strict $requested_mhz]} {
  error "CPU clock must be a numeric frequency in MHz, got: $requested_mhz"
}
if {$requested_mhz < 250.0 || $requested_mhz > 280.0} {
  error "CPU clock must remain in the supported experiment range 250..280 MHz"
}

open_project $project_file
set pll [get_ips -quiet mypll]
if {[llength $pll] != 1} {
  error "expected exactly one mypll IP, found [llength $pll]"
}

set output1_before [get_property CONFIG.CLKOUT1_REQUESTED_OUT_FREQ $pll]
set input_before [get_property CONFIG.PRIM_IN_FREQ $pll]
puts "mypll before: input=${input_before}MHz clk_out1=${output1_before}MHz clk_out2=[get_property CONFIG.CLKOUT2_REQUESTED_OUT_FREQ $pll]MHz"

set_property -dict [list CONFIG.CLKOUT2_REQUESTED_OUT_FREQ $requested_mhz] $pll

# Remove every generated product before regeneration so stale XDC, HDL, and
# DCP files from the previous frequency cannot be reused accidentally.
reset_target all $pll
generate_target all $pll
export_ip_user_files -of_objects $pll -no_script -sync -force -quiet
create_ip_run $pll

# The top-level project consumes the clock wizard's OOC checkpoint.  Merely
# regenerating HDL/XDC leaves an old mypll_synth_1 DCP available, so rebuild
# that short IP run here.  This does not launch top-level synthesis or impl.
set ip_run [get_runs -quiet mypll_synth_1]
if {[llength $ip_run] != 1} {
  error "expected exactly one mypll_synth_1 run after create_ip_run"
}
reset_run $ip_run
launch_runs $ip_run -jobs 4
wait_on_run $ip_run
if {[get_property PROGRESS $ip_run] ne "100%" ||
    [string match -nocase {*failed*} [get_property STATUS $ip_run]]} {
  error "mypll OOC synthesis failed: [get_property STATUS $ip_run]"
}

set output1_after [get_property CONFIG.CLKOUT1_REQUESTED_OUT_FREQ $pll]
if {$output1_after ne $output1_before} {
  error "clk_out1 request changed unexpectedly: $output1_before -> $output1_after MHz"
}

set actual1 [get_property CONFIG.C_CLKOUT1_OUT_FREQ $pll]
set actual2 [get_property CONFIG.C_CLKOUT2_OUT_FREQ $pll]
set divclk [get_property CONFIG.C_MMCM_DIVCLK_DIVIDE $pll]
set mult [get_property CONFIG.C_MMCM_CLKFBOUT_MULT_F $pll]
set out0_div [get_property CONFIG.C_MMCM_CLKOUT0_DIVIDE_F $pll]
set out1_div [get_property CONFIG.C_MMCM_CLKOUT1_DIVIDE $pll]
set vco_mhz [expr {double($input_before) * double($mult) / double($divclk)}]

puts "mypll after: requested clk_out1=${output1_after}MHz clk_out2=${requested_mhz}MHz"
puts "mypll actual: clk_out1=${actual1}MHz clk_out2=${actual2}MHz"
puts "mypll MMCM: DIVCLK=${divclk} CLKFBOUT_MULT_F=${mult} VCO=${vco_mhz}MHz CLKOUT0_DIVIDE_F=${out0_div} CLKOUT1_DIVIDE=${out1_div}"

if {abs(double($actual2) - double($requested_mhz)) > 0.001} {
  error "Vivado could not realize requested clk_out2 within 1 kHz: requested ${requested_mhz}MHz, actual ${actual2}MHz"
}
if {abs(double($actual1) - double($output1_before)) > 0.001} {
  error "Vivado changed clk_out1 actual frequency: requested ${output1_before}MHz, actual ${actual1}MHz"
}

set generated_xdc [file join $project_dir digital_twin.gen sources_1 ip mypll mypll.xdc]
if {![file exists $generated_xdc]} {
  error "clock-wizard generated XDC is missing: $generated_xdc"
}
puts "Generated clock constraints refreshed: $generated_xdc"
puts "Clock-wizard OOC checkpoint refreshed: [file join $project_dir digital_twin.runs mypll_synth_1 mypll.dcp]"
puts "The clock-wizard XDC derives clk_out2 from the regenerated MMCM parameters; no hand-written CPU period constraint is required."
close_project
