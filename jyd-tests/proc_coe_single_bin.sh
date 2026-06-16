#!/bin/bash

set -euo pipefail

TEXT_ADDR=0x80000000
DATA_ADDR=0x80100000
DATA_OFFSET=$((DATA_ADDR - TEXT_ADDR))

usage() {
    echo "Usage: $0 <coe_dir> [output_bin]"
}

COE_DIR="${1:-}"
OUTPUT_BIN="${2:-}"

if [ -z "$COE_DIR" ] || [ "${3:-}" != "" ]; then
    usage
    exit 1
fi

if [ ! -d "$COE_DIR" ]; then
    echo "Error: input directory not found: $COE_DIR" >&2
    exit 1
fi

IROM_COE="$COE_DIR/irom.coe"
DRAM_COE="$COE_DIR/dram.coe"

if [ ! -f "$IROM_COE" ]; then
    echo "Error: missing irom.coe in $COE_DIR" >&2
    exit 1
fi

if [ ! -f "$DRAM_COE" ]; then
    echo "Error: missing dram.coe in $COE_DIR" >&2
    exit 1
fi

if [ -z "$OUTPUT_BIN" ]; then
    OUTPUT_BIN="$(basename "$COE_DIR").bin"
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROC_SCRIPT="$SCRIPT_DIR/proc_coe.sh"

if [ ! -x "$PROC_SCRIPT" ]; then
    echo "Error: proc_coe.sh is not executable: $PROC_SCRIPT" >&2
    exit 1
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

PATCHED_IROM_COE="$TMP_DIR/irom.coe"

awk '
    BEGIN { replaced = 0 }
    !replaced && $0 ~ /^[[:space:]]*0000006f[[:space:]]*[,;][[:space:]]*$/ {
        sub(/0000006f/, "00100073")
        replaced = 1
    }
    { print }
    END {
        if (!replaced) {
            print "Error: 0000006f self-loop instruction not found" > "/dev/stderr"
            exit 1
        }
    }
' "$IROM_COE" > "$PATCHED_IROM_COE"

"$PROC_SCRIPT" -nodisasm "$PATCHED_IROM_COE" "$TMP_DIR"
"$PROC_SCRIPT" -nodisasm "$DRAM_COE" "$TMP_DIR"

IROM_BIN="$TMP_DIR/irom.bin"
DRAM_BIN="$TMP_DIR/dram.bin"
IROM_SIZE="$(stat -c%s "$IROM_BIN")"

if [ "$IROM_SIZE" -gt "$DATA_OFFSET" ]; then
    echo "Error: irom content size ($IROM_SIZE bytes) exceeds data offset ($DATA_OFFSET bytes)" >&2
    exit 1
fi

mkdir -p "$(dirname "$OUTPUT_BIN")"
PADDING_SIZE=$((DATA_OFFSET - IROM_SIZE))

cp "$IROM_BIN" "$OUTPUT_BIN"
if [ "$PADDING_SIZE" -gt 0 ]; then
    dd if=/dev/zero bs=1 count="$PADDING_SIZE" status=none >> "$OUTPUT_BIN"
fi
cat "$DRAM_BIN" >> "$OUTPUT_BIN"

echo "Generated $OUTPUT_BIN"
echo "  .text: $TEXT_ADDR, $(stat -c%s "$IROM_BIN") bytes from patched irom.coe"
echo "  .data: $DATA_ADDR, $(stat -c%s "$DRAM_BIN") bytes from dram.coe"
