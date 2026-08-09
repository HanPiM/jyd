#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  run_digital_twin_board_perf.sh run --coe-dir DIR [options]
  run_digital_twin_board_perf.sh calibrate --coe-dir DIR [options]

Commands:
  run         Build and, by default, board-test one profile.
  calibrate   Compare quick-75 with default-200, falling back to default-150
              only when default-200 is not timing-closed and board-valid.

Options:
  --coe-dir DIR            Directory containing irom.coe and dram.coe (required).
  --profile NAME           auto, quick-75, default-200, or default-150 (run only).
                           auto uses the calibrated profile, or quick-75 before calibration.
  --jobs N                 Vivado top-level jobs. Defaults to JOBS or nproc.
  --ip-jobs N              Vivado IP/OOC jobs. Defaults to IP_JOBS or 4.
  --fpga NAME              Force jyd-client to use a named FPGA.
  --no-board               Generate and archive the bitstream without board capture (run only).
  --prepare-only           Stop after creating and configuring the isolated project.
  --keep-workdir           Retain the isolated project after a successful run.
  --first-byte-timeout N   UART first-byte timeout in seconds. Defaults to 75.
  --capture-duration N     UART capture duration after the first byte. Defaults to 90.
  -h, --help               Show this help.

Environment:
  VIVADO                    Vivado executable. Defaults to vivado.
  JYD_DATA_ROOT             JYD data root. Defaults to /srv/data/jyd.
  SUBMIT_BITS_ROOT          submit-bits checkout. Defaults to /home/hanpi/gitclone/submit-bits.
EOF
}

die() {
  echo "error: $*" >&2
  exit 1
}

positive_integer() {
  [[ "$1" =~ ^[1-9][0-9]*$ ]]
}

positive_number() {
  [[ "$1" =~ ^[0-9]+([.][0-9]+)?$ ]] && awk -v value="$1" 'BEGIN { exit !(value > 0) }'
}

profile_settings() {
  case "$1" in
    quick-75)
      PROFILE_CPU_MHZ=75
      PROFILE_FLOW=quick
      ;;
    default-200)
      PROFILE_CPU_MHZ=200
      PROFILE_FLOW=default
      ;;
    default-150)
      PROFILE_CPU_MHZ=150
      PROFILE_FLOW=default
      ;;
    *)
      return 1
      ;;
  esac
}

command_name="${1:-}"
case "$command_name" in
  run | calibrate) shift ;;
  -h | --help | "")
    usage
    exit 0
    ;;
  *)
    echo "Unknown command: $command_name" >&2
    usage >&2
    exit 2
    ;;
esac

coe_dir=""
profile=auto
jobs="${JOBS:-$(nproc 2>/dev/null || echo 4)}"
ip_jobs="${IP_JOBS:-4}"
fpga_name=""
run_board=1
prepare_only=0
keep_workdir=0
first_byte_timeout=75
capture_duration=90

while [ "$#" -gt 0 ]; do
  case "$1" in
    --coe-dir)
      [ "$#" -ge 2 ] || die "missing value for --coe-dir"
      coe_dir="$2"
      shift 2
      ;;
    --profile)
      [ "$#" -ge 2 ] || die "missing value for --profile"
      profile="$2"
      shift 2
      ;;
    --jobs)
      [ "$#" -ge 2 ] || die "missing value for --jobs"
      jobs="$2"
      shift 2
      ;;
    --ip-jobs)
      [ "$#" -ge 2 ] || die "missing value for --ip-jobs"
      ip_jobs="$2"
      shift 2
      ;;
    --fpga)
      [ "$#" -ge 2 ] || die "missing value for --fpga"
      fpga_name="$2"
      shift 2
      ;;
    --no-board)
      run_board=0
      shift
      ;;
    --prepare-only)
      prepare_only=1
      keep_workdir=1
      shift
      ;;
    --keep-workdir)
      keep_workdir=1
      shift
      ;;
    --first-byte-timeout)
      [ "$#" -ge 2 ] || die "missing value for --first-byte-timeout"
      first_byte_timeout="$2"
      shift 2
      ;;
    --capture-duration)
      [ "$#" -ge 2 ] || die "missing value for --capture-duration"
      capture_duration="$2"
      shift 2
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

[ -n "$coe_dir" ] || die "--coe-dir is required"
coe_dir_input="$coe_dir"
coe_dir=$(CDPATH= cd -- "$coe_dir_input" && pwd) || die "COE directory does not exist: $coe_dir_input"
for coe_file in irom.coe dram.coe; do
  [ -f "$coe_dir/$coe_file" ] || die "required COE file not found: $coe_dir/$coe_file"
done
positive_integer "$jobs" || die "--jobs must be a positive integer: $jobs"
positive_integer "$ip_jobs" || die "--ip-jobs must be a positive integer: $ip_jobs"
positive_number "$first_byte_timeout" || die "--first-byte-timeout must be positive: $first_byte_timeout"
positive_number "$capture_duration" || die "--capture-duration must be positive: $capture_duration"

if [ "$command_name" = calibrate ]; then
  [ "$profile" = auto ] || die "--profile is only valid with the run command"
  [ "$run_board" -eq 1 ] || die "calibration requires board capture; --no-board is not supported"
  [ "$prepare_only" -eq 0 ] || die "--prepare-only is only valid with the run command"
fi

case "$profile" in
  auto | quick-75 | default-200 | default-150) ;;
  *) die "unsupported profile: $profile" ;;
esac

script_dir=$(CDPATH= cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
npc_dir=$(CDPATH= cd -- "$script_dir/.." && pwd)
repo_root=$(CDPATH= cd -- "$npc_dir/.." && pwd)
source_project="$repo_root/jyd-vivado-proj"
vivado_runner="$script_dir/run_digital_twin_vivado.sh"
vivado_bin="${VIVADO:-vivado}"
jyd_data_root="${JYD_DATA_ROOT:-/srv/data/jyd}"
tmp_root="$jyd_data_root/tmp"
archive_root="$jyd_data_root/archive"
cache_root="$jyd_data_root/cache/digital-twin-board-perf"
selected_profile_file="$cache_root/selected-profile"
submit_bits_root="${SUBMIT_BITS_ROOT:-/home/hanpi/gitclone/submit-bits}"
submit_client="$submit_bits_root/.venv/bin/python3"

[ -f "$source_project/digital_twin.xpr" ] || die "Vivado project not found: $source_project"
[ -x "$vivado_runner" ] || die "Vivado runner is not executable: $vivado_runner"
command -v "$vivado_bin" >/dev/null 2>&1 || die "Vivado executable not found: $vivado_bin"
if [ "$run_board" -eq 1 ]; then
  [ -x "$submit_client" ] || die "submit-bits client not found: $submit_client"
fi
mkdir -p -- "$tmp_root" "$archive_root" "$cache_root"

if [ "$profile" = auto ]; then
  if [ -f "$selected_profile_file" ]; then
    profile=$(tr -d '[:space:]' <"$selected_profile_file")
    profile_settings "$profile" || die "invalid calibrated profile in $selected_profile_file: $profile"
  else
    profile=quick-75
  fi
fi

echo "# Building current pack-fpga once for the frozen input"
make -C "$npc_dir" pack-fpga
pack_src="$npc_dir/build/pack-fpga"
[ -d "$pack_src" ] || die "pack-fpga output not found: $pack_src"

run_stamp=$(date -u +%Y%m%dT%H%M%SZ)
batch_archive="$archive_root/digital-twin-board-perf-$run_stamp"
mkdir -p -- "$batch_archive"

copy_project_inputs() {
  local project_dir="$1"
  local source_path relative_path target_path

  mkdir -p -- "$project_dir"
  cp -a -- "$source_project/digital_twin.xpr" "$project_dir/"
  for source_path in \
    "$source_project/digital_twin.srcs/constrs_1" \
    "$source_project/digital_twin.srcs/sim_1" \
    "$source_project/digital_twin.srcs/sources_1/new" \
    "$source_project/jyd-coes"; do
    if [ -e "$source_path" ]; then
      target_path="$project_dir/${source_path#"$source_project/"}"
      mkdir -p -- "$(dirname -- "$target_path")"
      cp -a -- "$source_path" "$target_path"
    fi
  done

  while IFS= read -r -d '' source_path; do
    relative_path=${source_path#"$source_project/"}
    target_path="$project_dir/$relative_path"
    mkdir -p -- "$(dirname -- "$target_path")"
    cp -a -- "$source_path" "$target_path"
  done < <(find "$source_project/digital_twin.srcs/sources_1/ip" -type f -name '*.xci' -print0 | sort -z)

  mkdir -p -- "$project_dir/digital_twin.srcs/sources_1/imports"
  cp -a -- "$pack_src" "$project_dir/digital_twin.srcs/sources_1/imports/pack-fpga"
  mkdir -p -- "$project_dir/digital_twin.srcs/sources_1/imports/cur_coe"
  cp -a -- "$coe_dir/irom.coe" "$coe_dir/dram.coe" \
    "$project_dir/digital_twin.srcs/sources_1/imports/cur_coe/"
}

seed_ip_build_state() {
  local project_dir="$1"
  local cache_dir run_dir

  for cache_dir in digital_twin.gen digital_twin.cache digital_twin.ip_user_files; do
    if [ -d "$source_project/$cache_dir" ]; then
      cp -a --reflink=auto -- "$source_project/$cache_dir" "$project_dir/"
    fi
  done

  mkdir -p -- "$project_dir/digital_twin.runs"
  while IFS= read -r -d '' run_dir; do
    cp -a --reflink=auto -- "$run_dir" "$project_dir/digital_twin.runs/"
  done < <(find "$source_project/digital_twin.runs" -mindepth 1 -maxdepth 1 \
    -type d -name '*_synth_1' -print0 2>/dev/null | sort -z)
}

configure_clock() {
  local project_dir="$1"
  local cpu_mhz="$2"
  local config_tcl="$3"

  cat >"$config_tcl" <<'EOF'
if {$argc != 2} {
  error "Expected Tcl args: <project-path> <cpu-mhz>"
}
set project_path [file normalize [lindex $argv 0]]
set cpu_mhz [lindex $argv 1]
open_project $project_path
set pll_ip [get_ips -quiet mypll]
if {[llength $pll_ip] != 1} {
  error "Expected exactly one mypll IP, got [llength $pll_ip]"
}
set_property CONFIG.CLKOUT2_REQUESTED_OUT_FREQ $cpu_mhz $pll_ip
set requested_cpu [get_property CONFIG.CLKOUT2_REQUESTED_OUT_FREQ $pll_ip]
set requested_peripheral [get_property CONFIG.CLKOUT1_REQUESTED_OUT_FREQ $pll_ip]
puts "CLOCK_PROFILE_CPU_MHZ=$requested_cpu"
puts "CLOCK_PROFILE_PERIPHERAL_MHZ=$requested_peripheral"
if {abs(double($requested_cpu) - double($cpu_mhz)) > 0.001} {
  error "Clock Wizard rejected requested CPU frequency $cpu_mhz MHz"
}
if {abs(double($requested_peripheral) - 50.0) > 0.001} {
  error "Clock Wizard peripheral output is not 50 MHz: $requested_peripheral"
}
close_project
EOF

  (
    cd "$project_dir"
    "$vivado_bin" -mode batch -nolog -nojournal -notrace \
      -source "$config_tcl" -tclargs "$project_dir/digital_twin.xpr" "$cpu_mhz"
  )
}

write_input_manifest() {
  local project_dir="$1"
  local output_file="$2"
  local input_file relative_path

  : >"$output_file"
  while IFS= read -r -d '' input_file; do
    relative_path=${input_file#"$project_dir/"}
    printf '%s  %s\n' "$(sha256sum "$input_file" | awk '{print $1}')" "$relative_path" >>"$output_file"
  done < <(
    find "$project_dir" -type f \
      ! -path '*/digital_twin.runs/*' \
      ! -path '*/digital_twin.gen/*' \
      ! -path '*/digital_twin.cache/*' \
      ! -path '*/.Xil/*' -print0 | sort -z
  )
}

archive_run_artifacts() {
  local project_dir="$1"
  local run_archive="$2"
  local artifact relative_path target_path

  mkdir -p -- "$run_archive/artifacts"
  while IFS= read -r -d '' artifact; do
    relative_path=${artifact#"$project_dir/"}
    target_path="$run_archive/artifacts/$relative_path"
    mkdir -p -- "$(dirname -- "$target_path")"
    cp -a -- "$artifact" "$target_path"
  done < <(
    {
      find \
        "$project_dir/digital_twin.runs/synth_1" \
        "$project_dir/digital_twin.runs/impl_1" \
        -type f \( \
        -name '*.bit' -o -name '*.dcp' -o -name '*timing*.rpt' -o \
        -name 'runme.log' -o -name 'runme.jou' -o -name 'vivado.log' -o -name 'vivado.jou' \
        \) -print0 2>/dev/null
      find "$project_dir" -type f \( -name 'vivado.log' -o -name 'vivado.jou' -o -name 'runme.log' \) \
        -print0 2>/dev/null
    } | sort -zu
  )
}

parse_timing() {
  local report_path="$1"
  local timing_row

  timing_row=$(awk '/^[[:space:]]*-?[0-9]+[.][0-9]+[[:space:]]/ {print $1, $2, $3, $5, $6, $7; exit}' "$report_path")
  [ -n "$timing_row" ] || return 1
  read -r LAST_WNS LAST_TNS LAST_SETUP_ENDPOINTS LAST_WHS LAST_THS LAST_HOLD_ENDPOINTS <<<"$timing_row"
  awk -v wns="$LAST_WNS" -v tns="$LAST_TNS" -v se="$LAST_SETUP_ENDPOINTS" \
    -v whs="$LAST_WHS" -v ths="$LAST_THS" -v he="$LAST_HOLD_ENDPOINTS" \
    'BEGIN { exit !(wns >= 0 && tns == 0 && se == 0 && whs >= 0 && ths == 0 && he == 0) }'
}

capture_board() {
  local bitstream="$1"
  local run_archive="$2"
  local -a capture_args

  capture_args=(
    -m jyd_client.cli capture "$bitstream" --skip-login
    --first-byte-timeout "$first_byte_timeout"
    --duration "$capture_duration"
  )
  if [ -n "$fpga_name" ]; then
    capture_args+=(--fpga "$fpga_name")
  fi

  set +e
  (
    cd "$submit_bits_root"
    "$submit_client" "${capture_args[@]}"
  ) > >(tee "$run_archive/uart.log") 2> >(tee "$run_archive/capture.stderr.log" >&2)
  LAST_CAPTURE_STATUS=$?
  set -e
  return "$LAST_CAPTURE_STATUS"
}

LAST_PROFILE=""
LAST_IMPL_SECONDS=""
LAST_FLOW_SECONDS=""
LAST_RUNTIME_SECONDS=""
LAST_SCORE=""
LAST_ELIGIBLE=0
LAST_ARCHIVE=""
LAST_WORKDIR=""
LAST_WNS=""
LAST_TNS=""
LAST_SETUP_ENDPOINTS=""
LAST_WHS=""
LAST_THS=""
LAST_HOLD_ENDPOINTS=""
LAST_CAPTURE_STATUS=0

execute_profile() {
  local selected_profile="$1"
  local run_archive="$batch_archive/$selected_profile"
  local work_dir project_dir config_tcl runner_log bitstream timing_report
  local vivado_status=0 timing_ok=0 uart_ok=0 flow_start_ns flow_end_ns

  profile_settings "$selected_profile" || die "unsupported profile: $selected_profile"
  work_dir=$(mktemp -d "$tmp_root/digital-twin-board-perf.${selected_profile}.XXXXXX")
  project_dir="$work_dir/jyd-vivado-proj"
  config_tcl="$work_dir/configure-clock.tcl"
  mkdir -p -- "$run_archive"

  LAST_PROFILE="$selected_profile"
  LAST_ARCHIVE="$run_archive"
  LAST_WORKDIR="$work_dir"
  LAST_IMPL_SECONDS=""
  LAST_FLOW_SECONDS=""
  LAST_RUNTIME_SECONDS=""
  LAST_SCORE=""
  LAST_ELIGIBLE=0

  {
    echo "profile=$selected_profile"
    echo "cpu_mhz=$PROFILE_CPU_MHZ"
    echo "peripheral_mhz=50"
    echo "flow_profile=$PROFILE_FLOW"
    echo "coe_dir=$coe_dir"
    echo "work_dir=$work_dir"
    echo "repo_commit=$(git -C "$repo_root" rev-parse HEAD)"
    echo "vivado_version=$($vivado_bin -version | head -n 1)"
    echo "started_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  } >"$run_archive/metadata.env"
  git -C "$repo_root" status --short >"$run_archive/git-status.txt"

  echo "# [$selected_profile] copying isolated Vivado project inputs"
  copy_project_inputs "$project_dir"
  echo "# [$selected_profile] seeding reusable IP output products and OOC runs"
  seed_ip_build_state "$project_dir"
  configure_clock "$project_dir" "$PROFILE_CPU_MHZ" "$config_tcl" | tee "$run_archive/configure-clock.log"
  write_input_manifest "$project_dir" "$run_archive/input-sha256.txt"

  if [ "$prepare_only" -eq 1 ]; then
    echo "prepared_project=$project_dir" | tee -a "$run_archive/metadata.env"
    echo "# [$selected_profile] prepared isolated project: $project_dir"
    return 0
  fi

  runner_log="$run_archive/vivado-runner.log"
  flow_start_ns=$(date +%s%N)
  set +e
  "$vivado_runner" bitstream \
    --project-root "$project_dir" \
    --expected-cpu-mhz "$PROFILE_CPU_MHZ" \
    --flow-profile "$PROFILE_FLOW" \
    --jobs "$jobs" --ip-jobs "$ip_jobs" --reuse-ip --skip-pack 2>&1 | tee "$runner_log"
  vivado_status=${PIPESTATUS[0]}
  set -e
  flow_end_ns=$(date +%s%N)
  LAST_FLOW_SECONDS=$(awk -v start="$flow_start_ns" -v end="$flow_end_ns" \
    'BEGIN { printf "%.3f", (end - start) / 1000000000.0 }')

  archive_run_artifacts "$project_dir" "$run_archive"
  echo "vivado_status=$vivado_status" >>"$run_archive/metadata.env"
  if [ "$vivado_status" -ne 0 ]; then
    echo "# [$selected_profile] Vivado failed; retaining $work_dir" >&2
    return 1
  fi

  LAST_IMPL_SECONDS=$(awk -F= '/^IMPL_ELAPSED_SECONDS=/ {value=$2} END {print value}' "$runner_log")
  [ -n "$LAST_IMPL_SECONDS" ] || die "implementation elapsed time missing from $runner_log"
  bitstream=$(find "$project_dir/digital_twin.runs/impl_1" -maxdepth 1 -type f -name '*.bit' -print -quit)
  [ -n "$bitstream" ] || die "bitstream not found for $selected_profile"

  timing_report="$project_dir/digital_twin.runs/impl_1/top_timing_summary_postroute_physopted.rpt"
  if [ ! -f "$timing_report" ]; then
    timing_report="$project_dir/digital_twin.runs/impl_1/top_timing_summary_routed.rpt"
  fi
  [ -f "$timing_report" ] || die "routed timing report not found: $timing_report"
  python3 "$repo_root/jyd-vivado-proj/scripts/extract-timing-summary.py" "$timing_report" \
    | tee "$run_archive/timing-summary.txt"
  if parse_timing "$timing_report"; then
    timing_ok=1
  fi

  cp -a -- "$bitstream" "$run_archive/top.bit"
  sha256sum "$run_archive/top.bit" >"$run_archive/bitstream-sha256.txt"

  if [ "$run_board" -eq 1 ]; then
    if capture_board "$run_archive/top.bit" "$run_archive"; then
      if grep -Fq 'Correct operation validated.' "$run_archive/uart.log"; then
        LAST_RUNTIME_SECONDS=$(awk -F: '/Total time \(secs\)/ {value=$2; gsub(/[[:space:]]/, "", value)} END {print value}' \
          "$run_archive/uart.log")
        if positive_number "$LAST_RUNTIME_SECONDS"; then
          uart_ok=1
        fi
      fi
    fi
  fi

  if [ "$run_board" -eq 0 ]; then
    LAST_RUNTIME_SECONDS=""
  elif [ "$uart_ok" -ne 1 ]; then
    echo "# [$selected_profile] UART capture did not contain a complete CoreMark PASS/runtime" >&2
  fi

  if [ "$timing_ok" -eq 1 ] && [ "$uart_ok" -eq 1 ]; then
    LAST_ELIGIBLE=1
    LAST_SCORE=$(awk -v flow="$LAST_FLOW_SECONDS" -v runtime="$LAST_RUNTIME_SECONDS" \
      'BEGIN { printf "%.6f", flow + runtime }')
  fi

  {
    echo "impl_elapsed_seconds=$LAST_IMPL_SECONDS"
    echo "flow_elapsed_seconds=$LAST_FLOW_SECONDS"
    echo "runtime_seconds=$LAST_RUNTIME_SECONDS"
    echo "score_seconds=$LAST_SCORE"
    echo "timing_eligible=$timing_ok"
    echo "uart_eligible=$uart_ok"
    echo "eligible=$LAST_ELIGIBLE"
    echo "wns_ns=$LAST_WNS"
    echo "tns_ns=$LAST_TNS"
    echo "setup_failing_endpoints=$LAST_SETUP_ENDPOINTS"
    echo "whs_ns=$LAST_WHS"
    echo "ths_ns=$LAST_THS"
    echo "hold_failing_endpoints=$LAST_HOLD_ENDPOINTS"
    if [ -n "$LAST_RUNTIME_SECONDS" ]; then
      awk -v runtime="$LAST_RUNTIME_SECONDS" -v mhz="$PROFILE_CPU_MHZ" \
        'BEGIN { printf "estimated_280mhz_seconds=%.6f\n", runtime * mhz / 280.0 }'
    fi
    echo "finished_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  } >>"$run_archive/metadata.env"

  if [ "$keep_workdir" -eq 0 ]; then
    rm -rf -- "$work_dir"
    LAST_WORKDIR=""
  fi

  if [ "$run_board" -eq 1 ] && [ "$LAST_ELIGIBLE" -ne 1 ]; then
    return 1
  fi
  return 0
}

if [ "$command_name" = run ]; then
  execute_profile "$profile"
  echo "# Result archive: $LAST_ARCHIVE"
  if [ -n "$LAST_WORKDIR" ]; then
    echo "# Isolated project retained: $LAST_WORKDIR"
  fi
  exit 0
fi

echo "# Calibration input archive: $batch_archive"
execute_profile quick-75 || die "quick-75 calibration baseline failed; see $LAST_ARCHIVE"
quick_score="$LAST_SCORE"
quick_archive="$LAST_ARCHIVE"

default_profile=default-200
default_ok=0
if execute_profile "$default_profile"; then
  default_ok=1
else
  echo "# default-200 was not eligible; evaluating default-150"
  default_profile=default-150
  if execute_profile "$default_profile"; then
    default_ok=1
  fi
fi

selected_profile=quick-75
selection_reason="Default profile was not timing-closed and board-valid."
if [ "$default_ok" -eq 1 ]; then
  default_score="$LAST_SCORE"
  if awk -v candidate="$default_score" -v baseline="$quick_score" 'BEGIN { exit !(candidate < baseline) }'; then
    selected_profile="$default_profile"
    selection_reason="$default_profile score $default_score was lower than quick-75 score $quick_score."
  else
    selection_reason="$default_profile score $default_score was not lower than quick-75 score $quick_score."
  fi
fi

{
  echo "selected_profile=$selected_profile"
  echo "quick_profile=quick-75"
  echo "quick_score_seconds=$quick_score"
  echo "quick_archive=$quick_archive"
  echo "default_profile=$default_profile"
  echo "default_eligible=$default_ok"
  echo "default_score_seconds=${default_score:-}"
  echo "reason=$selection_reason"
} | tee "$batch_archive/calibration-summary.env"

selected_profile_tmp="$cache_root/selected-profile.tmp.$$"
printf '%s\n' "$selected_profile" >"$selected_profile_tmp"
mv -f -- "$selected_profile_tmp" "$selected_profile_file"
cp -a -- "$batch_archive/calibration-summary.env" "$cache_root/calibration-summary.env"

echo "# Selected profile: $selected_profile"
echo "# Calibration archive: $batch_archive"
