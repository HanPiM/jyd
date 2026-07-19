#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Usage: run_digital_twin_vivado.sh [impl|write_bitstream|bitstream] [--jobs N] [--skip-pack] [--skip-vivado]

Build npc pack-fpga, replace the Vivado project's imported pack-fpga directory,
then run the digital_twin Vivado project to impl or write_bitstream.

Environment:
  VIVADO                Vivado executable to use. Defaults to "vivado".
  JOBS                  Vivado jobs and max threads. Defaults to nproc.
EOF
}

mode=impl
skip_pack=0
skip_vivado=0
jobs="${JOBS:-$(nproc 2>/dev/null || echo 4)}"

while [ "$#" -gt 0 ]; do
  case "$1" in
    impl)
      mode=impl
      shift
      ;;
    write_bitstream | bitstream)
      mode=write_bitstream
      shift
      ;;
    --jobs)
      if [ "$#" -lt 2 ]; then
        echo "Missing value for --jobs" >&2
        exit 2
      fi
      jobs="$2"
      shift 2
      ;;
    --skip-pack)
      skip_pack=1
      shift
      ;;
    --skip-vivado)
      skip_vivado=1
      shift
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if ! [[ "$jobs" =~ ^[1-9][0-9]*$ ]]; then
  echo "JOBS/--jobs must be a positive integer: $jobs" >&2
  exit 2
fi

script_dir=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
npc_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
repo_root=$(CDPATH= cd -- "$npc_dir/.." && pwd)
pack_src="$npc_dir/build/pack-fpga"

vivado_proj_home="$repo_root/jyd-vivado-proj"
vivado_project="$vivado_proj_home/digital_twin.xpr"
pack_dst="$vivado_proj_home/digital_twin.srcs/sources_1/imports/pack-fpga"
vivado_bin="${VIVADO:-vivado}"

if [ ! -d "$vivado_proj_home" ]; then
  echo "Vivado project directory does not exist: $vivado_proj_home" >&2
  exit 1
fi

if [ ! -f "$vivado_project" ]; then
  echo "Vivado project file does not exist: $vivado_project" >&2
  exit 1
fi

if [ "$skip_pack" -eq 0 ]; then
  make -C "$npc_dir" pack-fpga
fi

if [ ! -d "$pack_src" ]; then
  echo "pack-fpga directory does not exist: $pack_src" >&2
  echo "Run make -C $npc_dir pack-fpga first, or omit --skip-pack." >&2
  exit 1
fi

echo "# Updating Vivado imported pack-fpga"
echo "#   from: $pack_src"
echo "#   to:   $pack_dst"
rm -rf -- "$pack_dst"
mkdir -p -- "$(dirname -- "$pack_dst")"
cp -a -- "$pack_src" "$pack_dst"

if [ "$skip_vivado" -ne 0 ]; then
  echo "# Skipping Vivado run"
  exit 0
fi

if ! command -v "$vivado_bin" >/dev/null 2>&1; then
  echo "Vivado executable not found: $vivado_bin" >&2
  echo "Set VIVADO=/path/to/vivado or add vivado to PATH." >&2
  exit 1
fi

tcl_file=$(mktemp "${TMPDIR:-/tmp}/digital_twin_flow.XXXXXX.tcl")
trap 'rm -f "$tcl_file"' EXIT

cat >"$tcl_file" <<'EOF'
if {$argc != 3} {
  error "Expected Tcl args: <mode> <jobs> <expected-pack-fpga-dir>"
}
set mode [lindex $argv 0]
set jobs [lindex $argv 1]
set expected_pack_dir [file normalize [lindex $argv 2]]

open_project digital_twin.xpr
set_param general.maxThreads $jobs
update_compile_order -fileset sources_1
puts "Vivado launch_runs jobs: $jobs"
puts "Vivado general.maxThreads: [get_param general.maxThreads]"

set pack_file_count 0
foreach source_file [get_files -all] {
  set resolved_file [file normalize $source_file]
  if {[string first "/pack-fpga/" $resolved_file] >= 0} {
    incr pack_file_count
    if {[string first "${expected_pack_dir}/" $resolved_file] != 0} {
      error "pack-fpga source escaped the in-tree project: $resolved_file (expected under $expected_pack_dir)"
    }
  }
}
if {$pack_file_count == 0} {
  error "No pack-fpga sources were resolved from the Vivado project"
}
puts "Verified $pack_file_count pack-fpga sources under: $expected_pack_dir"

# Generated IP output products and OOC checkpoints are ignored build
# artifacts.  They may therefore belong to an older experiment even when the
# checked-in XCI is correct.  Rebuild every project IP before top synthesis so
# neither clocks nor COE-backed memories can silently reuse stale products.
set project_ips [get_ips -quiet]
if {[llength $project_ips] == 0} {
  error "No project IPs were found"
}
set locked_ips [list]
foreach ip_obj $project_ips {
  if {[get_property IS_LOCKED $ip_obj]} {
    lappend locked_ips [get_property NAME $ip_obj]
  }
}
if {[llength $locked_ips] > 0} {
  error "Locked/stale project IPs must be resolved before synthesis: $locked_ips"
}
set pll_ip [get_ips -quiet mypll]
if {[llength $pll_ip] != 1} {
  error "Expected exactly one mypll IP, got [llength $pll_ip]"
}
set requested_cpu_mhz [get_property CONFIG.CLKOUT2_REQUESTED_OUT_FREQ $pll_ip]
set pll_input_mhz [get_property CONFIG.PRIM_IN_FREQ $pll_ip]
set pll_feedback_mult [get_property CONFIG.MMCM_CLKFBOUT_MULT_F $pll_ip]
set pll_input_divide [get_property CONFIG.MMCM_DIVCLK_DIVIDE $pll_ip]
# The external clk_out2 CPU port is Clock Wizard's internal CLKOUT1.
set pll_cpu_divide [get_property CONFIG.MMCM_CLKOUT1_DIVIDE $pll_ip]
set pll_vco_mhz [expr {double($pll_input_mhz) * double($pll_feedback_mult) / double($pll_input_divide)}]
set configured_cpu_mhz [expr {$pll_vco_mhz / double($pll_cpu_divide)}]
set configured_freq_error_mhz [expr {abs(double($requested_cpu_mhz) - $configured_cpu_mhz)}]
puts [format "Configured CPU clock: requested=%.6f MHz actual=%.6f MHz error=%.6f MHz VCO=%.6f MHz divclk=%s feedback=%s output=%s" \
  $requested_cpu_mhz $configured_cpu_mhz $configured_freq_error_mhz $pll_vco_mhz \
  $pll_input_divide $pll_feedback_mult $pll_cpu_divide]
if {$configured_freq_error_mhz > 0.001} {
  error [format "CPU clock requested/actual mismatch exceeds 1 kHz: %.6f MHz" $configured_freq_error_mhz]
}
puts "Resetting and regenerating [llength $project_ips] project IP output products"
reset_target all $project_ips
generate_target all $project_ips
set checkpoint_ips [list]
foreach ip_obj $project_ips {
  if {[get_property GENERATE_SYNTH_CHECKPOINT $ip_obj]} {
    lappend checkpoint_ips $ip_obj
  }
}
puts "Rebuilding [llength $checkpoint_ips] project IP synthesis checkpoints"
if {[llength $checkpoint_ips] > 0} {
  synth_ip -force $checkpoint_ips
}
set locked_ips_after_generate [list]
foreach ip_obj $project_ips {
  if {[get_property IS_LOCKED $ip_obj]} {
    lappend locked_ips_after_generate [get_property NAME $ip_obj]
  }
}
if {[llength $locked_ips_after_generate] > 0} {
  error "Project IPs became locked/stale while regenerating output products: $locked_ips_after_generate"
}

if {[llength [get_runs synth_1]] == 0} {
  error "Vivado run synth_1 was not found"
}
if {[llength [get_runs impl_1]] == 0} {
  error "Vivado run impl_1 was not found"
}

# The 270 MHz v6 design closes timing only after the post-route physical
# optimization pass. Keep it inside impl_1 so timing reports and bitstreams are
# produced from the same reproducible run rather than a standalone DCP edit.
set impl_run [get_runs impl_1]
set_property STEPS.POST_ROUTE_PHYS_OPT_DESIGN.IS_ENABLED true $impl_run
set_property STEPS.POST_ROUTE_PHYS_OPT_DESIGN.ARGS.DIRECTIVE AggressiveExplore $impl_run
puts "impl_1 post-route physopt: enabled, directive=AggressiveExplore"

proc clear_run_property_if_exists {run_name prop_name} {
  set run_obj [get_runs $run_name]
  if {[lsearch -exact [list_property $run_obj] $prop_name] >= 0} {
    set old_value [get_property $prop_name $run_obj]
    if {$old_value ne ""} {
      puts "Clearing $run_name $prop_name: $old_value"
    }
    set_property $prop_name {} $run_obj
  }
}

foreach run_name {synth_1 impl_1} {
  foreach prop_name {
    INCREMENTAL_CHECKPOINT
    incremental_checkpoint
    STEPS.SYNTH_DESIGN.ARGS.INCREMENTAL_CHECKPOINT
    STEPS.OPT_DESIGN.ARGS.INCREMENTAL_CHECKPOINT
  } {
    clear_run_property_if_exists $run_name $prop_name
  }
}

set synth_run [get_runs synth_1]
if {[lsearch -exact [list_property $synth_run] AUTO_INCREMENTAL_CHECKPOINT] >= 0} {
  # Vivado 2024.2 exposes this run property as an integer.  Passing the Tcl
  # boolean string "false" fails with "bad lexical cast" before synthesis.
  set_property AUTO_INCREMENTAL_CHECKPOINT 0 $synth_run
}

reset_run synth_1

launch_runs synth_1 -jobs $jobs
wait_on_run synth_1
set synth_status [get_property STATUS [get_runs synth_1]]
set synth_progress [get_property PROGRESS [get_runs synth_1]]
puts "synth_1 STATUS: $synth_status"
puts "synth_1 PROGRESS: $synth_progress"

if {$synth_progress ne "100%"} {
  error "synth_1 did not finish successfully"
}
if {[string match -nocase {*failed*} $synth_status]} {
  error "synth_1 failed: $synth_status"
}

# The CPU is driven by mypll/clk_out2.  Validate the clock that top synthesis
# actually propagated, not only the requested value stored in the XCI.
open_run synth_1
set cpu_clocks [get_clocks -quiet clk_out2_mypll]
if {[llength $cpu_clocks] != 1} {
  error "Expected exactly one synthesized clk_out2_mypll clock, got [llength $cpu_clocks]"
}
set cpu_period_ns [get_property PERIOD $cpu_clocks]
set expected_cpu_period_ns [expr {1000.0 / $configured_cpu_mhz}]
set cpu_period_error_ns [expr {abs(double($cpu_period_ns) - $expected_cpu_period_ns)}]
puts [format "Synthesized CPU clock: configured=%.6f MHz reported_period=%.6f ns expected_period=%.6f ns" \
  $configured_cpu_mhz $cpu_period_ns $expected_cpu_period_ns]
# get_clocks PERIOD is reported with only ps precision.  This check catches a
# stale clock product (for example 3.810 ns at 262.5 MHz) while the exact 1 kHz
# requested/actual gate above is computed from the PLL divisors.
if {$cpu_period_error_ns > 0.0005} {
  error [format "Synthesized CPU clock period does not match configured PLL: %.6f ns" $cpu_period_error_ns]
}
close_design

if {$mode eq "impl"} {
  launch_runs impl_1 -jobs $jobs
} elseif {$mode eq "write_bitstream"} {
  launch_runs impl_1 -to_step write_bitstream -jobs $jobs
} else {
  error "Unsupported mode: $mode"
}

wait_on_run impl_1
set status [get_property STATUS [get_runs impl_1]]
set progress [get_property PROGRESS [get_runs impl_1]]
puts "impl_1 STATUS: $status"
puts "impl_1 PROGRESS: $progress"

if {$progress ne "100%"} {
  error "impl_1 did not finish successfully"
}
if {[string match -nocase {*failed*} $status]} {
  error "impl_1 failed: $status"
}

close_project
EOF

echo "# Running Vivado digital_twin to $mode"
cd "$vivado_proj_home"

ip_config_hash() {
  while IFS= read -r -d '' xci_file; do
    # Vivado may remove a final newline while regenerating an otherwise
    # byte-identical JSON XCI. Normalize that non-semantic difference while
    # retaining the gate for every configuration value and file path.
    normalized_hash=$(sed -e '$a\' "$xci_file" | sha256sum | awk '{print $1}')
    printf '%s  %s\n' "$normalized_hash" "$xci_file"
  done < <(find "$vivado_proj_home/digital_twin.srcs/sources_1/ip" -type f -name '*.xci' -print0 | sort -z) \
    | sha256sum \
    | awk '{print $1}'
}

# Vivado may warn instead of failing when an IP configuration changes while a
# run is active, then silently reuse a cached output product. Freeze the XCI
# manifest across the entire process so such a run is always rejected.
ip_config_hash_before=$(ip_config_hash)
set +e
"$vivado_bin" -mode batch -source "$tcl_file" -tclargs "$mode" "$jobs" "$pack_dst"
vivado_status=$?
set -e
ip_config_hash_after=$(ip_config_hash)
if [ "$ip_config_hash_before" != "$ip_config_hash_after" ]; then
  echo "Vivado IP configuration changed during the run:" >&2
  echo "  before: $ip_config_hash_before" >&2
  echo "  after:  $ip_config_hash_after" >&2
  exit 1
fi
exit "$vivado_status"
