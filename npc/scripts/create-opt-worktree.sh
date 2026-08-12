#!/usr/bin/env bash
#
# create-opt-worktree.sh - create and prepare an isolated optimization worktree.
#
# Automates the manual setup steps used for JYD timing/performance candidates:
#   * git worktree add under $JYD_DATA_ROOT/worktrees/
#   * link local ignored/untracked dependencies (am-kernels sources, npc/deps,
#     coremark build outputs, rt-thread-am)
#   * make ../riscv-arch-test-am-jyd resolve from the worktree's parent
#   * install a proven prebuilt NEMU dependency tree
#   * seed matching Vivado IP output products and completed OOC runs
#   * import the formal CoreMark COE pair into cur_coe
#
# Conventions (AGENTS.md):
#   * $JYD_DATA_ROOT defaults to /srv/data/jyd; never use /tmp for JYD data.
#   * Large shared directories are symlinked, not copied.
#   * No destructive commands; an existing target worktree is an error.
#
# Usage:
#   npc/scripts/create-opt-worktree.sh [--commit <ref>] [--branch <name>]
#       [--name <dir>] [--src <main-repo>] [--nemu-ref <dir>]
#       [--coe-dir <dir>] [--skip-coe-check] [--verify-sim]

set -euo pipefail

usage() {
  cat <<'EOF'
create-opt-worktree.sh - create and prepare an isolated optimization worktree.

Automates the setup steps used for JYD optimization candidates:
  * git worktree add under $JYD_DATA_ROOT/worktrees/
  * link local ignored/untracked dependencies (am-kernels, npc/deps,
    coremark build outputs, rt-thread-am)
  * make ../riscv-arch-test-am-jyd resolve from the worktree's parent
  * install a proven prebuilt NEMU dependency tree
  * seed matching Vivado IP output products and completed OOC runs
  * import the formal CoreMark COE pair into cur_coe

Usage:
  create-opt-worktree.sh [options]

Options:
  --commit <ref>       Commit to check out (default: HEAD of --src).
  --branch <name>      New branch name (default: opt-<short-commit>).
  --name <dir>         Worktree directory name under $JYD_DATA_ROOT/worktrees
                       (default: the branch name).
  --src <main-repo>    Main repository (default: auto-detected main worktree).
  --nemu-ref <dir>     Directory whose generated NEMU configuration, build
                       tree, generated instructions, fixdep, SoftFloat, and
                       sdb archive are installed
                       (default: --src).  Use these artifacts unchanged when
                       NEMU source/config has not changed; do not rebuild NEMU
                       in a candidate worktree.
  --difftest-ref <dir> Backward-compatible alias for --nemu-ref.
  --coe-dir <dir>      Directory with coremark-official-riscv32-jyd.{text,data}.coe
                       (default: <src>/jyd-tests/coremark-official/build/
                       iter10000-...-x_xbmul_xcrcu8_xlistrev_xmsum_xdfa4h-...).
  --skip-coe-check     Allow COE hashes to differ from the frozen formal pair.
  --verify-sim         Build and run the riscv32-jyd add smoke test, then verify
                       its simulator banner and image path belong to this
                       worktree.
  -h|--help            Show this help.
EOF
}

JYD_DATA_ROOT="${JYD_DATA_ROOT:-/srv/data/jyd}"
WT_BASE="$JYD_DATA_ROOT/worktrees"

SRC=""
COMMIT=""
BRANCH=""
NAME=""
NEMU_REF=""
COE_DIR=""
SKIP_COE_CHECK=0
VERIFY_SIM=0

while [[ $# -gt 0 ]]; do
  case "$1" in
    --commit) COMMIT="$2"; shift 2 ;;
    --branch) BRANCH="$2"; shift 2 ;;
    --name) NAME="$2"; shift 2 ;;
    --src) SRC="$2"; shift 2 ;;
    --nemu-ref|--difftest-ref) NEMU_REF="$2"; shift 2 ;;
    --coe-dir) COE_DIR="$2"; shift 2 ;;
    --skip-coe-check) SKIP_COE_CHECK=1; shift ;;
    --verify-sim) VERIFY_SIM=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown option: $1" >&2; usage; exit 1 ;;
  esac
done

# --- locate the main repository ---------------------------------------------
if [[ -z "$SRC" ]]; then
  SRC="$(git worktree list --porcelain | awk 'NR == 1 && $1 == "worktree" {print $2}')"
fi
if [[ -z "$SRC" || ! -d "$SRC/.git" && ! -d "$SRC" ]]; then
  echo "error: cannot locate the main repository (--src)" >&2
  exit 1
fi
SRC="$(cd "$SRC" && pwd)"
NEMU_REF="${NEMU_REF:-$SRC}"

COMMIT="${COMMIT:-$(git -C "$SRC" rev-parse HEAD)}"
COMMIT_FULL="$(git -C "$SRC" rev-parse "$COMMIT^{commit}")"
COMMIT_SHORT="$(git -C "$SRC" rev-parse --short "$COMMIT_FULL")"
BRANCH="${BRANCH:-opt-$COMMIT_SHORT}"
NAME="${NAME:-$BRANCH}"

WT_DIR="$WT_BASE/$NAME"
if [[ -e "$WT_DIR" ]]; then
  echo "error: worktree directory already exists: $WT_DIR" >&2
  exit 1
fi

# --- create the worktree ----------------------------------------------------
mkdir -p "$WT_BASE"
echo "== creating worktree: $WT_DIR (branch $BRANCH, commit $COMMIT_FULL)"
git -C "$SRC" worktree add -b "$BRANCH" "$WT_DIR" "$COMMIT_FULL"

cleanup() {
  echo "error: setup failed, removing incomplete worktree $WT_DIR" >&2
  git -C "$SRC" worktree remove --force "$WT_DIR" 2>/dev/null || true
  git -C "$SRC" branch -D "$BRANCH" 2>/dev/null || true
}
trap cleanup ERR

# --- link local dependencies (symlink, never copy) --------------------------
link_dep() { # link_dep <src> <dst>
  local s="$1" d="$2"
  if [[ -e "$s" ]]; then
    mkdir -p "$(dirname "$d")"
    ln -sfn "$s" "$d"
    echo "   linked $s -> $d"
  else
    echo "   skipped (source missing): $s"
  fi
}

# Keep the am-kernels directory hierarchy local to the worktree.  GNU make
# resolves `-C am-kernels/...` through a directory symlink, which makes CURDIR
# point into SRC and causes Abstract Machine to select SRC/npc as its simulator.
# A symlink farm shares the source files while preserving worktree-local build
# directories, generated Makefiles, and repository-relative AM/NPC paths.
if [[ -d "$SRC/am-kernels" ]]; then
  mkdir -p "$WT_DIR/am-kernels"
  while IFS= read -r -d '' source_dir; do
    relative_dir="${source_dir#"$SRC/am-kernels"/}"
    [[ "$source_dir" == "$SRC/am-kernels" ]] && relative_dir=""
    mkdir -p "$WT_DIR/am-kernels/$relative_dir"
  done < <(find "$SRC/am-kernels" \
    \( -name .git -o -name build -o -name out \) -prune -o \
    -type d -print0)
  while IFS= read -r -d '' source_file; do
    relative_file="${source_file#"$SRC/am-kernels"/}"
    ln -s "$source_file" "$WT_DIR/am-kernels/$relative_file"
  done < <(find "$SRC/am-kernels" \
    \( -name .git -o -name build -o -name out \) -prune -o \
    \( -name .result -o -name 'Makefile.*' \) -prune -o \
    \( -type f -o -type l \) -print0)
  if [[ -L "$WT_DIR/am-kernels" || -L "$WT_DIR/am-kernels/tests/cpu-tests" ]]; then
    echo "error: am-kernels directory hierarchy must remain worktree-local" >&2
    exit 1
  fi
  echo "   linked am-kernels source files into $WT_DIR/am-kernels"
else
  echo "   skipped (source missing): $SRC/am-kernels"
fi
link_dep "$SRC/npc/deps" "$WT_DIR/npc/deps"
link_dep "$SRC/jyd-tests/coremark-official/build" \
  "$WT_DIR/jyd-tests/coremark-official/build"
link_dep "$SRC/rt-thread-am" "$WT_DIR/rt-thread-am"

# riscv-arch-test-am-jyd is referenced as ../riscv-arch-test-am-jyd from the
# repository root, so the symlink must live next to the worktree, not inside it.
ARCH_TEST_LINK="$WT_BASE/riscv-arch-test-am-jyd"
if [[ -e "$ARCH_TEST_LINK" || -L "$ARCH_TEST_LINK" ]]; then
  echo "   riscv-arch-test-am-jyd already present: $ARCH_TEST_LINK"
else
  ln -sfn /home/hanpi/gitclone/riscv-arch-test-am-jyd "$ARCH_TEST_LINK"
  echo "   linked /home/hanpi/gitclone/riscv-arch-test-am-jyd -> $ARCH_TEST_LINK"
fi

# --- reusable Vivado IP/OOC state ------------------------------------------
# These ignored products are independent of candidate RTL, but only when the
# checked-out IP configurations match the source project. Vivado strips a
# terminal newline while generating output products, so normalize only that
# byte when comparing XCI manifests.
VIVADO_REF_PROJECT="$SRC/jyd-vivado-proj"
VIVADO_WT_PROJECT="$WT_DIR/jyd-vivado-proj"
VIVADO_REF_IP="$VIVADO_REF_PROJECT/digital_twin.srcs/sources_1/ip"
VIVADO_WT_IP="$VIVADO_WT_PROJECT/digital_twin.srcs/sources_1/ip"
if [[ -d "$VIVADO_REF_IP" && -d "$VIVADO_WT_IP" ]]; then
  REF_XCI_MANIFEST="$JYD_DATA_ROOT/tmp/create-opt-ref-xci.$$.sha256"
  WT_XCI_MANIFEST="$JYD_DATA_ROOT/tmp/create-opt-wt-xci.$$.sha256"
  mkdir -p "$JYD_DATA_ROOT/tmp"
  (
    cd "$VIVADO_REF_IP"
    while IFS= read -r -d '' xci_file; do
      printf '%s  %s\n' "$(sed -e '$a\' "$xci_file" | sha256sum | awk '{print $1}')" "$xci_file"
    done < <(find . -type f -name '*.xci' -print0 | sort -z)
  ) >"$REF_XCI_MANIFEST"
  (
    cd "$VIVADO_WT_IP"
    while IFS= read -r -d '' xci_file; do
      printf '%s  %s\n' "$(sed -e '$a\' "$xci_file" | sha256sum | awk '{print $1}')" "$xci_file"
    done < <(find . -type f -name '*.xci' -print0 | sort -z)
  ) >"$WT_XCI_MANIFEST"
  if cmp -s "$REF_XCI_MANIFEST" "$WT_XCI_MANIFEST"; then
    # Vivado emits some synthesis products beside each XCI instead of under
    # digital_twin.gen. Copy those ignored files without ever replacing the
    # target commit's tracked IP configuration.
    while IFS= read -r -d '' generated_dir; do
      relative_dir="${generated_dir#"$VIVADO_REF_IP"/}"
      [[ "$generated_dir" == "$VIVADO_REF_IP" ]] && relative_dir=""
      mkdir -p "$VIVADO_WT_IP/$relative_dir"
    done < <(find "$VIVADO_REF_IP" -type d -print0)
    while IFS= read -r -d '' generated_file; do
      relative_file="${generated_file#"$VIVADO_REF_IP"/}"
      cp -a "$generated_file" "$VIVADO_WT_IP/$relative_file"
    done < <(find "$VIVADO_REF_IP" \( -type f -o -type l \) ! -name '*.xci' -print0)
    echo "   copied Vivado IP in-tree output products"

    for cache_dir in digital_twin.gen digital_twin.cache digital_twin.ip_user_files; do
      if [[ -d "$VIVADO_REF_PROJECT/$cache_dir" ]]; then
        cp -a --reflink=auto "$VIVADO_REF_PROJECT/$cache_dir" "$VIVADO_WT_PROJECT/"
        echo "   copied Vivado IP state: $cache_dir"
      fi
    done
    if [[ -d "$VIVADO_REF_PROJECT/digital_twin.runs" ]]; then
      mkdir -p "$VIVADO_WT_PROJECT/digital_twin.runs"
      while IFS= read -r -d '' run_dir; do
        if [[ -f "$run_dir/.vivado.end.rst" ]] && find "$run_dir" -maxdepth 1 -type f -name '*.dcp' -print -quit | grep -q .; then
          cp -a --reflink=auto "$run_dir" "$VIVADO_WT_PROJECT/digital_twin.runs/"
          echo "   copied completed Vivado OOC run: $(basename "$run_dir")"
        else
          echo "   skipped incomplete Vivado OOC run: $(basename "$run_dir")"
        fi
      done < <(find "$VIVADO_REF_PROJECT/digital_twin.runs" -mindepth 1 -maxdepth 1 \
        -type d -name '*_synth_1' -print0 | sort -z)
    fi
  else
    echo "   skipped Vivado IP/OOC state: source and target XCI manifests differ"
  fi
  rm -f "$REF_XCI_MANIFEST" "$WT_XCI_MANIFEST"
else
  echo "   skipped Vivado IP/OOC state: IP source directory missing"
fi

# --- prebuilt NEMU artifacts -------------------------------------------------
# Candidate worktrees do not rebuild NEMU unless their NEMU source or
# configuration changes.  Copy the verified native executable as well as the
# difftest shared object so offline NEMU profiling and NPC difftest use known
# inputs without a dependency rebuild or network fetch.
if [[ ! -f "$NEMU_REF/nemu/.config" ]]; then
  echo "error: --nemu-ref has no nemu/.config: $NEMU_REF" >&2
  exit 1
fi
if [[ ! -x "$NEMU_REF/nemu/build/riscv32-nemu-interpreter" ]]; then
  echo "error: --nemu-ref has no built nemu/build/riscv32-nemu-interpreter: $NEMU_REF" >&2
  exit 1
fi
if [[ ! -x "$NEMU_REF/nemu/build/riscv32-nemu-interpreter-so" ]]; then
  echo "error: --nemu-ref has no built nemu/build/riscv32-nemu-interpreter-so: $NEMU_REF" >&2
  exit 1
fi
for required in \
  nemu/include/generated/autoconf.h \
  nemu/include/config/auto.conf \
  nemu/tools/fixdep/build/fixdep \
  nemu/tools/gen-inst/build/out.cc \
  nemu/tools/softfloat/repo/source/include/softfloat.h \
  nemu/tools/softfloat/repo/build/Linux-x86_64-GCC/softfloat.a \
  sdb/build/libsdb.a; do
  if [[ ! -e "$NEMU_REF/$required" ]]; then
    echo "error: --nemu-ref has no prebuilt dependency $required: $NEMU_REF" >&2
    exit 1
  fi
done

# Validate the reference tree before copying it.  The relocation step below
# deliberately refreshes timestamps in the destination, so a destination-only
# make -q check cannot detect a stale reference binary.
if ! make -s -q -C "$NEMU_REF/nemu" \
  "$NEMU_REF/nemu/build/riscv32-nemu-interpreter"; then
  echo "error: --nemu-ref interpreter is older than its source/configuration: $NEMU_REF" >&2
  echo "       rebuild it with: make -C $NEMU_REF/nemu" >&2
  exit 1
fi
if ! make -s -q -C "$NEMU_REF/nemu" SHARE=1 \
  "$NEMU_REF/nemu/build/riscv32-nemu-interpreter-so"; then
  echo "error: --nemu-ref interpreter-so is older than its source/configuration: $NEMU_REF" >&2
  echo "       rebuild it with: make -C $NEMU_REF/nemu SHARE=1" >&2
  exit 1
fi

mkdir -p "$WT_DIR/nemu/build" "$WT_DIR/nemu/include" \
  "$WT_DIR/nemu/tools/fixdep" "$WT_DIR/nemu/tools/gen-inst" \
  "$WT_DIR/nemu/tools/softfloat/repo/source" \
  "$WT_DIR/nemu/tools/softfloat/repo/build/Linux-x86_64-GCC" \
  "$WT_DIR/sdb/build"
cp "$NEMU_REF/nemu/.config" "$WT_DIR/nemu/.config"
cp -a "$NEMU_REF/nemu/include/generated" "$WT_DIR/nemu/include/"
cp -a "$NEMU_REF/nemu/include/config" "$WT_DIR/nemu/include/"
cp -a "$NEMU_REF/nemu/tools/fixdep/build" "$WT_DIR/nemu/tools/fixdep/"
cp -a "$NEMU_REF/nemu/tools/gen-inst/build" "$WT_DIR/nemu/tools/gen-inst/"
cp -a "$NEMU_REF/nemu/build/." "$WT_DIR/nemu/build/"
cp -a "$NEMU_REF/nemu/tools/softfloat/repo/source/include" \
  "$WT_DIR/nemu/tools/softfloat/repo/source/"
cp -a "$NEMU_REF/nemu/tools/softfloat/repo/build/Linux-x86_64-GCC/softfloat.a" \
  "$WT_DIR/nemu/tools/softfloat/repo/build/Linux-x86_64-GCC/softfloat.a"
cp -a "$NEMU_REF/sdb/build/libsdb.a" "$WT_DIR/sdb/build/libsdb.a"

# Sources outside nemu/ have absolute object paths.  Relocate those copied
# objects to the paths this worktree's Makefile computes.
for obj_dir in "$WT_DIR"/nemu/build/obj-*; do
  embedded_ref="$obj_dir/${NEMU_REF#/}"
  embedded_wt="$obj_dir/${WT_DIR#/}"
  if [[ -d "$embedded_ref" ]]; then
    mkdir -p "$(dirname "$embedded_wt")"
    cp -a "$embedded_ref" "$embedded_wt"
  fi
done

# fixdep records absolute source and object paths.  Point the copied dependency
# files at this worktree, then make the verified outputs newer than the freshly
# checked-out sources so make only launches the prebuilt executable.
NEMU_REF_SED="${NEMU_REF//\\/\\\\}"
NEMU_REF_SED="${NEMU_REF_SED//|/\\|}"
NEMU_REF_SED="${NEMU_REF_SED//&/\\&}"
WT_DIR_SED="${WT_DIR//\\/\\\\}"
WT_DIR_SED="${WT_DIR_SED//|/\\|}"
WT_DIR_SED="${WT_DIR_SED//&/\\&}"
find "$WT_DIR/nemu/build" -type f -name '*.d' -exec \
  sed -i "s|$NEMU_REF_SED|$WT_DIR_SED|g" {} +
touch "$WT_DIR/nemu/.config"
find "$WT_DIR/nemu/include/generated" "$WT_DIR/nemu/include/config" \
  "$WT_DIR/nemu/tools/fixdep/build" "$WT_DIR/nemu/tools/gen-inst/build" \
  "$WT_DIR/nemu/tools/softfloat/repo/source/include" \
  -type f -exec touch {} +
touch "$WT_DIR/nemu/tools/softfloat/repo/build/Linux-x86_64-GCC/softfloat.a" \
  "$WT_DIR/sdb/build/libsdb.a"
find "$WT_DIR/nemu/build" -type f -exec touch {} +

if ! make -s -q -C "$WT_DIR/nemu" \
  "$WT_DIR/nemu/build/riscv32-nemu-interpreter"; then
  echo "error: installed NEMU interpreter dependency tree is not up to date" >&2
  make -n -C "$WT_DIR/nemu" "$WT_DIR/nemu/build/riscv32-nemu-interpreter" >&2 || true
  exit 1
fi
if ! make -s -q -C "$WT_DIR/nemu" SHARE=1 \
  "$WT_DIR/nemu/build/riscv32-nemu-interpreter-so"; then
  echo "error: installed NEMU interpreter-so dependency tree is not up to date" >&2
  make -n -C "$WT_DIR/nemu" SHARE=1 \
    "$WT_DIR/nemu/build/riscv32-nemu-interpreter-so" >&2 || true
  exit 1
fi
echo "   nemu/.config sha256: $(sha256sum "$WT_DIR/nemu/.config" | awk '{print $1}')"
echo "   interpreter sha256: $(sha256sum "$WT_DIR/nemu/build/riscv32-nemu-interpreter" | awk '{print $1}')"
echo "   interpreter-so sha256: $(sha256sum "$WT_DIR/nemu/build/riscv32-nemu-interpreter-so" | awk '{print $1}')"

# --- formal COE pair ---------------------------------------------------------
if [[ -z "$COE_DIR" ]]; then
  COE_DIR="$SRC/jyd-tests/coremark-official/build/iter10000-data2000-z_zba_zbb_zbc_zbs_zbkb_zbkx-x_xbmul_xcrcu8_xlistrev_xmsum_xdfa4h-cdefault-lto0-pf1"
fi
case "/$COE_DIR/" in
  */iter10000-*) ;;
  *)
    echo "error: formal COE directory is not identified as ITERATIONS=10000: $COE_DIR" >&2
    echo "       rebuild CoreMark with ITERATIONS=10000; iter1000 COEs are never valid board inputs" >&2
    exit 1
    ;;
esac
if [[ ! -f "$COE_DIR/coremark-official-riscv32-jyd.text.coe" || \
      ! -f "$COE_DIR/coremark-official-riscv32-jyd.data.coe" ]]; then
  echo "error: COE files missing in --coe-dir: $COE_DIR" >&2
  exit 1
fi
CUR_COE="$WT_DIR/jyd-vivado-proj/digital_twin.srcs/sources_1/imports/cur_coe"
mkdir -p "$CUR_COE"
cp "$COE_DIR/coremark-official-riscv32-jyd.text.coe" "$CUR_COE/irom.coe"
cp "$COE_DIR/coremark-official-riscv32-jyd.data.coe" "$CUR_COE/dram.coe"
COE_TEXT="$(sha256sum "$CUR_COE/irom.coe" | awk '{print $1}')"
COE_DATA="$(sha256sum "$CUR_COE/dram.coe" | awk '{print $1}')"
cat >"$CUR_COE/coremark-workload.env" <<EOF
COREMARK_ITERATIONS=10000
COREMARK_TOTAL_DATA_SIZE=2000
COREMARK_SOURCE_DIR=$COE_DIR
COREMARK_IROM_SHA256=$COE_TEXT
COREMARK_DRAM_SHA256=$COE_DATA
EOF
echo "   cur_coe/irom.coe sha256: $COE_TEXT"
echo "   cur_coe/dram.coe sha256: $COE_DATA"
echo "   cur_coe workload: ITERATIONS=10000"

if [[ "$SKIP_COE_CHECK" -eq 0 ]]; then
  FROZEN_TEXT="3867cfc979c4e452b5f77be6ce568ea36cc2014bef6fe8fb68011ad0a451bf2b"
  FROZEN_DATA="83fecbb32572a559fd3d9f09b8835cafb5ef70e5bd36aaf0cbd0289488fe56f4"
  HIST_TEXT="5067088dee8da04cc366d6334f5e8dda2cd97f13679ae8d377f146d6a3e008f9"
  HIST_DATA="07b2cff9328da907f4db3510ebfb1c441f509ceae6c8c70cf3075c94a41a8254"
  if [[ "$COE_TEXT" != "$FROZEN_TEXT" || "$COE_DATA" != "$FROZEN_DATA" ]]; then
    echo "error: COE pair differs from the frozen official pair" >&2
    echo "         frozen: text=$FROZEN_TEXT data=$FROZEN_DATA" >&2
    echo "         actual: text=$COE_TEXT data=$COE_DATA" >&2
    echo "         historical board-proven pair: text=$HIST_TEXT data=$HIST_DATA" >&2
    echo "         use --coe-dir only with an explicitly audited replacement, or" >&2
    echo "         pass --skip-coe-check for a deliberately non-formal experiment" >&2
    exit 1
  fi
fi

# --- optional simulator identity smoke test ---------------------------------
if [[ "$VERIFY_SIM" -eq 1 ]]; then
  VERIFY_DIR="$JYD_DATA_ROOT/tmp/create-opt-worktree-verification"
  VERIFY_LOG="$VERIFY_DIR/$NAME-add.log"
  mkdir -p "$VERIFY_DIR"
  echo "== verifying worktree-local simulator identity"
  (
    cd "$WT_DIR"
    make -C am-kernels/tests/cpu-tests run ARCH=riscv32-jyd ALL=add
  ) 2>&1 | tee "$VERIFY_LOG"
  if ! grep -Fq "Git commit hash: $COMMIT_FULL" "$VERIFY_LOG"; then
    echo "error: simulator banner does not match worktree HEAD $COMMIT_FULL" >&2
    exit 1
  fi
  if ! grep -Fq "load image $WT_DIR/am-kernels/tests/cpu-tests/" "$VERIFY_LOG"; then
    echo "error: simulator did not load the worktree-local cpu-test image" >&2
    exit 1
  fi
  if [[ -z "$(find "$WT_DIR/npc/build/bin" -maxdepth 1 -type f -executable \
      -name 'JYDSoC-jyd-*' -print -quit 2>/dev/null)" ]]; then
    echo "error: worktree-local simulator was not built under $WT_DIR/npc/build/bin" >&2
    exit 1
  fi
  echo "   verified simulator commit: $COMMIT_FULL"
  echo "   verification log: $VERIFY_LOG"
fi

# --- summary -----------------------------------------------------------------
echo ""
echo "== worktree ready: $WT_DIR"
echo "   branch:  $BRANCH"
echo "   commit:  $COMMIT_FULL"
echo "   HEAD:    $(git -C "$WT_DIR" rev-parse --short HEAD)"
echo "   status:"
git -C "$WT_DIR" status --short | sed 's/^/     /' || true
echo ""
echo "Next: cd $WT_DIR"
echo "  make -C am-kernels/tests/cpu-tests run ARCH=riscv32-jyd ALL=add"
echo "  ./npc/scripts/run_digital_twin_vivado.sh impl --jobs 16 --ip-jobs 4"
