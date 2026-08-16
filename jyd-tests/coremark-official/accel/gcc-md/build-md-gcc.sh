#!/usr/bin/env bash
# Build the patched RISC-V GCC used by the default CoreMark configuration.

set -euo pipefail

PREFIX=$(realpath "${1:?usage: build-md-gcc.sh <prefix-dir>}")
PATCH_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
HOST_CROSS_PREFIX=${HOST_CROSS_PREFIX:-riscv64-linux-gnu-}
HOST_CC=$(command -v "${HOST_CROSS_PREFIX}gcc")
HOST_AS=$(command -v "${HOST_CROSS_PREFIX}as")
HOST_LD=$(command -v "${HOST_CROSS_PREFIX}ld")
HOST_SYSROOT=${HOST_SYSROOT:-$($HOST_CC -print-sysroot)}
HOST_MULTIARCH=$($HOST_CC -print-multiarch)
if [ -z "$HOST_MULTIARCH" ]; then
  HOST_MULTIARCH=$($HOST_CC -dumpmachine)
fi
PKG_CONFIG=${PKG_CONFIG:-pkg-config}
HOST_BUILD_CC=${HOST_BUILD_CC:-${CC:-cc}}

dependency_prefix() {
  local module=$1
  local header=$2

  if command -v "$PKG_CONFIG" >/dev/null 2>&1 && "$PKG_CONFIG" --exists "$module"; then
    "$PKG_CONFIG" --variable=prefix "$module"
  elif printf '#include <%s>\n' "$header" | "$HOST_BUILD_CC" -E -x c - >/dev/null 2>&1; then
    # Debian development packages install these dependencies under /usr, but
    # libmpc-dev does not provide an mpc.pc file.
    printf '%s\n' /usr
  else
    echo "cannot locate $module development files; set ${module^^}_PREFIX" >&2
    return 1
  fi
}

GMP_PREFIX=${GMP_PREFIX:-$(dependency_prefix gmp gmp.h)}
MPFR_PREFIX=${MPFR_PREFIX:-$(dependency_prefix mpfr mpfr.h)}
MPC_PREFIX=${MPC_PREFIX:-$(dependency_prefix mpc mpc.h)}

# Debian cross compilers commonly report / (or nothing) as their sysroot and
# keep target headers in a multiarch directory. Never substitute host headers.
if [ -z "$HOST_SYSROOT" ]; then
  HOST_SYSROOT=/
fi
HOST_SYSROOT=$(realpath -m "$HOST_SYSROOT")
if [ -z "${NATIVE_SYSTEM_HEADER_DIR:-}" ]; then
  if [ "$HOST_SYSROOT" = / ]; then
    if [ -z "$HOST_MULTIARCH" ]; then
      echo "cannot derive target headers: cross compiler reports no multiarch tuple" >&2
      exit 1
    fi
    NATIVE_SYSTEM_HEADER_DIR="/usr/$HOST_MULTIARCH/include"
  else
    NATIVE_SYSTEM_HEADER_DIR=/usr/include
  fi
fi
case "$NATIVE_SYSTEM_HEADER_DIR" in
  /*) ;;
  *)
    echo "NATIVE_SYSTEM_HEADER_DIR must be absolute: $NATIVE_SYSTEM_HEADER_DIR" >&2
    exit 1
    ;;
esac
if [ "$HOST_SYSROOT" = / ]; then
  SYSTEM_HEADER_PATH=$(realpath -m "$NATIVE_SYSTEM_HEADER_DIR")
else
  SYSTEM_HEADER_PATH=$(realpath -m "$HOST_SYSROOT$NATIVE_SYSTEM_HEADER_DIR")
fi
if [ "$SYSTEM_HEADER_PATH" = /usr/include ]; then
  echo "refusing to use host headers for cross GCC: $SYSTEM_HEADER_PATH" >&2
  exit 1
fi
if [ ! -f "$SYSTEM_HEADER_PATH/stdint.h" ]; then
  echo "missing target system header: $SYSTEM_HEADER_PATH/stdint.h" >&2
  exit 1
fi

echo "Using target system headers: $SYSTEM_HEADER_PATH"
echo "Using GMP/MPFR/MPC prefixes: $GMP_PREFIX $MPFR_PREFIX $MPC_PREFIX"

GCC_BASE=ff20c357b3f62d4ffa76a74ce21fc49b640d61e6

NPROC=$(nproc)
if [ "$NPROC" -gt 16 ]; then
  NPROC=16
fi

WORK=$(mktemp -d "${TMPDIR:-/tmp}/md-gcc-build.XXXXXX")
trap 'rm -rf "$WORK"' EXIT

echo "Fetching gcc-mirror base ${GCC_BASE}"
git init -q "$WORK/src"
git -C "$WORK/src" remote add origin https://github.com/gcc-mirror/gcc.git
git -C "$WORK/src" fetch --quiet --filter=blob:none --depth=1 origin "$GCC_BASE"
git -C "$WORK/src" checkout --quiet --detach "$GCC_BASE"
"$PATCH_DIR/check-backend-integrity.sh" --apply-to-clean-tree "$WORK/src"

mkdir -p "$WORK/build"
cd "$WORK/build"
"$WORK/src/configure" \
  --target=riscv64-unknown-linux-gnu \
  --prefix="$PREFIX" \
  --with-sysroot="$HOST_SYSROOT" \
  --with-native-system-header-dir="$NATIVE_SYSTEM_HEADER_DIR" \
  --with-as="$HOST_AS" \
  --with-ld="$HOST_LD" \
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
  --with-gmp="$GMP_PREFIX" \
  --with-mpfr="$MPFR_PREFIX" \
  --with-mpc="$MPC_PREFIX" \
  --with-system-zlib

make -j"$NPROC" all-gcc
make install-gcc

mkdir -p "$PREFIX/bin"
for tool in ar as ld nm objcopy objdump ranlib readelf strip; do
  ln -sf "$(command -v "${HOST_CROSS_PREFIX}$tool")" \
    "$PREFIX/bin/riscv64-unknown-linux-gnu-$tool"
done

"$PREFIX/bin/riscv64-unknown-linux-gnu-gcc" --version
