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
  --skip-project-update   Assume pack-fpga and COE imports are already applied
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
SKIP_PROJECT_UPDATE=0

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
    --skip-project-update)
      SKIP_PROJECT_UPDATE=1
      shift
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
  if [ "$SKIP_PROJECT_UPDATE" -ne 1 ]; then
    usage >&2
    exit 1
  fi
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$PROJECT_ROOT" && pwd)"
PROJECT_FILE="$PROJECT_ROOT/digital_twin.xpr"
FLOW_TCL="$SCRIPT_DIR/vivado_flow.tcl"
PRE_HOOK="$SCRIPT_DIR/vivado_pre_hook.tcl"

if [ -z "$RESULT_DIR" ]; then
  RESULT_DIR="$PROJECT_ROOT/result/$SAMPLE"
fi

if [ -z "$VIVADO_BIN" ]; then
  VIVADO_BIN="$(command -v vivado || true)"
fi

for required in "$PROJECT_FILE" "$FLOW_TCL" "$PRE_HOOK"; do
  if [ ! -e "$required" ]; then
    echo "required path not found: $required" >&2
    exit 1
  fi
done

if [ -z "$VIVADO_BIN" ] || [ ! -x "$VIVADO_BIN" ]; then
  echo "Vivado executable not found; pass --vivado or set VIVADO_BIN" >&2
  exit 1
fi

mkdir -p "$RESULT_DIR"

if [ "$SKIP_PROJECT_UPDATE" -ne 1 ]; then
  "$SCRIPT_DIR/run-vivado-project-update.sh" \
    --project-root "$PROJECT_ROOT" \
    --pack-zip "$PACK_ZIP" \
    --coe-dir "$COE_DIR" \
    --sample "$SAMPLE" \
    --vivado "$VIVADO_BIN"
fi

echo "Project root: $PROJECT_ROOT"
echo "Mode: $MODE"
echo "Sample: $SAMPLE"
echo "Vivado: $VIVADO_BIN"
echo "Result dir: $RESULT_DIR"

"$VIVADO_BIN" \
  -mode batch \
  -source "$FLOW_TCL" \
  -tclargs "$MODE" "$PROJECT_FILE" "$PRE_HOOK" "$RESULT_DIR"

for log_file in "$PROJECT_ROOT"/vivado.log "$PROJECT_ROOT"/vivado.jou; do
  if [ -f "$log_file" ]; then
    cp "$log_file" "$RESULT_DIR/"
  fi
done
