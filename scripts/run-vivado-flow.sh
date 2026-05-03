#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
usage: run-vivado-flow.sh --pack-zip <path> --coe-dir <path> --sample <name> [options]

Options:
  --project-root <path>   Vivado project root containing digital_twin.xpr (default: .)
  --mode <mode>           synth, impl, or bitstream (default: bitstream)
  --vivado <path>         Vivado executable (default: $VIVADO_BIN or vivado in PATH)
  --result-dir <path>     Result directory (default: <project-root>/result/<sample>)
  -h, --help              Show this help
EOF
}

PROJECT_ROOT="."
MODE="bitstream"
VIVADO_BIN="${VIVADO_BIN:-}"
RESULT_DIR=""
PACK_ZIP=""
COE_DIR=""
SAMPLE=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    --project-root)
      PROJECT_ROOT="$2"
      shift 2
      ;;
    --mode)
      MODE="$2"
      shift 2
      ;;
    --vivado)
      VIVADO_BIN="$2"
      shift 2
      ;;
    --result-dir)
      RESULT_DIR="$2"
      shift 2
      ;;
    --pack-zip)
      PACK_ZIP="$2"
      shift 2
      ;;
    --coe-dir)
      COE_DIR="$2"
      shift 2
      ;;
    --sample)
      SAMPLE="$2"
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

if [[ "$MODE" != "synth" && "$MODE" != "impl" && "$MODE" != "bitstream" ]]; then
  echo "unsupported mode: $MODE" >&2
  exit 1
fi

if [ -z "$PACK_ZIP" ] || [ -z "$COE_DIR" ] || [ -z "$SAMPLE" ]; then
  usage >&2
  exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$PROJECT_ROOT" && pwd)"
PROJECT_FILE="$PROJECT_ROOT/digital_twin.xpr"
PACK_DEST="$PROJECT_ROOT/digital_twin.srcs/sources_1/imports/pack-fpga"
COE_SRC="$COE_DIR/$SAMPLE"
COE_DEST="$PROJECT_ROOT/digital_twin.srcs/sources_1/imports/ci-coe/$SAMPLE"
FLOW_TCL="$SCRIPT_DIR/vivado_flow.tcl"
PRE_HOOK="$SCRIPT_DIR/vivado_pre_hook.tcl"

if [ -z "$RESULT_DIR" ]; then
  RESULT_DIR="$PROJECT_ROOT/result/$SAMPLE"
fi

if [ -z "$VIVADO_BIN" ]; then
  VIVADO_BIN="$(command -v vivado || true)"
fi

for required in "$PROJECT_FILE" "$PACK_ZIP" "$COE_SRC/irom.coe" "$COE_SRC/dram.coe" "$FLOW_TCL" "$PRE_HOOK"; do
  if [ ! -e "$required" ]; then
    echo "required path not found: $required" >&2
    exit 1
  fi
done

if [ -z "$VIVADO_BIN" ] || [ ! -x "$VIVADO_BIN" ]; then
  echo "Vivado executable not found; pass --vivado or set VIVADO_BIN" >&2
  exit 1
fi

rm -rf "$PACK_DEST"
mkdir -p "$PACK_DEST"
unzip -q "$PACK_ZIP" -d "$PACK_DEST"

rm -rf "$COE_DEST"
mkdir -p "$COE_DEST" "$RESULT_DIR"
cp "$COE_SRC/irom.coe" "$COE_DEST/irom.coe"
cp "$COE_SRC/dram.coe" "$COE_DEST/dram.coe"

echo "Project root: $PROJECT_ROOT"
echo "Mode: $MODE"
echo "Sample: $SAMPLE"
echo "Vivado: $VIVADO_BIN"
echo "Result dir: $RESULT_DIR"

"$VIVADO_BIN" \
  -mode batch \
  -source "$FLOW_TCL" \
  -tclargs "$MODE" "$PROJECT_FILE" "$PRE_HOOK" "$RESULT_DIR" "$COE_DEST/irom.coe" "$COE_DEST/dram.coe"

for log_file in "$PROJECT_ROOT"/vivado.log "$PROJECT_ROOT"/vivado.jou; do
  if [ -f "$log_file" ]; then
    cp "$log_file" "$RESULT_DIR/"
  fi
done
