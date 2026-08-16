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
scratch=$(mktemp -d "${TMPDIR:-/tmp}/xpaddh2-md-check.XXXXXX")
trap 'rm -rf -- "$scratch"' EXIT HUP INT TERM

flags='-O3 -march=rv32im_zba_zbb_zbc_zbs_zbkb_zbkx_zicsr -mabi=ilp32 -ffreestanding'
encoding='.insn r 0x0b, 1, 2'
pattern="$script_dir/xpaddh2-pattern.c"

"$cc" $flags -S "$pattern" -o "$scratch/control.s"
"$cc" $flags -mxpaddh2 -fdump-tree-clippedscore \
    -S "$pattern" -o "$scratch/enabled.s"
"$cc" $flags -mxpaddh2 -mno-xpaddh2 -S "$pattern" \
    -o "$scratch/disabled.s"

if grep -Fq "$encoding" "$scratch/control.s" \
    || grep -Fq "$encoding" "$scratch/disabled.s"; then
    echo "xpaddh2 emitted while the extension was disabled" >&2
    exit 1
fi
test "$(grep -Fc "$encoding" "$scratch/enabled.s")" -eq 1
grep -Fq 'recognized packed halfword matrix scalar add' \
    "$scratch"/enabled.c.*.clippedscore

for name in reject_different_bound reject_post_add_transform \
    reject_narrow_operands reject_unused_addend reject_weak_alignment \
    reject_wide_alias \
    reject_extra_store \
    reject_shifted_address reject_inverted_exit reject_do_while \
    reject_third_loop reject_switch_subset reject_volatile_asm; do
    if sed -n "/$name:/,/\\.size[[:space:]]*$name/p" "$scratch/enabled.s" \
        | grep -Fq "$encoding"; then
        echo "$name unexpectedly matched xpaddh2" >&2
        exit 1
    fi
done

sed 's/packed_halfword_matrix_add/renamed_packed_update/g' \
    "$pattern" > "$scratch/renamed.c"
"$cc" $flags -mxpaddh2 -fdump-tree-clippedscore \
    -S "$scratch/renamed.c" -o "$scratch/renamed.s"
test "$(grep -Fc "$encoding" "$scratch/renamed.s")" -eq 1
grep -Fq 'recognized packed halfword matrix scalar add' \
    "$scratch"/renamed.c.*.clippedscore

includes="-I$coremark_dir/src -I$jyd_dir/abstract-machine/am/include -I$jyd_dir/abstract-machine/klib/include"
core_flags="$flags -DITERATIONS=10000 -DTOTAL_DATA_SIZE=2000"
"$cc" $core_flags $includes '-DARCH_H="arch/riscv.h"' -mxpaddh2 \
    -fdump-tree-clippedscore -S "$coremark_dir/src/core_matrix.c" \
    -o "$scratch/core-matrix.s"
test "$(grep -Fc "$encoding" "$scratch/core-matrix.s")" -eq 3
test "$(sed -n '/^matrix_test:/,/^[[:space:]]*\.size[[:space:]]*matrix_test/p' \
    "$scratch/core-matrix.s" | grep -Fc "$encoding")" -eq 2
test "$(sed -n '/^matrix_add_const:/,/^[[:space:]]*\.size[[:space:]]*matrix_add_const/p' \
    "$scratch/core-matrix.s" | grep -Fc "$encoding")" -eq 1
test "$(grep -Fc 'recognized packed halfword matrix scalar add' \
    "$scratch"/core-matrix.c.*.clippedscore)" -eq 1

echo "xpaddh2 shape, fallback, and name-independence: PASS"
