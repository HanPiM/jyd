#!/usr/bin/env bash
# Build the patched RISC-V GCC used by the default CoreMark configuration.

set -euo pipefail

PREFIX=$(realpath "${1:?usage: build-md-gcc.sh <prefix-dir>}")
PATCH_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)

GCC_BASE=390648994968cf0bca7ab4ebdc28fb055dae02eb
GCC_BASE_DATE=2026-08-11
GCC_BRANCH=releases/gcc-16

NPROC=$(nproc)
if [ "$NPROC" -gt 16 ]; then
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

mkdir -p "$WORK/build"
cd "$WORK/build"
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

make -j"$NPROC" all-gcc
make install-gcc

mkdir -p "$PREFIX/bin"
for tool in ar as ld nm objcopy objdump ranlib readelf strip; do
  ln -sf "/usr/bin/riscv64-linux-gnu-$tool" "$PREFIX/bin/riscv64-linux-gnu-$tool"
done

"$PREFIX/bin/riscv64-linux-gnu-gcc" --version
