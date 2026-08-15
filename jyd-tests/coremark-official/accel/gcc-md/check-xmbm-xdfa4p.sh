#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
    echo "usage: $0 /path/to/riscv64-unknown-linux-gnu-gcc" >&2
    exit 2
fi

cc=$1
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
coremark_dir=$(CDPATH= cd -- "$script_dir/../.." && pwd)
jyd_dir=$(CDPATH= cd -- "$coremark_dir/../.." && pwd)
scratch=$(mktemp -d "${JYD_DATA_ROOT:-/srv/data/jyd}/tmp/xmbm-xdfa4p-md-check.XXXXXX")
trap 'rm -rf -- "$scratch"' EXIT HUP INT TERM

common_flags='-O3 -march=rv32im_zba_zbb_zbc_zbs_zbkb_zbkx_zicsr -mabi=ilp32 -ffreestanding -DITERATIONS=10000 -DTOTAL_DATA_SIZE=2000'
includes="-I$coremark_dir/src -I$jyd_dir/abstract-machine/am/include -I$jyd_dir/abstract-machine/klib/include"

"$cc" $common_flags $includes '-DARCH_H="arch/riscv.h"' \
    -S "$coremark_dir/src/core_matrix.c" -o "$scratch/matrix-control.s"
"$cc" $common_flags $includes '-DARCH_H="arch/riscv.h"' -mxmbm \
    -fdump-tree-clippedscore -S "$coremark_dir/src/core_matrix.c" \
    -o "$scratch/matrix-xmbm.s"

if grep -Fq '.insn r 0x0b, 5, 1' "$scratch/matrix-control.s"; then
    echo "control unexpectedly contains xmbm" >&2
    exit 1
fi
grep -Fq '.XMBM (' "$scratch"/matrix-xmbm.c.*.clippedscore
xmbm_matches=$(grep -Fc '.insn r 0x0b, 5, 1' "$scratch/matrix-xmbm.s")
test "$xmbm_matches" -gt 0

"$cc" $common_flags $includes '-DARCH_H="arch/riscv.h"' \
    -S "$coremark_dir/src/core_state.c" -o "$scratch/state-control.s"
"$cc" $common_flags $includes '-DARCH_H="arch/riscv.h"' -mxdfa4p \
    -fdump-tree-clippedscore -S "$coremark_dir/src/core_state.c" \
    -o "$scratch/state-xdfa4p.s"

if grep -Fq '.insn r 0x5b, 5, 2' "$scratch/state-control.s"; then
    echo "control unexpectedly contains xdfa4p" >&2
    exit 1
fi
grep -Fq 'recognized numeric-token DFA scan' "$scratch"/state-xdfa4p.c.*.clippedscore
step_matches=$(grep -Fc '.insn r 0x5b, 5, 2' "$scratch/state-xdfa4p.s")
test "$step_matches" -eq 2
test "$(grep -Fc '.insn r 0x5b, 0, 0' "$scratch/state-xdfa4p.s")" -eq 1
test "$(grep -Fc '.insn r 0x5b, 2, 1' "$scratch/state-xdfa4p.s")" -eq 1
test "$(grep -Fc '.insn r 0x5b, 2, 0' "$scratch/state-xdfa4p.s")" -eq 1

echo "unmodified core_matrix.c xmbm sites: $xmbm_matches"
echo "unmodified core_state.c xdfa4p step sites: $step_matches"
