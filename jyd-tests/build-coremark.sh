#!/usr/bin/env bash

set -euo pipefail

usage() {
  cat <<'EOF'
Usage: build-coremark.sh --arch ARCH --iterations N [options]

Build the repository's CoreMark source in an isolated temporary copy.

Options:
  --arch ARCH             AbstractMachine target, for example riscv32-nemu
  --iterations N          CoreMark ITERATIONS value, for example 1000 or 10000
  --riscv-zexts VALUE     Override RISCV_ZEXTS (empty is valid)
  --extra-cflags VALUE    Append compiler flags, for example '-O3 -fomit-frame-pointer'
  --source-dir DIR        CoreMark source directory
  --output-dir DIR        Directory receiving the built artifacts
  -h, --help              Show this help
EOF
}

repo_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
arch=
iterations=
riscv_zexts=
extra_cflags=
source_dir="$repo_root/am-kernels/benchmarks/coremark"
output_dir=

while (($# > 0)); do
  case "$1" in
    --arch)
      [[ $# -ge 2 ]] || { echo "--arch requires a value" >&2; exit 2; }
      arch=$2
      shift 2
      ;;
    --iterations)
      [[ $# -ge 2 ]] || { echo "--iterations requires a value" >&2; exit 2; }
      iterations=$2
      shift 2
      ;;
    --riscv-zexts)
      [[ $# -ge 2 ]] || { echo "--riscv-zexts requires a value" >&2; exit 2; }
      riscv_zexts=$2
      shift 2
      ;;
    --extra-cflags)
      [[ $# -ge 2 ]] || { echo "--extra-cflags requires a value" >&2; exit 2; }
      extra_cflags=$2
      shift 2
      ;;
    --source-dir)
      [[ $# -ge 2 ]] || { echo "--source-dir requires a value" >&2; exit 2; }
      source_dir=$2
      shift 2
      ;;
    --output-dir)
      [[ $# -ge 2 ]] || { echo "--output-dir requires a value" >&2; exit 2; }
      output_dir=$2
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "unknown argument: $1" >&2
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -z "$arch" || -z "$iterations" ]]; then
  echo "--arch and --iterations are required" >&2
  usage >&2
  exit 2
fi
if [[ ! "$iterations" =~ ^[1-9][0-9]*$ ]]; then
  echo "--iterations must be a positive decimal integer" >&2
  exit 2
fi
if [[ ! -d "$source_dir" || ! -f "$source_dir/include/core_portme.h" ]]; then
  echo "CoreMark source not found: $source_dir" >&2
  exit 1
fi

if [[ -z "$output_dir" ]]; then
  output_dir="$repo_root/build/coremark/iter${iterations}/${arch}"
fi

work_dir=$(mktemp -d /tmp/jyd-coremark-build.XXXXXX)
trap 'rm -rf "$work_dir"' EXIT
coremark_dir="$work_dir/coremark"
mkdir -p "$coremark_dir"
cp -a "$source_dir/." "$coremark_dir/"
# Never reuse build outputs copied from the source fixture.  They may have
# been compiled with a different ARCH or RISCV_ZEXTS value.
rm -rf "$coremark_dir/build"

# The upstream AM port keeps ITERATIONS in core_portme.h. Patch only this
# disposable copy so the source repository remains a clean input fixture.
sed -E -i "s/^[[:space:]]*#define[[:space:]]+ITERATIONS[[:space:]]+.*/#define ITERATIONS ${iterations}/" \
  "$coremark_dir/include/core_portme.h"

if ! rg -q "^[[:space:]]*#define[[:space:]]+ITERATIONS[[:space:]]+${iterations}[[:space:]]*$" \
  "$coremark_dir/include/core_portme.h"; then
  echo "failed to set ITERATIONS in temporary CoreMark source" >&2
  exit 1
fi

# Keep extra compiler flags separate from CFLAGS itself. A command-line
# CFLAGS assignment would suppress the include flags assembled by the AM
# Makefile, so append through a disposable CoreMark Makefile variable.
if [[ -n "$extra_cflags" ]]; then
  printf '\nCFLAGS += $(EXTRA_CFLAGS)\n' >> "$coremark_dir/Makefile"
fi

# Build the AM libraries in a disposable copy as well.  The normal AM build
# stores archives below abstract-machine/*/build; reusing those archives can
# silently reintroduce extensions from an earlier experiment.
workspace_dir="$work_dir/workspace"
am_home="$workspace_dir/abstract-machine"
mkdir -p "$workspace_dir"
cp -a "$repo_root/abstract-machine/." "$am_home/"
find "$am_home" -type d -name build -prune -exec rm -rf {} +
# The NEMU AM port includes stdio only for commented-out diagnostics.  The
# available RV32 cross sysroot has no glibc ilp32 stubs, so remove those
# unused includes in this disposable copy rather than changing the AM repo.
find "$am_home/am/src/platform/nemu" -type f -name '*.c' -exec \
  sed -i '/^[[:space:]]*#include[[:space:]]*<stdio\.h>/d' {} +
sed -i 's/^[[:space:]]*#include[[:space:]]*<string\.h>/#include <klib.h>/' \
  "$am_home/am/src/platform/nemu/ioe/gpu.c"
sed -i '/^[[:space:]]*#include[[:space:]]*<stdio\.h>/d' \
  "$am_home/klib/src/stdio.c"
sed -i 's/^[[:space:]]*#[[:space:]]*include[[:space:]]*<limits\.h>/#define CHAR_BIT __CHAR_BIT__/' \
  "$am_home/klib/src/int64.c"
sed -i '0,/^#define CHAR_BIT __CHAR_BIT__$/!{/^#define CHAR_BIT __CHAR_BIT__$/d;}' \
  "$am_home/klib/src/int64.c"
ln -s "$repo_root/nemu" "$workspace_dir/nemu"
ln -s "$repo_root/npc" "$workspace_dir/npc"
export JYD_AM_HOME="$am_home"
make_args=(ARCH="$arch" RISCV_ZEXTS="$riscv_zexts" image)
if [[ -n "$extra_cflags" ]]; then
  make_args+=("EXTRA_CFLAGS=$extra_cflags")
fi
make -C "$coremark_dir" "${make_args[@]}"

artifact_dir="$coremark_dir/build"
image="$artifact_dir/coremark-${arch}.bin"
if [[ ! -f "$image" ]]; then
  echo "CoreMark image was not produced: $image" >&2
  exit 1
fi

mkdir -p "$output_dir"
find "$artifact_dir" -maxdepth 1 -type f -exec cp -a {} "$output_dir/" \;

printf 'ITERATIONS=%s\n' "$iterations"
printf 'ARCH=%s\n' "$arch"
printf 'RISCV_ZEXTS=%s\n' "$riscv_zexts"
printf 'EXTRA_CFLAGS=%s\n' "$extra_cflags"
printf 'OUTPUT_DIR=%s\n' "$output_dir"
printf 'IMAGE=%s\n' "$output_dir/$(basename "$image")"
