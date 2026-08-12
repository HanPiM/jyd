#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Usage: run_digital_twin_vivado.sh [impl|write_bitstream|bitstream] [--jobs N] [--ip-jobs N] [--coe-dir DIR] [--isolated-profile NAME] [--archive-dir DIR] [--keep-workdir] [--project-root DIR] [--expected-cpu-mhz N] [--flow-profile NAME] [--reset-runs] [--reuse-ip] [--skip-pack] [--skip-vivado] [--user-approved-low-jobs]

Build npc pack-fpga, replace the Vivado project's imported pack-fpga directory,
then run the digital_twin Vivado project to impl or write_bitstream.

By default, completed IP/OOC and synth_1 checkpoints are reused and impl_1 is rerun.
Pass --reset-runs for a clean IP/OOC, synth_1, and impl_1 rebuild.
Bitstream mode always replaces the routed DCP's memories from DIR/irom.coe and
DIR/dram.coe (default: <repo>/cur_coe), then records their hashes beside top.bit.

Environment:
  VIVADO                Vivado executable to use. Defaults to "vivado".
  JOBS                  Vivado top-level jobs and max threads. Defaults to nproc.
  IP_JOBS               IP/OOC run concurrency and max threads. Defaults to 4.

Isolated diagnostic flow:
  --isolated-profile NAME
                         Build quick-75, default-200, or default-150 in a copied project.
  --archive-dir DIR      Archive isolated-flow logs, reports, DCPs, and bitstream in DIR.
  --keep-workdir         Retain the isolated Vivado project after completion.
  --project-root DIR     Use DIR as the Vivado project instead of the in-tree project.
  --expected-cpu-mhz N   Require the configured CPU clock to match N MHz.
  --flow-profile NAME    project, quick, or default. Defaults to project.
  --reuse-ip             Rebuild top synthesis while reusing completed IP/OOC products.

Parallelism policy:
  Top-level jobs must be at least 16 and IP/OOC jobs must be at least 4.
  Lower values require explicit user approval and --user-approved-low-jobs.
  VIVADO_SYNTH_GLOBAL_RETIMING
                         Set to 1 to enable synth_design global retiming.
  VIVADO_SYNTH_KEEP_EQUIVALENT_REGISTERS
                         Set to 1 to preserve equivalent registers in synthesis.
  VIVADO_SYNTH_FLATTEN_HIERARCHY
                         Optional synth_design flatten level: none, rebuilt, or full.
  VIVADO_PLACE_DIRECTIVE
                         Optional place_design directive, e.g. Explore.
  VIVADO_ROUTE_DIRECTIVE
                         Optional route_design directive, e.g. NoTimingRelaxation.
  VIVADO_PRE_ROUTE_PHYS_OPT_DIRECTIVE
                         Optional pre-route phys_opt_design directive,
                         e.g. AggressiveExplore.
  VIVADO_POST_ROUTE_PHYS_OPT_DIRECTIVE
                         Default "AggressiveExplore"; supported values are
                         Disabled, Default, Explore, and AggressiveExplore.
EOF
}

mode=impl
skip_pack=0
skip_vivado=0
reset_runs=0
reuse_ip=0
user_approved_low_jobs=0
jobs="${JOBS:-$(nproc 2>/dev/null || echo 4)}"
ip_jobs=""
coe_dir=""
project_root=""
expected_cpu_mhz=""
flow_profile=project
isolated_profile=""
archive_dir=""
keep_workdir=0
synth_global_retiming="${VIVADO_SYNTH_GLOBAL_RETIMING:-0}"
synth_keep_equivalent_registers="${VIVADO_SYNTH_KEEP_EQUIVALENT_REGISTERS:-0}"
synth_flatten_hierarchy="${VIVADO_SYNTH_FLATTEN_HIERARCHY:-}"
place_directive="${VIVADO_PLACE_DIRECTIVE:-}"
route_directive="${VIVADO_ROUTE_DIRECTIVE:-}"
pre_route_phys_opt_directive="${VIVADO_PRE_ROUTE_PHYS_OPT_DIRECTIVE:-}"
post_route_phys_opt_directive="${VIVADO_POST_ROUTE_PHYS_OPT_DIRECTIVE:-AggressiveExplore}"

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
    --ip-jobs)
      if [ "$#" -lt 2 ]; then
        echo "Missing value for --ip-jobs" >&2
        exit 2
      fi
      ip_jobs="$2"
      shift 2
      ;;
    --coe-dir)
      if [ "$#" -lt 2 ]; then
        echo "Missing value for --coe-dir" >&2
        exit 2
      fi
      coe_dir="$2"
      shift 2
      ;;
    --project-root)
      if [ "$#" -lt 2 ]; then
        echo "Missing value for --project-root" >&2
        exit 2
      fi
      project_root="$2"
      shift 2
      ;;
    --isolated-profile)
      [ "$#" -ge 2 ] || { echo "Missing value for --isolated-profile" >&2; exit 2; }
      isolated_profile="$2"
      shift 2
      ;;
    --archive-dir)
      [ "$#" -ge 2 ] || { echo "Missing value for --archive-dir" >&2; exit 2; }
      archive_dir="$2"
      shift 2
      ;;
    --keep-workdir)
      keep_workdir=1
      shift
      ;;
    --expected-cpu-mhz)
      if [ "$#" -lt 2 ]; then
        echo "Missing value for --expected-cpu-mhz" >&2
        exit 2
      fi
      expected_cpu_mhz="$2"
      shift 2
      ;;
    --flow-profile)
      if [ "$#" -lt 2 ]; then
        echo "Missing value for --flow-profile" >&2
        exit 2
      fi
      flow_profile="$2"
      shift 2
      ;;
    --reset-runs)
      reset_runs=1
      shift
      ;;
    --reuse-ip)
      reuse_ip=1
      shift
      ;;
    --skip-pack)
      skip_pack=1
      shift
      ;;
    --skip-vivado)
      skip_vivado=1
      shift
      ;;
    --user-approved-low-jobs)
      user_approved_low_jobs=1
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
if [ -z "$ip_jobs" ]; then
  ip_jobs="${IP_JOBS:-4}"
fi
if ! [[ "$ip_jobs" =~ ^[1-9][0-9]*$ ]]; then
  echo "--ip-jobs must be a positive integer: $ip_jobs" >&2
  exit 2
fi
if [ -n "$expected_cpu_mhz" ] && ! awk -v value="$expected_cpu_mhz" 'BEGIN { exit !(value > 0) }'; then
  echo "--expected-cpu-mhz must be positive: $expected_cpu_mhz" >&2
  exit 2
fi
case "$flow_profile" in
  project | quick | default) ;;
  *)
    echo "Unsupported --flow-profile: $flow_profile" >&2
    exit 2
    ;;
esac
case "$isolated_profile" in
  "" | quick-75 | default-200 | default-150) ;;
  *) echo "Unsupported --isolated-profile: $isolated_profile" >&2; exit 2 ;;
esac
if [ -n "$isolated_profile" ] && [ -n "$project_root" ]; then
  echo "--isolated-profile and --project-root are mutually exclusive" >&2
  exit 2
fi
if [ "$reset_runs" -eq 1 ] && [ "$reuse_ip" -eq 1 ]; then
  echo "--reset-runs and --reuse-ip are mutually exclusive" >&2
  exit 2
fi
if [ "$jobs" -lt 16 ] || [ "$ip_jobs" -lt 4 ]; then
  if [ "$user_approved_low_jobs" -ne 1 ]; then
    echo "Vivado parallelism below policy minimum: jobs=$jobs (minimum 16), ip-jobs=$ip_jobs (minimum 4)." >&2
    echo "Obtain explicit user approval, then pass --user-approved-low-jobs to override." >&2
    exit 2
  fi
  echo "WARNING: USER-APPROVED LOW PARALLELISM: jobs=$jobs ip-jobs=$ip_jobs" >&2
fi
if [[ "$synth_global_retiming" != 0 && "$synth_global_retiming" != 1 ]]; then
  echo "VIVADO_SYNTH_GLOBAL_RETIMING must be 0 or 1: $synth_global_retiming" >&2
  exit 2
fi
if [[ "$synth_keep_equivalent_registers" != 0 && "$synth_keep_equivalent_registers" != 1 ]]; then
  echo "VIVADO_SYNTH_KEEP_EQUIVALENT_REGISTERS must be 0 or 1: $synth_keep_equivalent_registers" >&2
  exit 2
fi
case "$post_route_phys_opt_directive" in
  Disabled | Default | Explore | AggressiveExplore) ;;
  *)
    echo "Unsupported VIVADO_POST_ROUTE_PHYS_OPT_DIRECTIVE: $post_route_phys_opt_directive" >&2
    exit 2
    ;;
esac
case "$synth_flatten_hierarchy" in
  "" | none | rebuilt | full) ;;
  *)
    echo "Unsupported VIVADO_SYNTH_FLATTEN_HIERARCHY: $synth_flatten_hierarchy" >&2
    exit 2
    ;;
esac
case "$place_directive" in
  "" | Default | Explore | ExtraTimingOpt | ExtraNetDelay_high | ExtraNetDelay_low | \
  ExtraPostPlacementOpt | AltSpreadLogic_high | AltSpreadLogic_low | Auto_1) ;;
  *)
    echo "Unsupported VIVADO_PLACE_DIRECTIVE: $place_directive" >&2
    exit 2
    ;;
esac
case "$route_directive" in
  "" | Default | Explore | NoTimingRelaxation | HigherDelayCost | MoreGlobalIterations | \
  AggressiveExplore | Quick) ;;
  *)
    echo "Unsupported VIVADO_ROUTE_DIRECTIVE: $route_directive" >&2
    exit 2
    ;;
esac
case "$pre_route_phys_opt_directive" in
  "" | Default | Explore | AggressiveExplore | AlternateReplication | AlternateFlowWithRetiming) ;;
  *)
    echo "Unsupported VIVADO_PRE_ROUTE_PHYS_OPT_DIRECTIVE: $pre_route_phys_opt_directive" >&2
    exit 2
    ;;
esac

script_dir=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
npc_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
repo_root=$(CDPATH= cd -- "$npc_dir/.." && pwd)
pack_src="$npc_dir/build/pack-fpga"
vivado_bin="${VIVADO:-vivado}"

if [ -z "$coe_dir" ]; then
  coe_dir="$repo_root/cur_coe"
fi
if [ "$mode" = write_bitstream ]; then
  if [ "${coe_dir#/}" = "$coe_dir" ]; then
    coe_dir="$PWD/$coe_dir"
  fi
  coe_dir=$(CDPATH= cd -- "$coe_dir" 2>/dev/null && pwd) || {
    echo "COE directory does not exist: $coe_dir" >&2
    exit 1
  }
  irom_coe="$coe_dir/irom.coe"
  dram_coe="$coe_dir/dram.coe"
  for coe_file in "$irom_coe" "$dram_coe"; do
    if [ ! -f "$coe_file" ]; then
      echo "Required bitstream COE does not exist: $coe_file" >&2
      exit 1
    fi
  done
fi

if [ -n "$isolated_profile" ]; then
  case "$isolated_profile" in
    quick-75) isolated_cpu_mhz=75; isolated_flow_profile=quick ;;
    default-200) isolated_cpu_mhz=200; isolated_flow_profile=default ;;
    default-150) isolated_cpu_mhz=150; isolated_flow_profile=default ;;
  esac
  workload_manifest="$coe_dir/coremark-workload.env"
  [ -f "$workload_manifest" ] || {
    echo "Formal COE manifest does not exist: $workload_manifest" >&2
    exit 1
  }
  manifest_iterations=$(sed -n 's/^COREMARK_ITERATIONS=//p' "$workload_manifest")
  manifest_irom_sha=$(sed -n 's/^COREMARK_IROM_SHA256=//p' "$workload_manifest")
  manifest_dram_sha=$(sed -n 's/^COREMARK_DRAM_SHA256=//p' "$workload_manifest")
  actual_irom_sha=$(sha256sum "$irom_coe" | awk '{print $1}')
  actual_dram_sha=$(sha256sum "$dram_coe" | awk '{print $1}')
  [ "$manifest_iterations" = 10000 ] || {
    echo "Isolated bitstream input must use COREMARK_ITERATIONS=10000, got: ${manifest_iterations:-missing}" >&2
    exit 1
  }
  [ "$manifest_irom_sha" = "$actual_irom_sha" ] || {
    echo "irom.coe does not match formal workload manifest" >&2
    exit 1
  }
  [ "$manifest_dram_sha" = "$actual_dram_sha" ] || {
    echo "dram.coe does not match formal workload manifest" >&2
    exit 1
  }

  jyd_data_root="${JYD_DATA_ROOT:-/srv/data/jyd}"
  isolated_stamp=$(date -u +%Y%m%dT%H%M%SZ)
  isolated_workdir=$(mktemp -d "$jyd_data_root/tmp/digital-twin-vivado.${isolated_profile}.XXXXXX")
  isolated_project="$isolated_workdir/jyd-vivado-proj"
  if [ -z "$archive_dir" ]; then
    archive_dir="$jyd_data_root/archive/digital-twin-vivado-$isolated_stamp/$isolated_profile"
  elif [ "${archive_dir#/}" = "$archive_dir" ]; then
    archive_dir="$PWD/$archive_dir"
  fi
  mkdir -p -- "$isolated_project" "$archive_dir"

  source_project="$repo_root/jyd-vivado-proj"
  cp -a -- "$source_project/digital_twin.xpr" "$isolated_project/"
  for source_path in \
    "$source_project/digital_twin.srcs/constrs_1" \
    "$source_project/digital_twin.srcs/sim_1" \
    "$source_project/digital_twin.srcs/sources_1/new" \
    "$source_project/jyd-coes"; do
    if [ -e "$source_path" ]; then
      target_path="$isolated_project/${source_path#"$source_project/"}"
      mkdir -p -- "$(dirname -- "$target_path")"
      cp -a -- "$source_path" "$target_path"
    fi
  done
  while IFS= read -r -d '' source_path; do
    target_path="$isolated_project/${source_path#"$source_project/"}"
    mkdir -p -- "$(dirname -- "$target_path")"
    cp -a -- "$source_path" "$target_path"
  done < <(find "$source_project/digital_twin.srcs/sources_1/ip" -type f -name '*.xci' -print0 | sort -z)
  for cache_name in digital_twin.gen digital_twin.cache digital_twin.ip_user_files; do
    [ ! -d "$source_project/$cache_name" ] || cp -a --reflink=auto -- "$source_project/$cache_name" "$isolated_project/"
  done
  mkdir -p -- "$isolated_project/digital_twin.runs"
  while IFS= read -r -d '' run_dir; do
    cp -a --reflink=auto -- "$run_dir" "$isolated_project/digital_twin.runs/"
  done < <(find "$source_project/digital_twin.runs" -mindepth 1 -maxdepth 1 -type d -name '*_synth_1' -print0 2>/dev/null | sort -z)

  if [ "$skip_pack" -eq 0 ]; then
    make -C "$npc_dir" pack-fpga
  fi
  [ -d "$pack_src" ] || { echo "pack-fpga directory does not exist: $pack_src" >&2; exit 1; }
  mkdir -p -- "$isolated_project/digital_twin.srcs/sources_1/imports"
  cp -a -- "$pack_src" "$isolated_project/digital_twin.srcs/sources_1/imports/pack-fpga"

  configure_tcl="$isolated_workdir/configure-clock.tcl"
  cat >"$configure_tcl" <<'EOF'
if {$argc != 2} { error "Expected Tcl args: <project-path> <cpu-mhz>" }
set project_path [file normalize [lindex $argv 0]]
set cpu_mhz [lindex $argv 1]
open_project $project_path
set pll_ip [get_ips -quiet mypll]
if {[llength $pll_ip] != 1} { error "Expected exactly one mypll IP, got [llength $pll_ip]" }
set_property CONFIG.CLKOUT2_REQUESTED_OUT_FREQ $cpu_mhz $pll_ip
set requested_cpu [get_property CONFIG.CLKOUT2_REQUESTED_OUT_FREQ $pll_ip]
set requested_peripheral [get_property CONFIG.CLKOUT1_REQUESTED_OUT_FREQ $pll_ip]
puts "CLOCK_PROFILE_CPU_MHZ=$requested_cpu"
puts "CLOCK_PROFILE_PERIPHERAL_MHZ=$requested_peripheral"
if {abs(double($requested_cpu) - double($cpu_mhz)) > 0.001} { error "Clock Wizard rejected requested CPU frequency $cpu_mhz MHz" }
if {abs(double($requested_peripheral) - 50.0) > 0.001} { error "Clock Wizard peripheral output is not 50 MHz: $requested_peripheral" }
close_project
EOF
  (
    cd "$isolated_project"
    "$vivado_bin" -mode batch -nolog -nojournal -notrace -source "$configure_tcl" \
      -tclargs "$isolated_project/digital_twin.xpr" "$isolated_cpu_mhz"
  ) 2>&1 | tee "$archive_dir/configure-clock.log"

  {
    echo "profile=$isolated_profile"
    echo "cpu_mhz=$isolated_cpu_mhz"
    echo "flow_profile=$isolated_flow_profile"
    echo "repo_commit=$(git -C "$repo_root" rev-parse HEAD)"
    echo "coe_dir=$coe_dir"
    echo "irom_sha256=$actual_irom_sha"
    echo "dram_sha256=$actual_dram_sha"
    echo "workdir=$isolated_workdir"
    echo "started_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  } >"$archive_dir/metadata.env"

  set +e
  "$script_dir/run_digital_twin_vivado.sh" "$mode" \
    --project-root "$isolated_project" --expected-cpu-mhz "$isolated_cpu_mhz" \
    --flow-profile "$isolated_flow_profile" --coe-dir "$coe_dir" \
    --jobs "$jobs" --ip-jobs "$ip_jobs" --reset-runs --skip-pack \
    2>&1 | tee "$archive_dir/vivado-runner.log"
  isolated_status=${PIPESTATUS[0]}
  set -e

  mkdir -p -- "$archive_dir/artifacts"
  for run_name in synth_1 impl_1; do
    run_path="$isolated_project/digital_twin.runs/$run_name"
    [ ! -d "$run_path" ] || find "$run_path" -maxdepth 1 -type f \
      \( -name '*.bit' -o -name '*.dcp' -o -name '*timing*.rpt' -o -name 'runme.log' -o -name 'runme.jou' \) \
      -exec cp -a --parents -- {} "$archive_dir/artifacts" \;
  done
  if [ "$isolated_status" -eq 0 ] && [ "$mode" = write_bitstream ]; then
    cp -a -- "$isolated_project/digital_twin.runs/impl_1/top.bit" "$archive_dir/top.bit"
    cp -a -- "$isolated_project/digital_twin.runs/impl_1/top.bit.coe-manifest" "$archive_dir/"
    sha256sum "$archive_dir/top.bit" >"$archive_dir/bitstream-sha256.txt"
  fi
  timing_report="$isolated_project/digital_twin.runs/impl_1/top_timing_summary_postroute_physopted.rpt"
  [ -f "$timing_report" ] || timing_report="$isolated_project/digital_twin.runs/impl_1/top_timing_summary_routed.rpt"
  if [ -f "$timing_report" ]; then
    python3 "$repo_root/jyd-vivado-proj/scripts/extract-timing-summary.py" "$timing_report" >"$archive_dir/timing-summary.txt"
  fi
  {
    echo "vivado_status=$isolated_status"
    echo "archive_dir=$archive_dir"
    echo "finished_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  } >>"$archive_dir/metadata.env"
  echo "ISOLATED_PROFILE=$isolated_profile"
  echo "ISOLATED_ARCHIVE=$archive_dir"
  if [ "$keep_workdir" -eq 1 ] || [ "$isolated_status" -ne 0 ]; then
    echo "ISOLATED_WORKDIR=$isolated_workdir"
  else
    rm -rf -- "$isolated_workdir"
  fi
  exit "$isolated_status"
fi

if [ -n "$project_root" ]; then
  vivado_proj_home=$(CDPATH= cd -- "$project_root" 2>/dev/null && pwd) || {
    echo "Vivado project directory does not exist: $project_root" >&2
    exit 1
  }
else
  vivado_proj_home="$repo_root/jyd-vivado-proj"
fi
vivado_project="$vivado_proj_home/digital_twin.xpr"
pack_dst="$vivado_proj_home/digital_twin.srcs/sources_1/imports/pack-fpga"

if [ ! -d "$vivado_proj_home" ]; then
  echo "Vivado project directory does not exist: $vivado_proj_home" >&2
  exit 1
fi

if [ ! -f "$vivado_project" ]; then
  echo "Vivado project file does not exist: $vivado_project" >&2
  exit 1
fi

# A second flow can otherwise overlap the first flow's project-manager and
# OOC child processes. Besides corrupting run state, that can exhaust memory
# even when either flow is safe by itself. GUI runs do not take this lock, so
# also keep the explicit run-status checks in the Tcl flow below.
lock_file="$vivado_proj_home/.digital_twin_vivado_flow.lock"
exec 9>"$lock_file"
if ! flock -n 9; then
  echo "Another scripted Vivado flow is already active for: $vivado_proj_home" >&2
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
xpr_backup=$(mktemp "${TMPDIR:-/tmp}/digital_twin_project.XXXXXX.xpr")
cp -- "$vivado_project" "$xpr_backup"
xpr_hash_before=$(sha256sum "$vivado_project" | awk '{print $1}')
cleanup() {
  if [ -n "${xpr_backup:-}" ] && [ -f "$xpr_backup" ]; then
    cp -- "$xpr_backup" "$vivado_project"
    rm -f -- "$xpr_backup"
  fi
  rm -f -- "$tcl_file"
}
trap cleanup EXIT

cat >"$tcl_file" <<'EOF'
if {$argc != 15} {
  error "Expected Tcl args: <mode> <jobs> <ip-jobs> <expected-pack-fpga-dir> <reset-runs> <reuse-ip> <expected-cpu-mhz> <flow-profile> <global-retiming> <keep-equivalent-registers> <flatten-hierarchy> <place-directive> <route-directive> <pre-route-physopt-directive> <post-route-physopt-directive>"
}
set mode [lindex $argv 0]
set jobs [lindex $argv 1]
set ip_jobs [lindex $argv 2]
set expected_pack_dir [file normalize [lindex $argv 3]]
set reset_runs [lindex $argv 4]
set reuse_ip [lindex $argv 5]
set expected_cpu_mhz [lindex $argv 6]
set flow_profile [lindex $argv 7]
set synth_global_retiming [lindex $argv 8]
set synth_keep_equivalent_registers [lindex $argv 9]
set synth_flatten_hierarchy [lindex $argv 10]
set place_directive [lindex $argv 11]
set route_directive [lindex $argv 12]
set pre_route_phys_opt_directive [lindex $argv 13]
set post_route_phys_opt_directive [lindex $argv 14]
if {$reset_runs ni {0 1}} {
  error "reset-runs must be 0 or 1, got: $reset_runs"
}
if {$reuse_ip ni {0 1}} {
  error "reuse-ip must be 0 or 1, got: $reuse_ip"
}
if {$flow_profile ni {project quick default}} {
  error "unsupported flow profile: $flow_profile"
}
if {$synth_global_retiming ni {0 1}} {
  error "global-retiming must be 0 or 1, got: $synth_global_retiming"
}
if {$synth_keep_equivalent_registers ni {0 1}} {
  error "keep-equivalent-registers must be 0 or 1, got: $synth_keep_equivalent_registers"
}
if {$post_route_phys_opt_directive ni {Disabled Default Explore AggressiveExplore}} {
  error "unsupported post-route physopt directive: $post_route_phys_opt_directive"
}
if {$synth_flatten_hierarchy ni {"" none rebuilt full}} {
  error "unsupported synth flatten hierarchy: $synth_flatten_hierarchy"
}
if {$place_directive ni {"" Default Explore ExtraTimingOpt ExtraNetDelay_high ExtraNetDelay_low ExtraPostPlacementOpt AltSpreadLogic_high AltSpreadLogic_low Auto_1}} {
  error "unsupported place directive: $place_directive"
}
if {$route_directive ni {"" Default Explore NoTimingRelaxation HigherDelayCost MoreGlobalIterations AggressiveExplore Quick}} {
  error "unsupported route directive: $route_directive"
}
if {$pre_route_phys_opt_directive ni {"" Default Explore AggressiveExplore AlternateReplication AlternateFlowWithRetiming}} {
  error "unsupported pre-route physopt directive: $pre_route_phys_opt_directive"
}

open_project digital_twin.xpr
set_param general.maxThreads $ip_jobs

set active_runs [list]
foreach run_obj [get_runs] {
  set run_status [get_property STATUS $run_obj]
  if {[string match -nocase {running*} $run_status] || [string match -nocase {queued*} $run_status]} {
    lappend active_runs "[get_property NAME $run_obj] ($run_status)"
  }
}
if {[llength $active_runs] > 0} {
  error "Vivado project has active or stale-running run metadata; resolve it before starting another flow: $active_runs"
}

# The generated Chisel file list may gain or lose helper modules (for example
# inferred memories) between candidates.  In particular, a copied worktree's
# XPR can retain an absolute path into its source worktree for a helper which
# the new pack no longer emits.  Remove every prior pack-fpga project entry,
# then register exactly this invocation's packaged RTL.  This keeps an
# implementation from silently mixing sources from another worktree.
set stale_pack_sources [list]
foreach source_file [get_files -all] {
  set resolved_file [file normalize $source_file]
  if {[string first "/pack-fpga/" $resolved_file] >= 0} {
    lappend stale_pack_sources $source_file
  }
}
if {[llength $stale_pack_sources] > 0} {
  remove_files -fileset sources_1 $stale_pack_sources
}

# Register every packaged RTL source before updating compile order so the
# in-tree project always consumes the complete pack-fpga artifact.
set known_project_files [dict create]
foreach source_file [get_files -all] {
  dict set known_project_files [file normalize $source_file] 1
}
set added_pack_sources 0
foreach pack_subdir {cpu fpgawrap} {
  foreach pattern {*.v *.sv} {
    foreach source_file [glob -nocomplain -directory "${expected_pack_dir}/${pack_subdir}" $pattern] {
      set resolved_file [file normalize $source_file]
      if {![dict exists $known_project_files $resolved_file]} {
        add_files -fileset sources_1 -norecurse $resolved_file
        dict set known_project_files $resolved_file 1
        incr added_pack_sources
      }
    }
  }
}
puts "Added $added_pack_sources new pack-fpga source(s) to sources_1"
update_compile_order -fileset sources_1
puts "Vivado launch_runs jobs: $jobs"
puts "Vivado IP/OOC max threads: [get_param general.maxThreads]"

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
# artifacts. A clean run rebuilds them; routine implementation iterations
# validate and reuse the completed products that fed the completed synth_1.
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
if {$expected_cpu_mhz ne "" && abs(double($expected_cpu_mhz) - $configured_cpu_mhz) > 0.001} {
  error [format "Configured CPU clock does not match expected frequency: expected=%.6f actual=%.6f MHz" \
    $expected_cpu_mhz $configured_cpu_mhz]
}
if {$reset_runs} {
  puts "Resetting and regenerating [llength $project_ips] project IP output products"
  reset_target all $project_ips
  generate_target all $project_ips
}
set checkpoint_ip_files [list]
foreach ip_obj $project_ips {
  set ip_name [get_property NAME $ip_obj]
  set ip_files [get_files -quiet -all "*/${ip_name}.xci"]
  if {[llength $ip_files] != 1} {
    error "Expected one XCI for $ip_name, got [llength $ip_files]"
  }
  set ip_file [lindex $ip_files 0]
  if {[get_property GENERATE_SYNTH_CHECKPOINT $ip_file]} {
    lappend checkpoint_ip_files $ip_file
  }
}
set checkpoint_runs [list]
foreach ip_file $checkpoint_ip_files {
  set ip_name [file rootname [file tail $ip_file]]
  set run_name "${ip_name}_synth_1"
  if {[llength [get_runs -quiet $run_name]] == 0} {
    create_ip_run $ip_file
  }
  set ip_run [get_runs -quiet $run_name]
  if {[llength $ip_run] == 1} {
    lappend checkpoint_runs $ip_run
  } elseif {$reset_runs} {
    error "Failed to create required clean IP synthesis run: $run_name"
  } else {
    puts "$ip_name has up-to-date cached synthesis output and no OOC run object; reusing it"
  }
}
if {$reset_runs} {
  puts "Rebuilding [llength $checkpoint_runs] project IP synthesis checkpoints with at most $ip_jobs concurrent run(s)"
  if {[llength $checkpoint_runs] > 0} {
    reset_run $checkpoint_runs
    launch_runs $checkpoint_runs -jobs $ip_jobs
    wait_on_run $checkpoint_runs
  }
} else {
  puts "Reusing [llength $checkpoint_runs] completed project IP synthesis checkpoints"
}
if {[llength $checkpoint_runs] > 0} {
  foreach run_obj $checkpoint_runs {
    set run_name [get_property NAME $run_obj]
    set run_status [get_property STATUS $run_obj]
    set run_progress [get_property PROGRESS $run_obj]
    puts "$run_name STATUS: $run_status"
    puts "$run_name PROGRESS: $run_progress"
    if {$run_progress ne "100%" || [string match -nocase {*failed*} $run_status]} {
      error "$run_name is not complete; rerun with --reset-runs"
    }
  }
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

set_param general.maxThreads $jobs
puts "Vivado top synthesis/implementation max threads: [get_param general.maxThreads]"

if {[llength [get_runs synth_1]] == 0} {
  error "Vivado run synth_1 was not found"
}
if {[llength [get_runs impl_1]] == 0} {
  error "Vivado run impl_1 was not found"
}

set impl_run [get_runs impl_1]
if {$flow_profile eq "quick"} {
  set_property STRATEGY Flow_Quick $impl_run
  set_property STEPS.POST_ROUTE_PHYS_OPT_DESIGN.IS_ENABLED true $impl_run
  set_property STEPS.POST_ROUTE_PHYS_OPT_DESIGN.ARGS.DIRECTIVE ExploreWithAggressiveHoldFix $impl_run
  puts "impl_1 profile: quick; strategy=[get_property STRATEGY $impl_run]; post-route physopt=ExploreWithAggressiveHoldFix"
} elseif {$flow_profile eq "default"} {
  set_property STRATEGY {Vivado Implementation Defaults} $impl_run
  set_property STEPS.POST_ROUTE_PHYS_OPT_DESIGN.IS_ENABLED false $impl_run
  puts "impl_1 profile: default; strategy=[get_property STRATEGY $impl_run]; post-route physopt disabled"
} elseif {$post_route_phys_opt_directive eq "Disabled"} {
  set_property STEPS.POST_ROUTE_PHYS_OPT_DESIGN.IS_ENABLED false $impl_run
  puts "impl_1 post-route physopt: disabled"
} else {
  set_property STEPS.POST_ROUTE_PHYS_OPT_DESIGN.IS_ENABLED true $impl_run
  set_property STEPS.POST_ROUTE_PHYS_OPT_DESIGN.ARGS.DIRECTIVE $post_route_phys_opt_directive $impl_run
  puts "impl_1 post-route physopt: enabled, directive=$post_route_phys_opt_directive"
}
if {$place_directive ne ""} {
  set_property STEPS.PLACE_DESIGN.ARGS.DIRECTIVE $place_directive $impl_run
  puts "impl_1 place directive: $place_directive"
}
if {$route_directive ne ""} {
  set_property STEPS.ROUTE_DESIGN.ARGS.DIRECTIVE $route_directive $impl_run
  puts "impl_1 route directive: $route_directive"
}
if {$pre_route_phys_opt_directive ne ""} {
  set_property STEPS.PHYS_OPT_DESIGN.ARGS.DIRECTIVE $pre_route_phys_opt_directive $impl_run
  puts "impl_1 pre-route physopt directive: $pre_route_phys_opt_directive"
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

set synth_run [get_runs synth_1]
set_property STEPS.SYNTH_DESIGN.ARGS.RETIMING [expr {$synth_global_retiming ? "true" : "false"}] $synth_run
set_property STEPS.SYNTH_DESIGN.ARGS.KEEP_EQUIVALENT_REGISTERS \
  [expr {$synth_keep_equivalent_registers ? "true" : "false"}] $synth_run
if {$synth_flatten_hierarchy ne ""} {
  set_property STEPS.SYNTH_DESIGN.ARGS.FLATTEN_HIERARCHY $synth_flatten_hierarchy $synth_run
  puts "synth_1 flatten hierarchy: $synth_flatten_hierarchy"
}
puts "synth_1 global retiming: $synth_global_retiming; keep equivalent registers: $synth_keep_equivalent_registers"
if {[lsearch -exact [list_property $synth_run] AUTO_INCREMENTAL_CHECKPOINT] >= 0} {
  # Vivado 2024.2 exposes this run property as an integer.  Passing the Tcl
  # boolean string "false" fails with "bad lexical cast" before synthesis.
  set_property AUTO_INCREMENTAL_CHECKPOINT 0 $synth_run
}

if {$reset_runs} {
  # Resetting synthesis also invalidates and resets the dependent impl_1 run.
  puts "Resetting synth_1 and dependent impl_1 for a clean rebuild"
  reset_run synth_1
  launch_runs synth_1 -jobs $jobs
} elseif {$mode eq "impl" || $reuse_ip} {
  # Routine RTL iterations reuse IP/OOC output products, but the top-level
  # checkpoint must always be rebuilt from the freshly packaged sources.
  puts "Resetting top-level synth_1 and dependent impl_1; reusing IP/OOC checkpoints"
  reset_run synth_1
  launch_runs synth_1 -jobs $jobs
} else {
  set synth_status [get_property STATUS $synth_run]
  set synth_progress [get_property PROGRESS $synth_run]
  if {$synth_progress ne "100%" || ![string match -nocase {*Complete*} $synth_status]} {
    error "synth_1 is not complete; rerun with --reset-runs: status=$synth_status progress=$synth_progress"
  }
  puts "Reusing completed synth_1 checkpoint for bitstream continuation"
}

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
"$vivado_bin" -mode batch -source "$tcl_file" -tclargs \
  "$mode" "$jobs" "$ip_jobs" "$pack_dst" "$reset_runs" "$reuse_ip" \
  "$expected_cpu_mhz" "$flow_profile" \
  "$synth_global_retiming" "$synth_keep_equivalent_registers" \
  "$synth_flatten_hierarchy" "$place_directive" "$route_directive" \
  "$pre_route_phys_opt_directive" "$post_route_phys_opt_directive"
vivado_status=$?
set -e

# Opening a project from a worktree causes Vivado to persist relocated paths
# in the XPR. Runs and reports are outputs, but the versioned project is an
# input and must remain byte-identical for reproducible A/B comparisons.
cp -- "$xpr_backup" "$vivado_project"
rm -f -- "$xpr_backup"
xpr_backup=""
xpr_hash_after=$(sha256sum "$vivado_project" | awk '{print $1}')
if [ "$xpr_hash_before" != "$xpr_hash_after" ]; then
  echo "Vivado project file was not restored after the run:" >&2
  echo "  before: $xpr_hash_before" >&2
  echo "  after:  $xpr_hash_after" >&2
  exit 1
fi

ip_config_hash_after=$(ip_config_hash)
if [ "$ip_config_hash_before" != "$ip_config_hash_after" ]; then
  echo "Vivado IP configuration changed during the run:" >&2
  echo "  before: $ip_config_hash_before" >&2
  echo "  after:  $ip_config_hash_after" >&2
  exit 1
fi
if [ "$vivado_status" -ne 0 ]; then
  exit "$vivado_status"
fi

if [ "$mode" = write_bitstream ]; then
  impl_dir="$vivado_proj_home/digital_twin.runs/impl_1"
  raw_bit="$impl_dir/top.bit"
  if [ "$flow_profile" = default ] || [ "$post_route_phys_opt_directive" = Disabled ]; then
    routed_dcp="$impl_dir/top_routed.dcp"
  else
    routed_dcp="$impl_dir/top_postroute_physopt.dcp"
  fi
  replace_tool="$repo_root/coe_replace/coe_replace.py"
  replaced_bit="$impl_dir/top.cur-coe.bit"
  raw_saved_bit="$impl_dir/top.project-init.bit"
  manifest="$impl_dir/top.bit.coe-manifest"
  replace_workdir=$(mktemp -d "${TMPDIR:-/tmp}/coe-replace-flow.XXXXXX")

  for required_file in "$raw_bit" "$routed_dcp" "$replace_tool"; do
    if [ ! -f "$required_file" ]; then
      echo "Required COE replacement input does not exist: $required_file" >&2
      exit 1
    fi
  done

  cp -- "$raw_bit" "$raw_saved_bit"
  echo "# Replacing bitstream memories from explicit COE inputs"
  sha256sum "$irom_coe" "$dram_coe"
  python3 "$replace_tool" \
    --dcp "$routed_dcp" \
    --irom-coe "$irom_coe" \
    --dram-coe "$dram_coe" \
    --out "$replaced_bit" \
    --bit "$raw_saved_bit" \
    --vivado "$vivado_bin" \
    --workdir "$replace_workdir"
  mv -- "$replaced_bit" "$raw_bit"
  {
    echo "irom_coe=$irom_coe"
    echo "irom_sha256=$(sha256sum "$irom_coe" | awk '{print $1}')"
    echo "dram_coe=$dram_coe"
    echo "dram_sha256=$(sha256sum "$dram_coe" | awk '{print $1}')"
    echo "project_init_bit=$raw_saved_bit"
    echo "project_init_bit_sha256=$(sha256sum "$raw_saved_bit" | awk '{print $1}')"
    echo "output_bit=$raw_bit"
    echo "output_bit_sha256=$(sha256sum "$raw_bit" | awk '{print $1}')"
    echo "implementation_checkpoint=$routed_dcp"
    echo "post_route_phys_opt_directive=$post_route_phys_opt_directive"
  } >"$manifest"
  echo "# Bitstream COE manifest: $manifest"
  cat "$manifest"
fi
