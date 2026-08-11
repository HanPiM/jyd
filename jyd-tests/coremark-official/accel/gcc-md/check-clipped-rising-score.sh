#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
    echo "usage: $0 /path/to/riscv-gcc" >&2
    exit 2
fi

cc=$1
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
coremark_dir=$(CDPATH= cd -- "$script_dir/../.." && pwd)
jyd_dir=$(CDPATH= cd -- "$coremark_dir/../.." && pwd)
scratch=$(mktemp -d "${JYD_DATA_ROOT:-/srv/data/jyd}/tmp/clipped-score-md-check.XXXXXX")
trap 'rm -rf -- "$scratch"' EXIT HUP INT TERM
flags='-O3 -march=rv32im_zbc_zicsr -mabi=ilp32 -mclipped-rising-score-reduce'

"$cc" $flags -S "$script_dir/clipped-rising-score-pattern.c" -o "$scratch/pattern.s"
test "$(grep -Fc '.insn r 0x0b, 7, 2' "$scratch/pattern.s")" -eq 1
sed -n '/clipped_rising_score:/,/\.size[[:space:]]*clipped_rising_score/p' "$scratch/pattern.s" \
    | grep -Fq '.insn r 0x0b, 7, 2'
for name in reject_equal_threshold reject_nonzero_reset reject_different_bonus reject_falling_score; do
    if sed -n "/$name:/,/\\.size[[:space:]]*$name/p" "$scratch/pattern.s" \
        | grep -Fq '.insn r 0x0b, 7, 2'; then
        echo "$name unexpectedly matched" >&2
        exit 1
    fi
done

core_flags='-O3 -march=rv32im_zbc_zicsr -mabi=ilp32 -ffreestanding -DITERATIONS=10000 -DTOTAL_DATA_SIZE=2000 -DCOREMARK_PSEUDO_FLOAT=1'
core_includes="-I$coremark_dir/src -I$jyd_dir/abstract-machine/am/include -I$jyd_dir/abstract-machine/klib/include"
"$cc" $core_flags $core_includes '-DARCH_H="arch/riscv.h"' \
    -mclipped-rising-score-reduce -S "$coremark_dir/src/core_matrix.c" -o "$scratch/core.s"
test "$(grep -Fc '.insn r 0x0b, 7, 2' "$scratch/core.s")" -ge 1
echo "clipped rising-score semantic pattern: PASS"
