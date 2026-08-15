#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
coremark_dir=$(CDPATH= cd -- "$script_dir/../.." && pwd)

forbidden='fp12|PSEUDO_FLOAT|current_function_name_is|DECL_NAME|IDENTIFIER_POINTER|core_list_find|matrix_mul_matrix|core_bench_state|core_state_transition|-fplugin|gcc-plugin.h|PLUGIN_PASS_MANAGER_SETUP'
files="$script_dir/active-accel-gcc16.patch
$coremark_dir/Makefile
$coremark_dir/src/core_portme.h
$coremark_dir/src/ee_printf.c"

if grep -En "$forbidden" $files; then
    echo "backend integrity audit found a disallowed selection path" >&2
    exit 1
fi

if find "$coremark_dir/accel" -maxdepth 1 -type f \( -name '*.cc' -o -name '*.so' \) | grep -q .; then
    echo "unexpected compiler extension file under accel/" >&2
    exit 1
fi

echo "backend integrity audit: PASS"
