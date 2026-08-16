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
scratch=$(mktemp -d "${TMPDIR:-/tmp}/xdup8lo-md-check.XXXXXX")
trap 'rm -rf -- "$scratch"' EXIT HUP INT TERM

flags='-O3 -march=rv32im_zba_zbb_zbkb_zbs_zicsr -mabi=ilp32'
"$cc" $flags -S "$script_dir/xdup8lo-pattern.c" -o "$scratch/control.s"
"$cc" $flags -mxdup8lo -S "$script_dir/xdup8lo-pattern.c" -o "$scratch/enabled.s"
"$cc" $flags -mxdup8lo -mno-xdup8lo -S "$script_dir/xdup8lo-pattern.c" -o "$scratch/disabled.s"

encoding='.insn r 0x0b, 1, 1'
if grep -Fq "$encoding" "$scratch/control.s"; then
    echo "control unexpectedly contains xdup8lo" >&2
    exit 1
fi
if grep -Fq "$encoding" "$scratch/disabled.s"; then
    echo "explicitly disabled build unexpectedly contains xdup8lo" >&2
    exit 1
fi
test "$(grep -Fc "$encoding" "$scratch/enabled.s")" -eq 2
for name in duplicate_byte_one renamed_duplicate_byte_one; do
    sed -n "/^$name:/,/\.size[[:space:]]*$name/p" "$scratch/enabled.s" \
        | grep -Fq "$encoding"
done
for name in reject_wrong_shift reject_wrong_mask reject_shift_value_live reject_mask_value_live; do
    if sed -n "/^$name:/,/\.size[[:space:]]*$name/p" "$scratch/enabled.s" \
        | grep -Fq "$encoding"; then
        echo "$name unexpectedly matched" >&2
        exit 1
    fi
done

core_flags='-O3 -march=rv32im_zba_zbb_zbkb_zbs_zicsr -mabi=ilp32 -ffreestanding -DITERATIONS=10000 -DTOTAL_DATA_SIZE=2000'
core_includes="-I$coremark_dir/src -I$jyd_dir/abstract-machine/am/include -I$jyd_dir/abstract-machine/klib/include"
"$cc" $core_flags $core_includes '-DARCH_H="arch/riscv.h"' \
    -S "$coremark_dir/src/core_list_join.c" -o "$scratch/core-control.s"
"$cc" $core_flags $core_includes '-DARCH_H="arch/riscv.h"' \
    -mxdup8lo -S "$coremark_dir/src/core_list_join.c" -o "$scratch/core.s"
if grep -Fq "$encoding" "$scratch/core-control.s"; then
    echo "CoreMark control unexpectedly contains xdup8lo" >&2
    exit 1
fi
core_matches=$(grep -Fc "$encoding" "$scratch/core.s")
test "$core_matches" -gt 0

echo "xdup8lo exact semantic pattern: PASS ($core_matches CoreMark static sites)"
