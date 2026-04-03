#!/bin/bash

set -euo pipefail

usage() {
    echo "Usage: $0 <input_dir> <output_dir>"
}

INPUT_DIR="${1:-}"
OUTPUT_DIR="${2:-}"

if [ -z "$INPUT_DIR" ] || [ -z "$OUTPUT_DIR" ] || [ "${3:-}" != "" ]; then
    usage
    exit 1
fi

if [ ! -d "$INPUT_DIR" ]; then
    echo "Error: input directory not found: $INPUT_DIR" >&2
    exit 1
fi

mkdir -p "$OUTPUT_DIR"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROC_SCRIPT="$SCRIPT_DIR/proc_coe.sh"

if [ ! -x "$PROC_SCRIPT" ]; then
    echo "Error: proc_coe.sh is not executable: $PROC_SCRIPT" >&2
    exit 1
fi

TMP_DIR="$(mktemp -d)"
trap 'rm -rf "$TMP_DIR"' EXIT

find "$INPUT_DIR" -mindepth 1 -maxdepth 1 -type d | sort | while IFS= read -r SRC_DIR; do
    SAMPLE_NAME="$(basename "$SRC_DIR")"
    IROM_COE="$SRC_DIR/irom.coe"
    DRAM_COE="$SRC_DIR/dram.coe"

    if [ ! -f "$IROM_COE" ]; then
        echo "Error: missing irom.coe in $SRC_DIR" >&2
        exit 1
    fi

    if [ ! -f "$DRAM_COE" ]; then
        echo "Error: missing dram.coe in $SRC_DIR" >&2
        exit 1
    fi

    SAMPLE_TMP_DIR="$TMP_DIR/$SAMPLE_NAME"
    mkdir -p "$SAMPLE_TMP_DIR"

    "$PROC_SCRIPT" "$IROM_COE" "$SAMPLE_TMP_DIR"
    mv "$SAMPLE_TMP_DIR/irom.bin" "$OUTPUT_DIR/$SAMPLE_NAME.bin"
    mv "$SAMPLE_TMP_DIR/irom.txt" "$OUTPUT_DIR/$SAMPLE_NAME.txt"

    "$PROC_SCRIPT" -nodisasm "$DRAM_COE" "$SAMPLE_TMP_DIR"
    mv "$SAMPLE_TMP_DIR/dram.bin" "$OUTPUT_DIR/$SAMPLE_NAME.data.bin"
done
