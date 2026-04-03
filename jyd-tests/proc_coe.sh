#!/bin/bash

set -euo pipefail

NO_DISASM=0

usage() {
    echo "Usage: $0 [-nodisasm] <coe_file> <output_dir>"
}

if [ "${1:-}" = "-nodisasm" ]; then
    NO_DISASM=1
    shift
fi

DEST_COE="${1:-}"
OUTPUT_DIR="${2:-}"

if [ -z "$DEST_COE" ] || [ -z "$OUTPUT_DIR" ] || [ "${3:-}" != "" ]; then
    usage
    exit 1
fi

if [ ! -f "$DEST_COE" ]; then
    echo "Error: coe file not found: $DEST_COE" >&2
    exit 1
fi

mkdir -p "$OUTPUT_DIR"

BASE_NAME="$(basename "${DEST_COE%.coe}")"
DEST_BIN="$OUTPUT_DIR/$BASE_NAME.bin"
DEST_DISASM="$OUTPUT_DIR/$BASE_NAME.txt"

tail -n +3 "$DEST_COE" | tr -d ',\r\n' | xxd -r -p > "$DEST_BIN"

objcopy -I binary -O binary --reverse-bytes=4 "$DEST_BIN" "$DEST_BIN"

if [ "$NO_DISASM" -eq 0 ]; then
    riscv64-linux-gnu-objdump -D -b binary -m riscv:rv32 --adjust-vma=0x80000000 "$DEST_BIN" > "$DEST_DISASM"
fi
