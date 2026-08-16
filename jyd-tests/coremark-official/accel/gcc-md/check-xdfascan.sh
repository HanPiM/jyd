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
scratch=$(mktemp -d "${TMPDIR:-/tmp}/xdfascan-md-check.XXXXXX")
trap 'rm -rf -- "$scratch"' EXIT HUP INT TERM

common_flags='-O3 -march=rv32im_zba_zbb_zbc_zbs_zbkb_zbkx_zicsr -mabi=ilp32 -ffreestanding -DITERATIONS=10000 -DTOTAL_DATA_SIZE=2000'
includes="-I$coremark_dir/src -I$jyd_dir/abstract-machine/am/include -I$jyd_dir/abstract-machine/klib/include"
source=$coremark_dir/src/core_state.c

compile_state()
{
    name=$1
    input=$2
    options=$3
    "$cc" $common_flags $includes '-DARCH_H="arch/riscv.h"' $options \
        -fdump-tree-clippedscore \
        -S "$input" -o "$scratch/$name.s"
    dump=$(find "$scratch" -maxdepth 1 -type f \
        -name "$name.c.*.clippedscore" -print -quit)
    if [ -n "$dump" ]; then
        mv "$dump" "$scratch/$name.clipped"
    else
        touch "$scratch/$name.clipped"
    fi
}

assert_no_whole_scan()
{
    name=$1
    if grep -Fq '.insn r 0x5b, 5, 3' "$scratch/$name.s"; then
        echo "$name unexpectedly selected xdfascan" >&2
        exit 1
    fi
    if grep -Fq 'recognized whole-string numeric-token DFA scan' \
        "$scratch/$name.clipped"; then
        echo "$name dump unexpectedly reports xdfascan" >&2
        exit 1
    fi
}

compile_state control "$source" ''
assert_no_whole_scan control

compile_state enabled "$source" '-mxdfascan'
grep -Fq 'recognized whole-string numeric-token DFA scan' "$scratch/enabled.clipped"
test "$(grep -Fc '.XDFASCAN (' "$scratch/enabled.clipped")" -eq 2
test "$(grep -Fc '.insn r 0x5b, 5, 3' "$scratch/enabled.s")" -eq 2
test "$(grep -Fc '.insn r 0x5b, 5, 2' "$scratch/enabled.s")" -eq 0
test "$(grep -Fc '.insn r 0x5b, 0, 0' "$scratch/enabled.s")" -eq 1
test "$(grep -Fc '.insn r 0x5b, 2, 0' "$scratch/enabled.s")" -eq 1
test "$(grep -Fc '.insn r 0x5b, 2, 1' "$scratch/enabled.s")" -eq 1

# The formal image uses command-line -Os plus a function-level O3 pragma.
compile_state formal-flags "$source" \
    "-mxdfascan -Os -include $coremark_dir/accel/force_o3.h"
grep -Fq 'recognized whole-string numeric-token DFA scan' \
    "$scratch/formal-flags.clipped"
test "$(grep -Fc '.insn r 0x5b, 5, 3' "$scratch/formal-flags.s")" -eq 2

sed -e 's/core_bench_state/audit_state_scan/g' \
    -e 's/core_state_transition/audit_state_step/g' \
    "$source" > "$scratch/renamed.c"
compile_state renamed "$scratch/renamed.c" '-mxdfascan'
grep -Fq 'recognized whole-string numeric-token DFA scan' "$scratch/renamed.clipped"
test "$(grep -Fc '.insn r 0x5b, 5, 3' "$scratch/renamed.s")" -eq 2

# Explicitly disabling xdfascan keeps the old xdfa4p lowering available.
compile_state fallback "$source" '-mxdfa4p -mxdfascan -mno-xdfascan'
assert_no_whole_scan fallback
grep -Fq 'recognized numeric-token DFA scan' "$scratch/fallback.clipped"
test "$(grep -Fc '.insn r 0x5b, 5, 2' "$scratch/fallback.s")" -eq 2

# With no legacy DFA option, -mno-xdfascan is a normal scalar fallback.
compile_state disabled "$source" '-mxdfascan -mno-xdfascan'
assert_no_whole_scan disabled
test "$(grep -Fc '.insn r 0x5b, 5, 2' "$scratch/disabled.s")" -eq 0

sed "s/NEXT_SYMBOL == ','/NEXT_SYMBOL == ';'/" \
    "$source" > "$scratch/wrong-delimiter.c"
compile_state wrong-delimiter "$scratch/wrong-delimiter.c" '-mxdfascan'
assert_no_whole_scan wrong-delimiter

sed 's/while (\*p != 0)/while (*p != 1)/g' \
    "$source" > "$scratch/wrong-nul.c"
test "$(grep -Fc 'while (*p != 1)' "$scratch/wrong-nul.c")" -eq 2
compile_state wrong-nul "$scratch/wrong-nul.c" '-mxdfascan'
assert_no_whole_scan wrong-nul

sed 's/final_counts\[fstate\]++;/final_counts[fstate] += 2;/g' \
    "$source" > "$scratch/wrong-final-count.c"
test "$(grep -Fc 'final_counts[fstate] += 2;' "$scratch/wrong-final-count.c")" -eq 2
compile_state wrong-final-count "$scratch/wrong-final-count.c" '-mxdfascan'
assert_no_whole_scan wrong-final-count

awk '
    /enum CORE_STATE core_state_transition\(ee_u8 \*\*instr, ee_u32 \*transition_count\);/ {
        print
        print "enum CORE_STATE audit_other_transition(ee_u8 **, ee_u32 *);"
        next
    }
    /core_state_transition\(&p, track_counts\)/ {
        calls++
        if (calls == 2)
            sub(/core_state_transition/, "audit_other_transition")
    }
    { print }
    END {
        print "enum CORE_STATE __attribute__((noinline))"
        print "audit_other_transition(ee_u8 **p, ee_u32 *c)"
        print "{ return core_state_transition(p, c); }"
    }
' "$source" > "$scratch/mixed-callee.c"
test "$(grep -Fc 'audit_other_transition(&p, track_counts)' "$scratch/mixed-callee.c")" -eq 1
compile_state mixed-callee "$scratch/mixed-callee.c" '-mxdfascan'
assert_no_whole_scan mixed-callee

echo "xdfascan enabled sites: 2"
echo "xdfascan renamed source shape: PASS"
echo "xdfascan disable/fallback audit: PASS"
echo "xdfascan semantic near-miss rejection: PASS"
