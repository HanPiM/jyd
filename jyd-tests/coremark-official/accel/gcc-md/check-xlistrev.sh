#!/bin/sh
# Check the xlistrev in-place list-reversal recognizer: the walk-and-relink
# idiom must lower to the two .insn r 0x0b, 6, 0/2 encodings, and the empty-
# list fallback must keep the software path.
set -eu

if [ "$#" -ne 1 ]; then
    echo "usage: $0 /path/to/riscv-gcc" >&2
    exit 2
fi

cc=$1
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
coremark_dir=$(CDPATH= cd -- "$script_dir/../.." && pwd)
jyd_dir=$(CDPATH= cd -- "$coremark_dir/../.." && pwd)
scratch=$(mktemp -d "${TMPDIR:-/tmp}/xlistrev-md-check.XXXXXX")
trap 'rm -rf -- "$scratch"' EXIT HUP INT TERM

cat > "$scratch/rev.c" <<'EOF'
struct L { struct L *next; int v; };
struct L *rev(struct L *p) {
  struct L *q = 0, *r;
  while (p) { r = p->next; p->next = q; q = p; p = r; }
  return q;
}
EOF
cat > "$scratch/walk.c" <<'EOF'
struct L { struct L *next; int v; };
int count(struct L *p) {
  int n = 0;
  while (p) { n++; p = p->next; }
  return n;
}
EOF

flags='-O3 -march=rv32im_zicsr -mabi=ilp32'
"$cc" $flags -mxlistrev -S "$scratch/rev.c" -o "$scratch/rev.s"
test "$(grep -Fc '.insn r 0x0b, 6' "$scratch/rev.s")" -eq 2
"$cc" $flags -mxlistrev -S "$scratch/walk.c" -o "$scratch/walk.s"
if grep -Fq '.insn r 0x0b, 6' "$scratch/walk.s"; then
    echo "plain walk unexpectedly matched" >&2
    exit 1
fi

core_flags='-O3 -march=rv32im_zicsr -mabi=ilp32 -ffreestanding -DITERATIONS=10000 -DTOTAL_DATA_SIZE=2000'
core_includes="-I$coremark_dir/src -I$jyd_dir/abstract-machine/am/include -I$jyd_dir/abstract-machine/klib/include"
"$cc" $core_flags $core_includes '-DARCH_H="arch/riscv.h"' \
    -mxlistrev -S "$coremark_dir/src/core_list_join.c" -o "$scratch/clj.s"
test "$(grep -Fc '.insn r 0x0b, 6' "$scratch/clj.s")" -eq 4
echo "xlistrev in-place list reversal: PASS"
