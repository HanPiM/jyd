#!/bin/bash
# Check the xdfa4h core_bench_state rewrite: the 046t clippedscore dump must
# contain the counter init, two step loops, and both hardware counter reads,
# and the emitted assembly must decode to the .insn r 0x5b encodings
# (0,0,0 init / 5,1 step / 2,0 transition read / 2,1 final read).
set -eu

if [ "$#" -ne 1 ]; then
    echo "usage: $0 /path/to/riscv-gcc" >&2
    exit 2
fi

cc=$1
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
coremark_dir=$(CDPATH= cd -- "$script_dir/../.." && pwd)
jyd_dir=$(CDPATH= cd -- "$coremark_dir/../.." && pwd)
scratch=$(mktemp -d "${JYD_DATA_ROOT:-/srv/data/jyd}/tmp/xdfa4h-md-check.XXXXXX")
trap 'rm -rf -- "$scratch"' EXIT HUP INT TERM

flags='-Os -O2 -fno-builtin -ffreestanding -O3 -march=rv32im_zicsr_zba_zbb_zbc_zbs_zbkb_zbkx -mabi=ilp32 -mxdfa4h'
includes="-I$coremark_dir/src -I$jyd_dir/abstract-machine/am/include -I$jyd_dir/abstract-machine/klib/include"

"$cc" $flags $includes '-DARCH_H="arch/riscv.h"' \
    -fdump-tree-clippedscore="$scratch/cs.046t.clippedscore" -S "$coremark_dir/src/core_state.c" \
    -o "$scratch/core.s"
test "$(grep -Fc '.XDFACNT_INIT ()' "$scratch/cs.046t.clippedscore")" -eq 1
test "$(grep -Fc '.XDFA4H_STEP (' "$scratch/cs.046t.clippedscore")" -eq 2
test "$(grep -Fc '.XDFA4H_READ (' "$scratch/cs.046t.clippedscore")" -eq 1
test "$(grep -Fc '.XDFA4H_FINAL_READ (' "$scratch/cs.046t.clippedscore")" -eq 3

# Parse the emitted .insn r 0x5b mnemonics: expect one init (funct3=0,
# funct7=0), two steps (funct3=5, funct7=1), and the two counter-read forms.
steps=$(grep -Fc '.insn r 0x5b, 5, 1' "$scratch/core.s")
init=$(grep -Fc '.insn r 0x5b, 0, 0' "$scratch/core.s")
read=$(grep -Fc '.insn r 0x5b, 2, 0' "$scratch/core.s")
final_read=$(grep -Fc '.insn r 0x5b, 2, 1' "$scratch/core.s")
test "$init" -eq 1
test "$steps" -eq 2
test "$read" -eq 1
test "$final_read" -eq 1
echo "xdfa4h numeric-token DFA scan: PASS"
