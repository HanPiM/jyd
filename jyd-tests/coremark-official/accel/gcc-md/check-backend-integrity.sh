#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
coremark_dir=$(CDPATH= cd -- "$script_dir/../.." && pwd)
patch_file="$script_dir/active-accel-gcc16.patch"
gcc_base=ff20c357b3f62d4ffa76a74ce21fc49b640d61e6
backend_source=gcc/tree-clipped-rising-score.cc
source_heading="diff --git a/$backend_source b/$backend_source"

fail()
{
    echo "backend integrity audit: FAIL: $*" >&2
    exit 1
}

scratch=
cleanup()
{
    if [ -n "$scratch" ]; then
        rm -rf -- "$scratch"
    fi
}
trap cleanup EXIT HUP INT TERM

forbidden='fp12|PSEUDO_FLOAT|current_function_name_is|DECL_NAME|DECL_ASSEMBLER_NAME|IDENTIFIER_POINTER|LOCATION_FILE|main_input_filename|core_list_find|matrix_mul_matrix|core_bench_state|core_state_transition|-fplugin|gcc-plugin.h|PLUGIN_PASS_MANAGER_SETUP'
for file in \
    "$patch_file" \
    "$coremark_dir/Makefile" \
    "$coremark_dir/src/core_portme.h" \
    "$coremark_dir/src/ee_printf.c"
do
    test -r "$file" || fail "cannot read audit input: $file"
    if grep -En "$forbidden" "$file"; then
        fail "found a disallowed selection path in $file"
    else
        grep_status=$?
        test "$grep_status" -eq 1 || fail "could not scan audit input: $file"
    fi
done

for file in "$coremark_dir/Makefile" "$coremark_dir/../rtthread-nano/Makefile"; do
    test -r "$file" || fail "cannot read CRC build input: $file"
    if grep -En 'xcrc_hw\.h|COREMARK_CRC_CALLER|__COREMARK_XCRCU8' "$file"; then
        fail "forced-include CRC selection remains in $file"
    else
        grep_status=$?
        test "$grep_status" -eq 1 || fail "could not scan CRC build input: $file"
    fi
done

test ! -e "$coremark_dir/accel/xcrc_hw.h" \
    || fail "legacy forced-include CRC macro header remains in the tree"

grep -Fq 'fcrc-semantic-lto' "$patch_file" \
    || fail "GCC patch does not provide the explicit CRC semantic-LTO option"
grep -Fq 'pass_ch_crc' "$patch_file" \
    || fail "GCC patch is missing the CRC-specific early loop-shape pass"
grep -Fq 'pass_crc_semantic_wrappers' "$patch_file" \
    || fail "GCC patch is missing the LTO CRC semantic propagation pass"

if ! unexpected_extensions=$(find "$coremark_dir/accel" -maxdepth 1 -type f \
    \( -name '*.cc' -o -name '*.so' \) -print -quit); then
    fail "could not scan accel/ for alternate compiler extensions"
fi
if [ -n "$unexpected_extensions" ]; then
    fail "unexpected compiler extension file under accel/"
fi

source_count=$(awk -v heading="$source_heading" \
    '$0 == heading { count++ } END { print count + 0 }' "$patch_file") \
    || fail "could not inspect patch structure"
if [ "$source_count" -ne 1 ]; then
    fail "patch must carry exactly one $backend_source new-file diff"
fi

scratch=$(mktemp -d "${TMPDIR:-/tmp}/backend-integrity.XXXXXX")
source_patch="$scratch/backend-source.patch"
awk -v heading="$source_heading" '
    $0 == heading { emit = 1 }
    emit && /^diff --git / && $0 != heading { exit }
    emit { print }
' "$patch_file" > "$source_patch"

grep -Fqx 'new file mode 100644' "$source_patch" || fail "$backend_source is not a new regular file"
grep -Fqx -- '--- /dev/null' "$source_patch" || fail "$backend_source does not originate from /dev/null"
grep -Fqx "+++ b/$backend_source" "$source_patch" || fail "$backend_source has no patch destination"

mkdir -p "$scratch/extract"
git -C "$scratch/extract" init -q
git -C "$scratch/extract" apply --check "$source_patch"
git -C "$scratch/extract" apply "$source_patch"
test -s "$scratch/extract/$backend_source" || fail "patch did not materialize $backend_source"
grep -Fq 'make_pass_clipped_rising_score' "$scratch/extract/$backend_source" \
    || fail "$backend_source is missing its pass constructor"

echo "backend patch source audit: PASS"

case $# in
0)
    ;;
2)
    if [ "$1" != "--apply-to-clean-tree" ]; then
        fail "usage: $0 [--apply-to-clean-tree GCC_SOURCE_DIR]"
    fi

    gcc_tree=$(CDPATH= cd -- "$2" && pwd) || fail "cannot enter GCC source tree: $2"
    test "$(git -C "$gcc_tree" rev-parse --is-inside-work-tree 2>/dev/null)" = true \
        || fail "not a GCC git worktree: $gcc_tree"
    actual_base=$(git -C "$gcc_tree" rev-parse HEAD)
    test "$actual_base" = "$gcc_base" \
        || fail "GCC base is $actual_base, expected $gcc_base"
    test -z "$(git -C "$gcc_tree" status --porcelain --untracked-files=all)" \
        || fail "GCC source tree is not clean before patch application"
    test ! -e "$gcc_tree/$backend_source" \
        || fail "$backend_source exists before patch application"

    git -C "$gcc_tree" apply --check --index --unidiff-zero "$patch_file"
    git -C "$gcc_tree" apply --index --unidiff-zero "$patch_file"
    git -C "$gcc_tree" diff --cached --check
    test -s "$gcc_tree/$backend_source" || fail "full patch did not materialize $backend_source"
    added_source=$(git -C "$gcc_tree" diff --cached --diff-filter=A --name-only -- "$backend_source") \
        || fail "could not inspect applied patch"
    test "$added_source" = "$backend_source" \
        || fail "$backend_source was not supplied by the full patch"
    grep -Fq 'make_pass_clipped_rising_score' "$gcc_tree/$backend_source" \
        || fail "$backend_source is missing its pass constructor after full apply"
    echo "clean GCC patch apply audit: PASS ($actual_base)"
    ;;
*)
    fail "usage: $0 [--apply-to-clean-tree GCC_SOURCE_DIR]"
    ;;
esac

echo "backend integrity audit: PASS"
