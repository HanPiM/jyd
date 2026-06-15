#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Usage: run_digital_twin_vivado.sh [impl|write_bitstream|bitstream] [--jobs N] [--skip-pack] [--skip-vivado]

Build npc pack-fpga, replace the Vivado project's imported pack-fpga directory,
then run the digital_twin Vivado project to impl or write_bitstream.

Environment:
  JYD_VIVADO_PROJ_HOME  Path to the digital_twin Vivado project root.
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
pack_src="$npc_dir/build/pack-fpga"

: "${JYD_VIVADO_PROJ_HOME:?JYD_VIVADO_PROJ_HOME is not set}"
vivado_proj_home=$JYD_VIVADO_PROJ_HOME
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
if {$argc != 2} {
  error "Expected Tcl args: <mode> <jobs>"
}
set mode [lindex $argv 0]
set jobs [lindex $argv 1]

open_project digital_twin.xpr
set_param general.maxThreads $jobs
update_compile_order -fileset sources_1
puts "Vivado launch_runs jobs: $jobs"
puts "Vivado general.maxThreads: [get_param general.maxThreads]"

if {[llength [get_runs synth_1]] == 0} {
  error "Vivado run synth_1 was not found"
}
if {[llength [get_runs impl_1]] == 0} {
  error "Vivado run impl_1 was not found"
}

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
"$vivado_bin" -mode batch -source "$tcl_file" -tclargs "$mode" "$jobs"
