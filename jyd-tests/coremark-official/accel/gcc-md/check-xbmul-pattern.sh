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
scratch=$(mktemp -d "${JYD_DATA_ROOT:-/srv/data/jyd}/tmp/xbmul-md-check.XXXXXX")
trap 'rm -rf -- "$scratch"' EXIT HUP INT TERM

common_flags='-O3 -march=rv32im_zicsr -mabi=ilp32'
"$cc" $common_flags -S "$script_dir/xbmul-pattern.c" -o "$scratch/control.s"
"$cc" $common_flags -mxbmul -S "$script_dir/xbmul-pattern.c" -o "$scratch/xbmul.s"

if grep -Fq '.insn r 0x0b, 5, 0' "$scratch/control.s"; then
    echo "control unexpectedly contains xbmul" >&2
    exit 1
fi
grep -Fq '.insn r 0x0b, 5, 0' "$scratch/xbmul.s"

core_flags='-O3 -march=rv32im_zicsr -mabi=ilp32 -ffreestanding -DITERATIONS=10000 -DTOTAL_DATA_SIZE=2000 -DCOREMARK_PSEUDO_FLOAT=1'
core_includes="-I$coremark_dir/src -I$jyd_dir/abstract-machine/am/include -I$jyd_dir/abstract-machine/klib/include"
"$cc" $core_flags $core_includes '-DARCH_H="arch/riscv.h"' -S "$coremark_dir/src/core_matrix.c" -o "$scratch/core-control.s"
"$cc" $core_flags $core_includes '-DARCH_H="arch/riscv.h"' -mxbmul -S "$coremark_dir/src/core_matrix.c" -o "$scratch/core-xbmul.s"

if grep -Fq '.insn r 0x0b, 5, 0' "$scratch/core-control.s"; then
    echo "CoreMark control unexpectedly contains xbmul" >&2
    exit 1
fi
core_matches=$(grep -Fc '.insn r 0x0b, 5, 0' "$scratch/core-xbmul.s")
test "$core_matches" -gt 0

echo "control instruction selection:"
sed -n '/packed_field_multiply:/,/ret/p' "$scratch/control.s"
echo "mxbmul instruction selection:"
sed -n '/packed_field_multiply:/,/ret/p' "$scratch/xbmul.s"
echo "unmodified core_matrix.c matches: $core_matches"
