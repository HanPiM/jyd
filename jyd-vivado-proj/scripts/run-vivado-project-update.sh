#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
usage: run-vivado-project-update.sh --pack-zip <path> --coe-dir <path> --sample <name> [options]

Options:
  --project-root <path>   Vivado project root containing digital_twin.xpr (default: .)
  --vivado <path>         Vivado executable (default: $VIVADO_BIN or vivado in PATH)
  -h, --help              Show this help
EOF
}

PROJECT_ROOT="."
VIVADO_BIN="${VIVADO_BIN:-}"
PACK_ZIP=""
COE_DIR=""
SAMPLE=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    --project-root)
      PROJECT_ROOT="$2"
      shift 2
      ;;
    --vivado)
      VIVADO_BIN="$2"
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
UPDATE_TCL="$SCRIPT_DIR/vivado_update_project.tcl"

if [ -z "$VIVADO_BIN" ]; then
  VIVADO_BIN="$(command -v vivado || true)"
fi

for required in "$PROJECT_FILE" "$PACK_ZIP" "$COE_SRC/irom.coe" "$COE_SRC/dram.coe" "$UPDATE_TCL"; do
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
mkdir -p "$COE_DEST"
cp "$COE_SRC/irom.coe" "$COE_DEST/irom.coe"
cp "$COE_SRC/dram.coe" "$COE_DEST/dram.coe"

echo "Project root: $PROJECT_ROOT"
echo "Sample: $SAMPLE"
echo "Vivado: $VIVADO_BIN"

"$VIVADO_BIN" \
  -mode batch \
  -source "$UPDATE_TCL" \
  -tclargs "$PROJECT_FILE" "$COE_DEST/irom.coe" "$COE_DEST/dram.coe"
