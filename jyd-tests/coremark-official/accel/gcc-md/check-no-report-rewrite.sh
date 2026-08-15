#!/bin/sh
set -eu

script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
coremark_dir=$(CDPATH= cd -- "$script_dir/../.." && pwd)

forbidden='mcoremark-fp12-report|riscv_coremark_fp12_report|__fp12_|coremark_report_replacement|replace_coremark_report_calls|xaccel_plugin-report|float_dump|PSEUDO_FLOAT'
files="$script_dir/active-accel-gcc16.patch
$coremark_dir/Makefile
$coremark_dir/src/core_portme.h
$coremark_dir/src/ee_printf.c
$coremark_dir/accel/xaccel_plugin.cc"

if grep -En "$forbidden" $files; then
    echo "report-call rewriting or pseudo-float support is still present" >&2
    exit 1
fi

echo "report rewriting audit: PASS"
