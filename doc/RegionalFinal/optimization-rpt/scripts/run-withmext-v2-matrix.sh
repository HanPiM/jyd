#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT=${REPO_ROOT:-/home/hanpi/gitclone/jyd}
REPORT_DIR=${REPORT_DIR:-/home/hanpi/gitclone/jyd/doc/RegionalFinal/optimization-rpt}
INPUT_DIR=${INPUT_DIR:-/home/hanpi/gitclone/jyd/jyd-tests/2026/bin}
BUILD_CPUSET=${BUILD_CPUSET:-16-31}
SIM_CPUSET=${SIM_CPUSET:-0-15}
MAX_PARALLEL=${MAX_PARALLEL:-4}
START_INDEX=${START_INDEX:-0}

IMAGE_NAME=withMext-v2.bin
DATA_NAME=withMext-v2.data.bin
IMAGE_SHA256=b998eae7b2e6c3dd5911bc67927cea4e9c4f8a1bcb639391c520ba3391742749
DATA_SHA256=2ab9a4c9e7c2cc1b785637f6df83d1cbe0b175c838b478dedbfd30516de24e80

LABELS=(
  divider
  timing280
  dcache_shadow
  btb32
  btb_shadow
  exact_ret
  partial_store
  ret_bypass
  alu_div_correct
  wbu_addr
  dcache_addr
  final
)
COMMITS=(
  85d3dad
  11f93f5
  906249b
  8eb6b8c
  a72b1f9
  f5143a0
  d205c9a
  3fd8030
  c4279e4
  e3a7baa
  9343d53
  58f371d
)
END_INDEX=${END_INDEX:-${#COMMITS[@]}}

stamp() { date '+%F %T'; }
log() { printf '[%s] %s\n' "$(stamp)" "$*"; }
die() { log "ERROR: $*" >&2; exit 1; }

command -v jq >/dev/null || die "jq is required"
command -v taskset >/dev/null || die "taskset is required"
[[ -d $REPO_ROOT/.git ]] || die "repository not found: $REPO_ROOT"
[[ -f $INPUT_DIR/$IMAGE_NAME ]] || die "missing image: $INPUT_DIR/$IMAGE_NAME"
[[ -f $INPUT_DIR/$DATA_NAME ]] || die "missing data image: $INPUT_DIR/$DATA_NAME"

actual_image_sha=$(sha256sum "$INPUT_DIR/$IMAGE_NAME" | awk '{print $1}')
actual_data_sha=$(sha256sum "$INPUT_DIR/$DATA_NAME" | awk '{print $1}')
[[ $actual_image_sha == "$IMAGE_SHA256" ]] || die "unexpected $IMAGE_NAME hash: $actual_image_sha"
[[ $actual_data_sha == "$DATA_SHA256" ]] || die "unexpected $DATA_NAME hash: $actual_data_sha"

BENCH_ROOT=${BENCH_ROOT:-$(mktemp -d /tmp/jyd-withmext-v2-bench.XXXXXX)}
CLONE_DIR=$BENCH_ROOT/repo
RUNS_DIR=$BENCH_ROOT/runs
INPUT_COPY=$BENCH_ROOT/input
RESULTS_DIR=$REPORT_DIR/results
CSV_TMP=$BENCH_ROOT/withmext-v2-rerun.csv

mkdir -p "$RUNS_DIR" "$INPUT_COPY" "$RESULTS_DIR"
if [[ ! -d $CLONE_DIR/.git ]]; then
  cp "$INPUT_DIR/$IMAGE_NAME" "$INPUT_DIR/$DATA_NAME" "$INPUT_COPY/"
  git clone --shared --no-checkout "$REPO_ROOT" "$CLONE_DIR"
  if [[ -f $REPO_ROOT/npc/deps/clone.done ]]; then
    mkdir -p "$CLONE_DIR/npc"
    cp -a "$REPO_ROOT/npc/deps" "$CLONE_DIR/npc/deps"
  else
    die "local npc dependencies are incomplete: $REPO_ROOT/npc/deps"
  fi
fi

if [[ ! -f $CSV_TMP ]]; then
  printf '%s\n' 'label,commit,subject,image_sha256,data_sha256,binary_sha256,exit_status,instruction_count,cycle_count,cpi,seconds_280,raw_result' >"$CSV_TMP"
fi
printf '%s\n' "$BENCH_ROOT" >"$RESULTS_DIR/latest-run-path.txt"
log "raw artifacts: $BENCH_ROOT"

build_candidate() {
  local commit=$1 label=$2 attempt
  git -C "$CLONE_DIR" switch --detach "$commit" >/dev/null
  for attempt in 1 2; do
    log "build $label ($commit), attempt $attempt"
    make -C "$CLONE_DIR/npc" clean >/dev/null
    if env CCACHE_DISABLE=1 taskset -c "$BUILD_CPUSET" make -C "$CLONE_DIR/npc" ARCH=riscv32-jyd VSIM_OPT=-O3 \
        >"$RUNS_DIR/$label-build.log" 2>&1; then
      return 0
    fi
    log "build failed for $label; retrying once"
  done
  die "build failed twice for $label (see $RUNS_DIR/$label-build.log)"
}

prepare_candidate() {
  local label=$1 commit=$2 run_dir=$RUNS_DIR/$label
  mkdir -p "$run_dir/smoke/build/logs" "$run_dir/full/build/logs"
  cp "$CLONE_DIR/npc/build/bin/JYDSoC-jyd-O3" "$run_dir/sim"
  sha256sum "$run_dir/sim" >"$run_dir/sim.sha256"
  git -C "$CLONE_DIR" show -s --format='%H%n%s' "$commit" >"$run_dir/commit.txt"
}

smoke_candidate() {
  local label=$1 run_dir=$RUNS_DIR/$label
  local rc=0
  log "smoke $label (5-second launch check)"
  (cd "$run_dir/smoke" && env MAKE_PERF=1 VSIM_difftest=0 VSIM_showdisasm=0 VSIM_etrace=0 \
      timeout --signal=INT 5s taskset -c "$SIM_CPUSET" ../sim -b "$INPUT_COPY/$IMAGE_NAME" \
      >smoke.log 2>&1) || rc=$?
  if [[ $rc -ne 0 && $rc -ne 124 && $rc -ne 130 ]]; then
    die "smoke failed for $label with exit $rc (see $run_dir/smoke/smoke.log)"
  fi
  grep -q 'sim started in sdb debug mode' "$run_dir/smoke/smoke.log" || \
    die "smoke did not reach simulation start for $label"
}

start_full() {
  local label=$1 run_dir=$RUNS_DIR/$label
  log "start full simulation $label"
  (
    cd "$run_dir/full"
    set +e
    env MAKE_PERF=1 VSIM_difftest=0 VSIM_showdisasm=0 VSIM_etrace=0 \
      taskset -c "$SIM_CPUSET" ../sim -b "$INPUT_COPY/$IMAGE_NAME" >full.log 2>&1
    rc=$?
    printf '%s\n' "$rc" >exit.code
    exit "$rc"
  ) &
  FULL_PID=$!
}

finish_full() {
  local pid=$1 label=$2 commit=$3
  local run_dir=$RUNS_DIR/$label json rc subject instructions cycles cpi seconds binary_sha
  log "wait full simulation $label"
  if wait "$pid"; then rc=0; else rc=$?; fi
  [[ $rc -eq 0 ]] || die "full simulation failed for $label with exit $rc (see $run_dir/full/full.log)"
  json=$(find "$run_dir/full/history_perf" -name counters.rawdata.json -type f | sort | tail -n 1)
  [[ -n $json ]] || die "no performance JSON for $label"
  instructions=$(jq -er '.run.instruction_count // empty' "$json" 2>/dev/null || true)
  cycles=$(jq -er '.run.cycle_count // empty' "$json" 2>/dev/null || true)
  if [[ -z $instructions || -z $cycles ]]; then
    instructions=$(sed -n 's/.*total instruction count: \([0-9][0-9]*\).*/\1/p' "$run_dir/full/full.log" | tail -n 1)
    cycles=$(sed -n 's/.*total cycle count: \([0-9][0-9]*\).*/\1/p' "$run_dir/full/full.log" | tail -n 1)
  fi
  [[ -n $instructions && -n $cycles ]] || die "cannot parse performance counters for $label"
  cpi=$(awk -v c="$cycles" -v i="$instructions" 'BEGIN { printf "%.6f", c/i }')
  seconds=$(awk -v c="$cycles" 'BEGIN { printf "%.9f", c/280000000 }')
  binary_sha=$(awk '{print $1}' "$run_dir/sim.sha256")
  subject=$(git -C "$CLONE_DIR" show -s --format='%s' "$commit" | sed 's/"/""/g')
  printf '"%s","%s","%s","%s","%s","%s",%d,%s,%s,%s,%s,"%s"\n' \
    "$label" "$(git -C "$CLONE_DIR" rev-parse "$commit")" "$subject" "$IMAGE_SHA256" "$DATA_SHA256" \
    "$binary_sha" "$rc" "$instructions" "$cycles" "$cpi" "$seconds" "$json" >>"$CSV_TMP"
  log "done $label: $cycles cycles, $instructions instructions, $seconds s @ 280 MHz"
}

active_pids=()
active_labels=()
active_commits=()
for i in "${!COMMITS[@]}"; do
  (( i < START_INDEX )) && continue
  (( i >= END_INDEX )) && continue
  label=${LABELS[$i]}
  commit=${COMMITS[$i]}
  build_candidate "$commit" "$label"
  prepare_candidate "$label" "$commit"
  smoke_candidate "$label"

  start_full "$label"
  active_pids+=("$FULL_PID")
  active_labels+=("$label")
  active_commits+=("$commit")
  if (( ${#active_pids[@]} >= MAX_PARALLEL )); then
    finish_full "${active_pids[0]}" "${active_labels[0]}" "${active_commits[0]}"
    active_pids=("${active_pids[@]:1}")
    active_labels=("${active_labels[@]:1}")
    active_commits=("${active_commits[@]:1}")
  fi
done
for i in "${!active_pids[@]}"; do
  finish_full "${active_pids[$i]}" "${active_labels[$i]}" "${active_commits[$i]}"
done

cp "$CSV_TMP" "$RESULTS_DIR/withmext-v2-rerun.csv"
log "summary: $RESULTS_DIR/withmext-v2-rerun.csv"
log "raw artifacts retained at: $BENCH_ROOT"
