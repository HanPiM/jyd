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
scratch=$(mktemp -d "${TMPDIR:-/tmp}/gcc-backend-check.XXXXXX")
trap 'rm -rf -- "$scratch"' EXIT HUP INT TERM

flags='-O3 -march=rv32im_zba_zbb_zbc_zbs_zbkb_zbkx_zicsr -mabi=ilp32 -ffreestanding -DITERATIONS=10000 -DTOTAL_DATA_SIZE=2000'
includes="-I$coremark_dir/src -I$jyd_dir/abstract-machine/am/include -I$jyd_dir/abstract-machine/klib/include"
arch='-DARCH_H="arch/riscv.h"'

"$cc" $flags $includes "$arch" -S "$coremark_dir/src/core_list_join.c" \
    -o "$scratch/list-control.s"
"$cc" $flags $includes "$arch" -mxlistfind -fdump-tree-clippedscore \
    -S "$coremark_dir/src/core_list_join.c" -o "$scratch/list-xlistfind.s"
if grep -Fq '.insn r 0x0b, 6, 1' "$scratch/list-control.s" \
    || grep -Fq '.insn r 0x0b, 6, 3' "$scratch/list-control.s"; then
    echo "list control unexpectedly contains xlistfind" >&2
    exit 1
fi
grep -Fq 'recognized linked-list search' "$scratch"/list-xlistfind.c.*.clippedscore
test "$(grep -Fc '.insn r 0x0b, 6, 1' "$scratch/list-xlistfind.s")" -gt 0
test "$(grep -Fc '.insn r 0x0b, 6, 3' "$scratch/list-xlistfind.s")" -gt 0

# Rename the source functions and require the same semantic matches. This
# proves selection does not depend on benchmark symbol names.
sed 's/core_list_find/audit_list_search/g' \
    "$coremark_dir/src/core_list_join.c" > "$scratch/list-renamed.c"
"$cc" $flags $includes "$arch" -mxlistfind -fdump-tree-clippedscore \
    -S "$scratch/list-renamed.c" -o "$scratch/list-renamed.s"
grep -Fq 'recognized linked-list search' "$scratch"/list-renamed.c.*.clippedscore
test "$(grep -Fc '.insn r 0x0b, 6, 1' "$scratch/list-renamed.s")" -gt 0
test "$(grep -Fc '.insn r 0x0b, 6, 3' "$scratch/list-renamed.s")" -gt 0

"$cc" $flags $includes "$arch" -S "$coremark_dir/src/core_matrix.c" \
    -o "$scratch/matrix-control.s"
"$cc" $flags $includes "$arch" -mxmacacc -mxdotn -fdump-tree-clippedscore \
    -S "$coremark_dir/src/core_matrix.c" -o "$scratch/matrix-xdotn.s"
if grep -Eq '\.insn r 0x0b, (3, (4|5|6|7|8|9)|4, (3|4|5|6|7))' "$scratch/matrix-control.s"; then
    echo "matrix control unexpectedly contains xmacacc/xdotn" >&2
    exit 1
fi
grep -Fq 'recognized matrix multiply accumulation' "$scratch"/matrix-xdotn.c.*.clippedscore
grep -Fq 'recognized bit-extract matrix accumulation' "$scratch"/matrix-xdotn.c.*.clippedscore
for funct7 in 4 5 6 7 8 9; do
    test "$(grep -Fc ".insn r 0x0b, 3, $funct7" "$scratch/matrix-xdotn.s")" -gt 0
done
for funct7 in 3 4 5 6 7; do
    test "$(grep -Fc ".insn r 0x0b, 4, $funct7" "$scratch/matrix-xdotn.s")" -gt 0
done

sed -e 's/matrix_mul_matrix_bitextract/audit_matrix_bit_accumulate/g' \
    -e 's/matrix_mul_matrix/audit_matrix_accumulate/g' \
    "$coremark_dir/src/core_matrix.c" > "$scratch/matrix-renamed.c"
"$cc" $flags $includes "$arch" -mxmacacc -mxdotn -fdump-tree-clippedscore \
    -S "$scratch/matrix-renamed.c" -o "$scratch/matrix-renamed.s"
grep -Fq 'recognized matrix multiply accumulation' "$scratch"/matrix-renamed.c.*.clippedscore
grep -Fq 'recognized bit-extract matrix accumulation' "$scratch"/matrix-renamed.c.*.clippedscore
for funct7 in 4 5 6 7 8 9; do
    test "$(grep -Fc ".insn r 0x0b, 3, $funct7" "$scratch/matrix-renamed.s")" -gt 0
done
for funct7 in 3 4 5 6 7; do
    test "$(grep -Fc ".insn r 0x0b, 4, $funct7" "$scratch/matrix-renamed.s")" -gt 0
done

echo "xlistfind and xmacacc/xdotn shape/name-independence: PASS"
