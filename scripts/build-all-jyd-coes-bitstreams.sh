#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
usage: build-all-jyd-coes-bitstreams.sh [options]

Options:
  --project-root <path>   Vivado project root containing digital_twin.xpr (default: .)
  --coe-root <path>       Directory containing COE sample directories (default: ./jyd-coes)
  --output-dir <path>     Directory for generated bitstreams (default: ./bitstreams)
  --run <name>            Vivado implementation run to launch (default: impl_Auto_1)
  --vivado <path>         Vivado executable (default: $VIVADO_BIN or vivado in PATH)
  -h, --help              Show this help
EOF
}

PROJECT_ROOT="."
COE_ROOT="./jyd-coes"
OUTPUT_DIR="./bitstreams"
RUN_NAME="impl_Auto_1"
VIVADO_BIN="${VIVADO_BIN:-}"

require_value() {
  if [ "$#" -lt 2 ] || [ -z "$2" ]; then
    echo "missing value for $1" >&2
    usage >&2
    exit 1
  fi
}

while [ "$#" -gt 0 ]; do
  case "$1" in
    --project-root)
      require_value "$@"
      PROJECT_ROOT="$2"
      shift 2
      ;;
    --coe-root)
      require_value "$@"
      COE_ROOT="$2"
      shift 2
      ;;
    --output-dir)
      require_value "$@"
      OUTPUT_DIR="$2"
      shift 2
      ;;
    --run)
      require_value "$@"
      RUN_NAME="$2"
      shift 2
      ;;
    --vivado)
      require_value "$@"
      VIVADO_BIN="$2"
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "unknown argument: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$PROJECT_ROOT" && pwd)"
PROJECT_FILE="$PROJECT_ROOT/digital_twin.xpr"
TCL_SCRIPT="$SCRIPT_DIR/vivado_build_bitstream_for_run.tcl"

if [ -z "$VIVADO_BIN" ]; then
  VIVADO_BIN="$(command -v vivado || true)"
fi

if [ ! -f "$PROJECT_FILE" ]; then
  echo "Vivado project not found: $PROJECT_FILE" >&2
  exit 1
fi

if [ ! -f "$TCL_SCRIPT" ]; then
  echo "Vivado Tcl script not found: $TCL_SCRIPT" >&2
  exit 1
fi

if [ ! -d "$PROJECT_ROOT/$COE_ROOT" ] && [ ! -d "$COE_ROOT" ]; then
  echo "COE root not found: $COE_ROOT" >&2
  exit 1
fi

if [ -z "$VIVADO_BIN" ] || [ ! -x "$VIVADO_BIN" ]; then
  echo "Vivado executable not found; pass --vivado or set VIVADO_BIN" >&2
  exit 1
fi

COE_ROOT="$(cd "$PROJECT_ROOT" && cd "$COE_ROOT" && pwd)"
if [[ "$OUTPUT_DIR" != /* ]]; then
  OUTPUT_DIR="$PROJECT_ROOT/$OUTPUT_DIR"
fi
OUTPUT_DIR="$(mkdir -p "$OUTPUT_DIR" && cd "$OUTPUT_DIR" && pwd)"
COE_DEST="$PROJECT_ROOT/digital_twin.srcs/sources_1/imports/cur_coe"

if [ ! -d "$COE_DEST" ]; then
  echo "destination COE directory not found: $COE_DEST" >&2
  exit 1
fi

mapfile -d '' SAMPLE_DIRS < <(find "$COE_ROOT" -mindepth 1 -maxdepth 1 -type d -print0 | sort -z)

if [ "${#SAMPLE_DIRS[@]}" -eq 0 ]; then
  echo "no COE sample directories found under: $COE_ROOT" >&2
  exit 1
fi

echo "Project root: $PROJECT_ROOT"
echo "COE root: $COE_ROOT"
echo "Output dir: $OUTPUT_DIR"
echo "Vivado run: $RUN_NAME"
echo "Vivado: $VIVADO_BIN"

for sample_dir in "${SAMPLE_DIRS[@]}"; do
  sample_name="$(basename "$sample_dir")"
  irom_src="$sample_dir/irom.coe"
  dram_src="$sample_dir/dram.coe"
  bit_out="$OUTPUT_DIR/$sample_name.bit"
  log_file="$OUTPUT_DIR/$sample_name.vivado.log"
  jou_file="$OUTPUT_DIR/$sample_name.vivado.jou"

  for required in "$irom_src" "$dram_src"; do
    if [ ! -f "$required" ]; then
      echo "required COE file not found: $required" >&2
      exit 1
    fi
  done

  echo "[$sample_name] updating COE files"
  cp "$irom_src" "$COE_DEST/irom.coe"
  cp "$dram_src" "$COE_DEST/dram.coe"

  echo "[$sample_name] launching Vivado"
  (
    cd "$PROJECT_ROOT"
    "$VIVADO_BIN" \
      -mode batch \
      -journal "$jou_file" \
      -log "$log_file" \
      -source "$TCL_SCRIPT" \
      -tclargs "$PROJECT_FILE" "$RUN_NAME" "$bit_out"
  )

  echo "[$sample_name] wrote $bit_out"
done

echo "all bitstreams generated in $OUTPUT_DIR"
