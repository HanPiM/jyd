#!/usr/bin/env bash
# Build the patched RV32-capable RISC-V cross compiler used by the CI
# coremark-official job.  The active-accel-gcc16.patch is applied on top of
# the pinned upstream base recorded below, which GitHub serves through the
# releases/gcc-16 branch; the shallow-since window must cover the base date.
#
# Usage: build-md-gcc.sh <prefix-dir>
#
# The prefix receives riscv64-linux-gnu-gcc plus symlinks to the distro's
# RISC-V binutils so the Abstract-Machine toolchain prefix is complete.

set -euo pipefail

PREFIX=$(realpath "${1:?usage: build-md-gcc.sh <prefix-dir>}")
PATCH_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

GCC_BASE=ff20c357b3f6
GCC_BASE_DATE=2026-08-09
GCC_BRANCH=releases/gcc-16

NPROC=$(nproc)
if [ "$NPROC" -gt 16 ]; then
  # GCC's memory-hungry files need roughly 2 GB each at peak parallelism.
  NPROC=16
fi

WORK=$(mktemp -d "${TMPDIR:-/tmp}/md-gcc-build.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

echo "Fetching gcc-mirror ${GCC_BRANCH} (base ${GCC_BASE})"
git init -q "$WORK/src"
git -C "$WORK/src" remote add origin https://github.com/gcc-mirror/gcc.git
git -C "$WORK/src" fetch --quiet --filter=blob:none \
  --shallow-since="$GCC_BASE_DATE" origin "$GCC_BRANCH"
git -C "$WORK/src" checkout --quiet --detach "$GCC_BASE"
git -C "$WORK/src" apply "$PATCH_DIR/active-accel-gcc16.patch"
echo "Active accelerator patch applied"

mkdir -p "$WORK/build"
cd "$WORK/build"
echo "Configuring gcc for target riscv64-linux-gnu"
"$WORK/src/configure" \
  --target=riscv64-linux-gnu \
  --prefix="$PREFIX" \
  --with-sysroot=/usr/riscv64-linux-gnu \
  --with-native-system-header-dir=/include \
  --with-as=/usr/bin/riscv64-linux-gnu-as \
  --with-ld=/usr/bin/riscv64-linux-gnu-ld \
  --enable-languages=c \
  --disable-bootstrap \
  --disable-multilib \
  --disable-shared \
  --disable-threads \
  --disable-nls \
  --disable-werror \
  --disable-libsanitizer \
  --disable-libquadmath \
  --disable-libssp \
  --disable-libgomp \
  --disable-libatomic \
  --with-gmp=/usr \
  --with-mpfr=/usr \
  --with-mpc=/usr \
  --with-system-zlib

echo "Building gcc (C compiler only, -j$NPROC)"
make -j"$NPROC" all-gcc
make install-gcc

echo "Linking distro RISC-V binutils into the prefix"
mkdir -p "$PREFIX/bin"
for tool in ar as ld nm objcopy objdump ranlib readelf strip; do
  ln -sf "/usr/bin/riscv64-linux-gnu-$tool" "$PREFIX/bin/riscv64-linux-gnu-$tool"
done

"$PREFIX/bin/riscv64-linux-gnu-gcc" --version
echo "MD toolchain installed under $PREFIX"
