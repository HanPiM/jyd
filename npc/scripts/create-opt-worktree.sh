#!/usr/bin/env bash
#
# create-opt-worktree.sh - create and prepare an isolated optimization worktree.
#
# Automates the manual setup steps used for JYD timing/performance candidates:
#   * git worktree add under $JYD_DATA_ROOT/worktrees/
#   * link local ignored/untracked dependencies (am-kernels, npc/deps,
#     coremark build outputs, rt-thread-am)
#   * make ../riscv-arch-test-am-jyd resolve from the worktree's parent
#   * install proven prebuilt NEMU artifacts (.config + interpreter + interpreter-so)
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
#       [--coe-dir <dir>] [--skip-coe-check]

set -euo pipefail

usage() {
  cat <<'EOF'
create-opt-worktree.sh - create and prepare an isolated optimization worktree.

Automates the setup steps used for JYD optimization candidates:
  * git worktree add under $JYD_DATA_ROOT/worktrees/
  * link local ignored/untracked dependencies (am-kernels, npc/deps,
    coremark build outputs, rt-thread-am)
  * make ../riscv-arch-test-am-jyd resolve from the worktree's parent
  * install proven prebuilt NEMU artifacts (.config + interpreter + interpreter-so)
  * import the formal CoreMark COE pair into cur_coe

Usage:
  create-opt-worktree.sh [options]

Options:
  --commit <ref>       Commit to check out (default: HEAD of --src).
  --branch <name>      New branch name (default: opt-<short-commit>).
  --name <dir>         Worktree directory name under $JYD_DATA_ROOT/worktrees
                       (default: the branch name).
  --src <main-repo>    Main repository (default: auto-detected main worktree).
  --nemu-ref <dir>     Directory whose nemu/.config,
                       nemu/build/riscv32-nemu-interpreter, and
                       nemu/build/riscv32-nemu-interpreter-so are installed
                       (default: --src).  Use these artifacts unchanged when
                       NEMU source/config has not changed; do not rebuild NEMU
                       in a candidate worktree.
  --difftest-ref <dir> Backward-compatible alias for --nemu-ref.
  --coe-dir <dir>      Directory with coremark-official-riscv32-jyd.{text,data}.coe
                       (default: <src>/jyd-tests/coremark-official/build/
                       iter10000-data2000-z_zba_zbb_zbc_zbs-cdefault-lto0-pf1/).
  --skip-coe-check     Do not warn when COE hashes differ from the frozen pair.
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

while [[ $# -gt 0 ]]; do
  case "$1" in
    --commit) COMMIT="$2"; shift 2 ;;
    --branch) BRANCH="$2"; shift 2 ;;
    --name) NAME="$2"; shift 2 ;;
    --src) SRC="$2"; shift 2 ;;
    --nemu-ref|--difftest-ref) NEMU_REF="$2"; shift 2 ;;
    --coe-dir) COE_DIR="$2"; shift 2 ;;
    --skip-coe-check) SKIP_COE_CHECK=1; shift ;;
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

link_dep "$SRC/am-kernels" "$WT_DIR/am-kernels"
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
mkdir -p "$WT_DIR/nemu/build"
cp "$NEMU_REF/nemu/.config" "$WT_DIR/nemu/.config"
cp "$NEMU_REF/nemu/build/riscv32-nemu-interpreter" \
  "$WT_DIR/nemu/build/riscv32-nemu-interpreter"
cp "$NEMU_REF/nemu/build/riscv32-nemu-interpreter-so" \
  "$WT_DIR/nemu/build/riscv32-nemu-interpreter-so"
echo "   nemu/.config sha256: $(sha256sum "$WT_DIR/nemu/.config" | awk '{print $1}')"
echo "   interpreter sha256: $(sha256sum "$WT_DIR/nemu/build/riscv32-nemu-interpreter" | awk '{print $1}')"
echo "   interpreter-so sha256: $(sha256sum "$WT_DIR/nemu/build/riscv32-nemu-interpreter-so" | awk '{print $1}')"

# --- formal COE pair ---------------------------------------------------------
if [[ -z "$COE_DIR" ]]; then
  COE_DIR="$SRC/jyd-tests/coremark-official/build/iter10000-data2000-z_zba_zbb_zbc_zbs-cdefault-lto0-pf1"
fi
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
echo "   cur_coe/irom.coe sha256: $COE_TEXT"
echo "   cur_coe/dram.coe sha256: $COE_DATA"

if [[ "$SKIP_COE_CHECK" -eq 0 ]]; then
  FROZEN_TEXT="c93b3f8d57fa0cc4bba2481c321f55def46ff72f6e1f703bf87fa2e2576abe36"
  FROZEN_DATA="b4d242fa61985e8065a804c23f4a088b60bc249bccbb75031c8de1982a374165"
  HIST_TEXT="5067088dee8da04cc366d6334f5e8dda2cd97f13679ae8d377f146d6a3e008f9"
  HIST_DATA="07b2cff9328da907f4db3510ebfb1c441f509ceae6c8c70cf3075c94a41a8254"
  if [[ "$COE_TEXT" != "$FROZEN_TEXT" || "$COE_DATA" != "$FROZEN_DATA" ]]; then
    echo "warning: COE pair differs from the frozen official pair"
    echo "         frozen: text=$FROZEN_TEXT data=$FROZEN_DATA"
    echo "         actual: text=$COE_TEXT data=$COE_DATA"
    echo "         historical board-proven pair: text=$HIST_TEXT data=$HIST_DATA"
    echo "         (pass --skip-coe-check to silence this warning)"
  fi
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
echo "  ./npc/scripts/run_digital_twin_vivado.sh impl --jobs 16 --ip-jobs 1"
