#!/usr/bin/env bash
#
# create-opt-worktree.sh - create and prepare an isolated optimization worktree.
#
# Automates the manual setup steps used for JYD timing/performance candidates:
#   * git worktree add under $JYD_DATA_ROOT/worktrees/
#   * link local ignored/untracked dependencies (am-kernels sources, npc/deps,
#     AM SoftFloat sources, coremark build outputs, rt-thread-am)
#   * install a verified pinned RT-Thread Nano checkout without network access
#   * make ../riscv-arch-test-am-jyd resolve from the worktree's parent
#   * install a proven prebuilt NEMU dependency tree
#   * seed matching Vivado IP output products and completed OOC runs
#   * import the formal CoreMark COE pair into cur_coe
#
# Conventions (AGENTS.md):
#   * $JYD_DATA_ROOT defaults to /srv/data/jyd; never use /tmp for JYD data.
#   * Large shared directories are symlinked, not copied.
#   * No destructive commands; an existing target worktree requires --resume.
#
# Usage:
#   npc/scripts/create-opt-worktree.sh [--commit <ref>] [--branch <name>]
#       [--name <dir>] [--src <main-repo>] [--nemu-ref <dir>]
#       [--coe-dir <dir>] [--skip-coe-check] [--verify-sim] [--resume]
#       [--adopt-incomplete]

set -euo pipefail

usage() {
  cat <<'EOF'
create-opt-worktree.sh - create and prepare an isolated optimization worktree.

Automates the setup steps used for JYD optimization candidates:
  * git worktree add under $JYD_DATA_ROOT/worktrees/
  * link local ignored/untracked dependencies (am-kernels, npc/deps,
    AM SoftFloat sources, coremark build outputs, rt-thread-am)
  * install a verified pinned RT-Thread Nano checkout without clone/fetch
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
                       (default: the frozen b92/d972 formal build under
                       $JYD_DATA_ROOT/archive/).
  --skip-coe-check     Allow COE hashes to differ from the frozen formal pair.
  --verify-sim         Build and run the riscv32-jyd add smoke test, then verify
                       its simulator banner and image path belong to this
                       worktree.
  --resume             Resume an interrupted setup. Requires explicit --name,
                       --branch, and --commit matching the registered worktree;
                       the saved input marker must also match exactly. An
                       existing worktree is never removed on resume failure.
  --adopt-incomplete   With --resume, bind a legacy unmarked partial worktree to
                       the explicitly supplied inputs after strict validation.
  -h|--help            Show this help.
EOF
}

die() {
  echo "error: $*" >&2
  exit 1
}

# Worktree setup must never follow a symlink supplied as the destination or as
# one of its parent directories. A renamed/misdirected target would otherwise
# make --resume validate one path and mutate another.
reject_symlink_components() { # reject_symlink_components <absolute-path>
  local candidate="$1" component current="/" remainder
  [[ "$candidate" == /* ]] || die "internal path is not absolute: $candidate"
  remainder="${candidate#/}"
  while [[ -n "$remainder" ]]; do
    if [[ "$remainder" == */* ]]; then
      component="${remainder%%/*}"
      remainder="${remainder#*/}"
    else
      component="$remainder"
      remainder=""
    fi
    [[ -z "$component" ]] && continue
    current="${current%/}/$component"
    if [[ -L "$current" ]]; then
      die "destination path component is a symlink: $current"
    fi
  done
}

canonical_dir() { # canonical_dir <existing-directory>
  local directory="$1"
  [[ -d "$directory" ]] || die "directory does not exist: $directory"
  (cd "$directory" && pwd -P)
}

require_real_directory() { # require_real_directory <directory>
  local directory="$1"
  reject_symlink_components "$directory"
  [[ -d "$directory" && ! -L "$directory" ]] || \
    die "expected a real directory: $directory"
}

ensure_real_directory() { # ensure_real_directory <directory>
  local directory="$1" parent
  if [[ -e "$directory" || -L "$directory" ]]; then
    require_real_directory "$directory"
    return
  fi
  parent="$(dirname "$directory")"
  reject_symlink_components "$parent"
  mkdir -p -- "$directory"
  require_real_directory "$directory"
}

require_real_file() { # require_real_file <file>
  local file="$1"
  reject_symlink_components "$file"
  [[ -f "$file" && ! -L "$file" ]] || die "expected a real regular file: $file"
}

require_single_link_file() { # require_single_link_file <file>
  local file="$1"
  require_real_file "$file"
  [[ "$(stat -c '%h' -- "$file")" == 1 ]] || \
    die "expected an unlinked regular file: $file"
}

safe_copy_new() { # safe_copy_new <source> <destination> [reflink]
  local source="$1" destination="$2" reflink="${3:-}" parent source_mode destination_mode
  if [[ -d "$source" && ! -L "$source" ]]; then
    require_real_directory "$source"
  elif [[ -f "$source" && ! -L "$source" ]]; then
    require_real_file "$source"
  elif [[ -L "$source" ]]; then
    require_real_directory "$(dirname "$source")"
    [[ -n "$(readlink "$source")" ]] || die "source symlink has an empty target: $source"
  else
    die "copy source is not a supported file, symlink, or real directory: $source"
  fi
  source_mode="$(stat -c '%a' -- "$source")"
  parent="$(dirname "$destination")"
  ensure_real_directory "$parent"
  [[ ! -e "$destination" && ! -L "$destination" ]] || \
    die "refusing to overwrite destination: $destination"
  if [[ "$reflink" == "reflink" ]]; then
    cp -a --reflink=auto -- "$source" "$destination"
  else
    cp -a -- "$source" "$destination"
  fi
  require_real_directory "$parent"
  if [[ -d "$source" && ! -L "$source" ]]; then
    require_real_directory "$destination"
  elif [[ -f "$source" && ! -L "$source" ]]; then
    require_real_file "$destination"
  elif [[ ! -L "$destination" ]]; then
    die "copied symlink changed type: $destination"
  fi
  destination_mode="$(stat -c '%a' -- "$destination")"
  [[ "$source_mode" == "$destination_mode" ]] || \
    die "copied output mode differs from source: $destination ($destination_mode != $source_mode)"
}

safe_move_new() { # safe_move_new <source> <destination>
  local source="$1" destination="$2" parent
  if [[ -d "$source" && ! -L "$source" ]]; then
    require_real_directory "$source"
  elif [[ -f "$source" && ! -L "$source" ]]; then
    require_real_file "$source"
  else
    die "move source is not a real file or directory: $source"
  fi
  parent="$(dirname "$destination")"
  ensure_real_directory "$parent"
  [[ ! -e "$destination" && ! -L "$destination" ]] || \
    die "refusing to replace destination: $destination"
  mv -T -- "$source" "$destination"
  require_real_directory "$parent"
  if [[ -d "$destination" && ! -L "$destination" ]]; then
    require_real_directory "$destination"
  else
    require_real_file "$destination"
  fi
}

safe_link_new() { # safe_link_new <source> <destination>
  local source="$1" destination="$2" parent
  parent="$(dirname "$destination")"
  ensure_real_directory "$parent"
  [[ ! -e "$destination" && ! -L "$destination" ]] || \
    die "refusing to replace dependency link: $destination"
  ln -s -- "$source" "$destination"
  require_real_directory "$parent"
}

safe_touch_regular() { # safe_touch_regular <file> [reference-file]
  local file="$1" reference="${2:-}"
  require_real_directory "$(dirname "$file")"
  require_real_file "$file"
  [[ "$(stat -c '%h' -- "$file")" == 1 ]] || \
    die "refusing to retimestamp hard-linked output: $file"
  if [[ -n "$reference" ]]; then
    require_real_file "$reference"
    touch -r "$reference" -- "$file"
  else
    touch -- "$file"
  fi
  require_real_file "$file"
}

safe_remove_regular() { # safe_remove_regular <file>
  local file="$1"
  require_real_directory "$(dirname "$file")"
  if [[ -e "$file" || -L "$file" ]]; then
    require_real_file "$file"
    rm -f -- "$file"
  fi
}

JYD_DATA_ROOT="${JYD_DATA_ROOT:-/srv/data/jyd}"
if [[ "$JYD_DATA_ROOT" != /* ]]; then
  JYD_DATA_ROOT="$(pwd -P)/$JYD_DATA_ROOT"
fi
reject_symlink_components "$JYD_DATA_ROOT"
ensure_real_directory "$JYD_DATA_ROOT"
JYD_DATA_ROOT="$(canonical_dir "$JYD_DATA_ROOT")"
WT_BASE="$JYD_DATA_ROOT/worktrees"
reject_symlink_components "$WT_BASE"
ensure_real_directory "$WT_BASE"

SRC=""
COMMIT=""
BRANCH=""
NAME=""
NEMU_REF=""
COE_DIR=""
SKIP_COE_CHECK=0
VERIFY_SIM=0
RESUME=0
ADOPT_INCOMPLETE=0

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
    --resume) RESUME=1; shift ;;
    --adopt-incomplete) ADOPT_INCOMPLETE=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown option: $1" >&2; usage; exit 1 ;;
  esac
done

if [[ "$RESUME" -eq 1 && ( -z "$COMMIT" || -z "$BRANCH" || -z "$NAME" ) ]]; then
  echo "error: --resume requires explicit --commit, --branch, and --name" >&2
  exit 1
fi
if [[ "$ADOPT_INCOMPLETE" -eq 1 && "$RESUME" -ne 1 ]]; then
  die "--adopt-incomplete requires --resume"
fi

# --- locate the main repository ---------------------------------------------
if [[ -z "$SRC" ]]; then
  SRC="$(git worktree list --porcelain | awk 'NR == 1 && $1 == "worktree" {print $2}')"
fi
[[ -n "$SRC" ]] || die "cannot locate the main repository (--src)"
SRC="$(canonical_dir "$SRC")"
git -C "$SRC" rev-parse --is-inside-work-tree >/dev/null 2>&1 || \
  die "--src is not a Git worktree: $SRC"
SRC_COMMON_GITDIR="$(git -C "$SRC" rev-parse --path-format=absolute --git-common-dir)"
reject_symlink_components "$SRC_COMMON_GITDIR"
require_real_directory "$SRC_COMMON_GITDIR"
SRC_COMMON_GITDIR="$(canonical_dir "$SRC_COMMON_GITDIR")"
NEMU_REF="${NEMU_REF:-$SRC}"
NEMU_REF="$(canonical_dir "$NEMU_REF")"
git -C "$NEMU_REF" rev-parse --is-inside-work-tree >/dev/null 2>&1 || \
  die "--nemu-ref is not a Git worktree: $NEMU_REF"
NEMU_REF_COMMON_GITDIR="$(git -C "$NEMU_REF" rev-parse --path-format=absolute --git-common-dir)"
reject_symlink_components "$NEMU_REF_COMMON_GITDIR"
require_real_directory "$NEMU_REF_COMMON_GITDIR"
NEMU_REF_COMMON_GITDIR="$(canonical_dir "$NEMU_REF_COMMON_GITDIR")"
if [[ "$NEMU_REF_COMMON_GITDIR" != "$SRC_COMMON_GITDIR" ]]; then
  die "--nemu-ref must be a worktree of the same repository as --src"
fi
NEMU_REF_COMMIT="$(git -C "$NEMU_REF" rev-parse HEAD)"

COMMIT="${COMMIT:-$(git -C "$SRC" rev-parse HEAD)}"
COMMIT_FULL="$(git -C "$SRC" rev-parse "$COMMIT^{commit}")"
COMMIT_SHORT="$(git -C "$SRC" rev-parse --short "$COMMIT_FULL")"
BRANCH="${BRANCH:-opt-$COMMIT_SHORT}"
NAME="${NAME:-$BRANCH}"
if [[ ! "$NAME" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]]; then
  echo "error: --name must be one make-safe directory component under $WT_BASE: $NAME" >&2
  exit 1
fi

WT_DIR="$WT_BASE/$NAME"
reject_symlink_components "$WT_DIR"
CREATED_WORKTREE=0
WORKTREE_GIT_DIR=""
SETUP_MARKER=""
TEMP_DIRS=()

cleanup() {
  local status="$1"
  local temporary
  trap - EXIT
  for temporary in "${TEMP_DIRS[@]}"; do
    case "$temporary" in
      "$JYD_DATA_ROOT"/tmp/create-opt-*)
        if [[ -L "$temporary" ]]; then
          echo "warning: refused to remove symlink substituted for temporary directory: $temporary" >&2
        elif [[ -d "$temporary" ]]; then
          reject_symlink_components "$temporary"
          rm -rf -- "$temporary"
        elif [[ -e "$temporary" ]]; then
          echo "warning: refused to remove non-directory temporary path: $temporary" >&2
        fi
        ;;
      "$WT_DIR"/jyd-vivado-proj/digital_twin.srcs/sources_1/imports/.cur_coe.create-opt-stage.*)
        if [[ -L "$temporary" ]]; then
          echo "warning: refused to remove symlink substituted for COE stage: $temporary" >&2
        elif [[ -d "$temporary" ]]; then
          reject_symlink_components "$temporary"
          rm -rf -- "$temporary"
        elif [[ -e "$temporary" ]]; then
          echo "warning: refused to remove non-directory COE stage: $temporary" >&2
        fi
        ;;
      *) echo "warning: refused to remove unexpected temporary path: $temporary" >&2 ;;
    esac
  done
  if [[ "$status" -ne 0 && "$CREATED_WORKTREE" -eq 1 ]]; then
    echo "error: setup failed, removing incomplete worktree $WT_DIR" >&2
    git -C "$SRC" worktree remove --force "$WT_DIR" 2>/dev/null || true
    git -C "$SRC" branch -D "$BRANCH" 2>/dev/null || true
  elif [[ "$status" -ne 0 && "$RESUME" -eq 1 ]]; then
    echo "error: resumed setup failed; existing worktree retained: $WT_DIR" >&2
  fi
  exit "$status"
}
trap 'cleanup $?' EXIT

# --- create or validate the worktree ----------------------------------------
if [[ "$RESUME" -eq 1 ]]; then
  if [[ ! -d "$WT_DIR" ]]; then
    echo "error: --resume worktree directory does not exist: $WT_DIR" >&2
    exit 1
  fi
  if ! git -C "$SRC" worktree list --porcelain | \
      awk -v path="$WT_DIR" '$1 == "worktree" && $2 == path { found = 1 } END { exit !found }'; then
    echo "error: --resume target is not a registered worktree of $SRC: $WT_DIR" >&2
    exit 1
  fi
  ACTUAL_BRANCH="$(git -C "$WT_DIR" symbolic-ref -q HEAD || true)"
  if [[ "$ACTUAL_BRANCH" != "refs/heads/$BRANCH" ]]; then
    echo "error: --resume branch mismatch: expected refs/heads/$BRANCH, found ${ACTUAL_BRANCH:-detached HEAD}" >&2
    exit 1
  fi
  ACTUAL_COMMIT="$(git -C "$WT_DIR" rev-parse HEAD)"
  if [[ "$ACTUAL_COMMIT" != "$COMMIT_FULL" ]]; then
    echo "error: --resume commit mismatch: expected $COMMIT_FULL, found $ACTUAL_COMMIT" >&2
    exit 1
  fi
  echo "== resuming worktree: $WT_DIR (branch $BRANCH, commit $COMMIT_FULL)"
else
  if [[ -e "$WT_DIR" || -L "$WT_DIR" ]]; then
    echo "error: worktree directory already exists: $WT_DIR" >&2
    exit 1
  fi
  echo "== creating worktree: $WT_DIR (branch $BRANCH, commit $COMMIT_FULL)"
  git -C "$SRC" worktree add -b "$BRANCH" "$WT_DIR" "$COMMIT_FULL"
  CREATED_WORKTREE=1
fi

reject_symlink_components "$WT_DIR"
WORKTREE_COMMON_GITDIR="$(git -C "$WT_DIR" rev-parse --path-format=absolute --git-common-dir)"
reject_symlink_components "$WORKTREE_COMMON_GITDIR"
require_real_directory "$WORKTREE_COMMON_GITDIR"
WORKTREE_COMMON_GITDIR="$(canonical_dir "$WORKTREE_COMMON_GITDIR")"
if [[ "$WORKTREE_COMMON_GITDIR" != "$SRC_COMMON_GITDIR" ]]; then
  die "target worktree common Git directory differs from --src"
fi
WORKTREE_GIT_DIR="$(git -C "$WT_DIR" rev-parse --path-format=absolute --git-dir)"
reject_symlink_components "$WORKTREE_GIT_DIR"
require_real_directory "$WORKTREE_GIT_DIR"
WORKTREE_GIT_DIR="$(canonical_dir "$WORKTREE_GIT_DIR")"
require_real_directory "$WORKTREE_GIT_DIR"
command -v flock >/dev/null 2>&1 || die "flock is required for serialized worktree setup"
SETUP_LOCK="$WORKTREE_GIT_DIR/create-opt-worktree.lock"
if [[ -L "$SETUP_LOCK" ]]; then
  die "setup lock path is a symlink: $SETUP_LOCK"
elif [[ ! -e "$SETUP_LOCK" ]]; then
  if ! (umask 077; set -o noclobber; : >"$SETUP_LOCK") 2>/dev/null; then
    [[ -e "$SETUP_LOCK" && ! -L "$SETUP_LOCK" ]] || \
      die "cannot create setup lock safely: $SETUP_LOCK"
  fi
fi
require_real_file "$SETUP_LOCK"
[[ "$(stat -c '%a' -- "$SETUP_LOCK")" == 600 ]] || \
  die "setup lock must have mode 600: $SETUP_LOCK"
[[ "$(stat -c '%h' -- "$SETUP_LOCK")" == 1 ]] || \
  die "setup lock must not be hard-linked: $SETUP_LOCK"
SETUP_LOCK_PATH_ID="$(stat -Lc '%d:%i' -- "$SETUP_LOCK")"
exec {SETUP_LOCK_FD}<>"$SETUP_LOCK"
require_real_file "$SETUP_LOCK"
SETUP_LOCK_FD_ID="$(stat -Lc '%d:%i' -- "/proc/$$/fd/$SETUP_LOCK_FD")"
[[ "$SETUP_LOCK_PATH_ID" == "$SETUP_LOCK_FD_ID" ]] || \
  die "setup lock path changed while it was opened: $SETUP_LOCK"
if ! flock -n "$SETUP_LOCK_FD"; then
  die "another create-opt-worktree setup owns this worktree lock: $SETUP_LOCK"
fi
SETUP_MARKER="$WORKTREE_GIT_DIR/create-opt-worktree.incomplete"

# --- fail-closed input preflight --------------------------------------------
# These checks intentionally run before any ignored dependency, NEMU, Vivado,
# or COE output is installed in the candidate. The prebuilt binaries are valid
# only for the exact tracked source trees and generated inputs checked here.
NEMU_TRACKED_PATHS=(nemu sdb cachesim branchsim)

reject_untracked_nemu_sources() { # reject_untracked_nemu_sources <repo>
  local repo="$1" untracked_sources
  if ! untracked_sources="$(git -C "$repo" ls-files --others --exclude-standard -- "${NEMU_TRACKED_PATHS[@]}")"; then
    die "cannot enumerate untracked prebuilt-NEMU sources: $repo"
  fi
  if [[ -n "$untracked_sources" ]]; then
    echo "error: untracked prebuilt-NEMU sources can affect the installed binaries: $repo" >&2
    printf '%s\n' "$untracked_sources" | sed 's/^/         /' >&2
    exit 1
  fi
}

for checked_repo in "$WT_DIR" "$NEMU_REF"; do
  if ! git -C "$checked_repo" diff --quiet -- "${NEMU_TRACKED_PATHS[@]}"; then
    echo "error: tracked prebuilt-NEMU sources have unstaged changes: $checked_repo" >&2
    git -C "$checked_repo" diff --name-only -- "${NEMU_TRACKED_PATHS[@]}" | sed 's/^/         /' >&2
    exit 1
  fi
  if ! git -C "$checked_repo" diff --cached --quiet -- "${NEMU_TRACKED_PATHS[@]}"; then
    echo "error: tracked prebuilt-NEMU sources have staged changes: $checked_repo" >&2
    git -C "$checked_repo" diff --cached --name-only -- "${NEMU_TRACKED_PATHS[@]}" | sed 's/^/         /' >&2
    exit 1
  fi
  reject_untracked_nemu_sources "$checked_repo"
done

TRACKED_TREE_STATE=()
for tracked_dir in "${NEMU_TRACKED_PATHS[@]}"; do
  candidate_tree="$(git -C "$WT_DIR" rev-parse "HEAD:$tracked_dir")" || \
    die "candidate commit has no tracked $tracked_dir tree"
  reference_tree="$(git -C "$NEMU_REF" rev-parse "HEAD:$tracked_dir")" || \
    die "--nemu-ref commit has no tracked $tracked_dir tree"
  if [[ "$candidate_tree" != "$reference_tree" ]]; then
    echo "error: --nemu-ref tracked $tracked_dir tree does not match the candidate" >&2
    echo "         candidate $COMMIT_FULL: $candidate_tree" >&2
    echo "         reference $NEMU_REF_COMMIT: $reference_tree" >&2
    echo "         refusing to copy or retimestamp prebuilt NEMU/SDB artifacts" >&2
    exit 1
  fi
  TRACKED_TREE_STATE+=("$tracked_dir=$candidate_tree")
done

if [[ ! -f "$NEMU_REF/nemu/.config" ]]; then
  die "--nemu-ref has no nemu/.config: $NEMU_REF"
fi
if [[ ! -x "$NEMU_REF/nemu/build/riscv32-nemu-interpreter" ]]; then
  die "--nemu-ref has no built nemu/build/riscv32-nemu-interpreter: $NEMU_REF"
fi
if [[ ! -x "$NEMU_REF/nemu/build/riscv32-nemu-interpreter-so" ]]; then
  die "--nemu-ref has no built nemu/build/riscv32-nemu-interpreter-so: $NEMU_REF"
fi
NEMU_REQUIRED_INPUTS=(
  nemu/include/generated/autoconf.h
  nemu/include/config/auto.conf
  nemu/include/config/auto.conf.cmd
  nemu/tools/fixdep/build/fixdep
  nemu/tools/gen-inst/build/out.cc
  nemu/tools/softfloat/repo/source/include/softfloat.h
  nemu/tools/softfloat/repo/build/Linux-x86_64-GCC/softfloat.a
  sdb/build/libsdb.a
)
for required in "${NEMU_REQUIRED_INPUTS[@]}"; do
  require_real_file "$NEMU_REF/$required"
done
for required_executable in \
  nemu/build/riscv32-nemu-interpreter \
  nemu/build/riscv32-nemu-interpreter-so \
  nemu/tools/fixdep/build/fixdep; do
  require_real_file "$NEMU_REF/$required_executable"
  [[ -x "$NEMU_REF/$required_executable" ]] || \
    die "--nemu-ref prebuilt dependency is not executable: $required_executable"
done

# Validate the reference before staging it. A later destination timestamp check
# cannot identify a stale source binary because setup intentionally normalizes
# destination mtimes.
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

ensure_real_directory "$JYD_DATA_ROOT/tmp"
PREFLIGHT_STAGE="$(mktemp -d "$JYD_DATA_ROOT/tmp/create-opt-inputs.XXXXXX")"
require_real_directory "$PREFLIGHT_STAGE"
TEMP_DIRS+=("$PREFLIGHT_STAGE")

FIND_LIST_SEQUENCE=0
LAST_FIND_LIST=""
checked_find_list() { # checked_find_list <plain|sorted> <find-arguments...>
  local order="$1"
  shift
  FIND_LIST_SEQUENCE=$((FIND_LIST_SEQUENCE + 1))
  LAST_FIND_LIST="$PREFLIGHT_STAGE/find-list.$FIND_LIST_SEQUENCE"
  [[ ! -e "$LAST_FIND_LIST" && ! -L "$LAST_FIND_LIST" ]] || \
    die "internal find-list collision: $LAST_FIND_LIST"
  if [[ "$order" == "sorted" ]]; then
    if ! find "$@" -print0 | sort -z >"$LAST_FIND_LIST"; then
      die "failed to generate sorted file list"
    fi
  elif [[ "$order" == "plain" ]]; then
    if ! find "$@" -print0 >"$LAST_FIND_LIST"; then
      die "failed to generate file list"
    fi
  else
    die "internal invalid find-list order: $order"
  fi
  require_real_file "$LAST_FIND_LIST"
}

write_manifest_from_list() { # write_manifest_from_list <root> <output> <nul-list>
  local root="$1" output="$2" file_list="$3" manifest_file file_type file_mode file_hash
  require_real_directory "$root"
  require_real_file "$file_list"
  ensure_real_directory "$(dirname "$output")"
  [[ ! -e "$output" && ! -L "$output" ]] || die "manifest output already exists: $output"
  : >"$output"
  require_real_file "$output"
  while IFS= read -r -d '' manifest_file; do
    [[ "$manifest_file" != *$'\n'* ]] || die "manifest path contains a newline: $manifest_file"
    case "$manifest_file" in
      /*|../*|*/../*) die "manifest path escapes its root: $manifest_file" ;;
    esac
    require_real_directory "$(dirname "$root/$manifest_file")"
    file_mode="$(stat -c '%a' -- "$root/$manifest_file")"
    if [[ -L "$root/$manifest_file" ]]; then
      file_type=L
      file_hash="$(printf '%s' "$(readlink "$root/$manifest_file")" | sha256sum | awk '{print $1}')"
    elif [[ -f "$root/$manifest_file" ]]; then
      file_type=F
      file_hash="$(sha256sum "$root/$manifest_file" | awk '{print $1}')"
    else
      die "manifest input changed type during enumeration: $root/$manifest_file"
    fi
    printf '%s %s %s  %s\n' "$file_type" "$file_mode" "$file_hash" "$manifest_file" >>"$output"
  done <"$file_list"
  require_real_file "$output"
}

write_content_manifest() { # write_content_manifest <root> <output> <paths...>
  local root="$1" output="$2" file_list
  shift 2
  require_real_directory "$root"
  ensure_real_directory "$(dirname "$output")"
  [[ ! -e "$output" && ! -L "$output" ]] || die "manifest output already exists: $output"
  file_list="$output.files"
  [[ ! -e "$file_list" && ! -L "$file_list" ]] || die "manifest list already exists: $file_list"
  if ! (cd "$root" && find "$@" \( -type f -o -type l \) -print0 | sort -z >"$file_list"); then
    die "failed to enumerate manifest inputs under $root"
  fi
  require_real_file "$file_list"
  write_manifest_from_list "$root" "$output" "$file_list"
}

NEMU_INPUT_MANIFEST="$PREFLIGHT_STAGE/nemu-inputs.sha256"
write_content_manifest "$NEMU_REF" "$NEMU_INPUT_MANIFEST" \
  nemu/.config \
  nemu/include/generated \
  nemu/include/config \
  nemu/tools/fixdep/build \
  nemu/tools/gen-inst/build \
  nemu/tools/softfloat/repo/source/include \
  nemu/tools/softfloat/repo/build/Linux-x86_64-GCC/softfloat.a \
  nemu/build/riscv32-nemu-interpreter \
  nemu/build/riscv32-nemu-interpreter-so \
  sdb/build/libsdb.a
NEMU_INPUT_MANIFEST_SHA256="$(sha256sum "$NEMU_INPUT_MANIFEST" | awk '{print $1}')"
NEMU_CONFIG_SHA256="$(sha256sum "$NEMU_REF/nemu/.config" | awk '{print $1}')"
NEMU_INTERPRETER_SHA256="$(sha256sum "$NEMU_REF/nemu/build/riscv32-nemu-interpreter" | awk '{print $1}')"
NEMU_INTERPRETER_SO_SHA256="$(sha256sum "$NEMU_REF/nemu/build/riscv32-nemu-interpreter-so" | awk '{print $1}')"

# Stage the complete prebuilt payload before writing the resume marker. The
# stage is the immutable source for this invocation; no later copy reads live
# NEMU_REF contents.
NEMU_STAGE="$PREFLIGHT_STAGE/prebuilt-root"
for required_directory in \
  nemu/include/generated \
  nemu/include/config \
  nemu/tools/fixdep/build \
  nemu/tools/gen-inst/build \
  nemu/tools/softfloat/repo/source/include \
  nemu/build \
  sdb/build; do
  require_real_directory "$NEMU_REF/$required_directory"
done
ensure_real_directory "$NEMU_STAGE"
ensure_real_directory "$NEMU_STAGE/nemu"
ensure_real_directory "$NEMU_STAGE/nemu/include"
ensure_real_directory "$NEMU_STAGE/nemu/tools"
ensure_real_directory "$NEMU_STAGE/nemu/tools/fixdep"
ensure_real_directory "$NEMU_STAGE/nemu/tools/gen-inst"
ensure_real_directory "$NEMU_STAGE/nemu/tools/softfloat"
ensure_real_directory "$NEMU_STAGE/nemu/tools/softfloat/repo"
ensure_real_directory "$NEMU_STAGE/nemu/tools/softfloat/repo/source"
ensure_real_directory "$NEMU_STAGE/nemu/tools/softfloat/repo/build"
ensure_real_directory "$NEMU_STAGE/nemu/tools/softfloat/repo/build/Linux-x86_64-GCC"
ensure_real_directory "$NEMU_STAGE/sdb"
ensure_real_directory "$NEMU_STAGE/sdb/build"
safe_copy_new "$NEMU_REF/nemu/.config" "$NEMU_STAGE/nemu/.config"
safe_copy_new "$NEMU_REF/nemu/include/generated" "$NEMU_STAGE/nemu/include/generated" reflink
safe_copy_new "$NEMU_REF/nemu/include/config" "$NEMU_STAGE/nemu/include/config" reflink
safe_copy_new "$NEMU_REF/nemu/tools/fixdep/build" "$NEMU_STAGE/nemu/tools/fixdep/build" reflink
safe_copy_new "$NEMU_REF/nemu/tools/gen-inst/build" "$NEMU_STAGE/nemu/tools/gen-inst/build" reflink
safe_copy_new "$NEMU_REF/nemu/build" "$NEMU_STAGE/nemu/build" reflink
safe_copy_new "$NEMU_REF/nemu/tools/softfloat/repo/source/include" \
  "$NEMU_STAGE/nemu/tools/softfloat/repo/source/include" reflink
safe_copy_new "$NEMU_REF/nemu/tools/softfloat/repo/build/Linux-x86_64-GCC/softfloat.a" \
  "$NEMU_STAGE/nemu/tools/softfloat/repo/build/Linux-x86_64-GCC/softfloat.a" reflink
safe_copy_new "$NEMU_REF/sdb/build/libsdb.a" "$NEMU_STAGE/sdb/build/libsdb.a" reflink

STAGED_NEMU_INPUT_MANIFEST="$PREFLIGHT_STAGE/staged-nemu-inputs.sha256"
write_content_manifest "$NEMU_STAGE" "$STAGED_NEMU_INPUT_MANIFEST" \
  nemu/.config \
  nemu/include/generated \
  nemu/include/config \
  nemu/tools/fixdep/build \
  nemu/tools/gen-inst/build \
  nemu/tools/softfloat/repo/source/include \
  nemu/tools/softfloat/repo/build/Linux-x86_64-GCC/softfloat.a \
  nemu/build/riscv32-nemu-interpreter \
  nemu/build/riscv32-nemu-interpreter-so \
  sdb/build/libsdb.a
if ! cmp -s "$NEMU_INPUT_MANIFEST" "$STAGED_NEMU_INPUT_MANIFEST"; then
  echo "error: --nemu-ref generated inputs changed while they were staged" >&2
  diff -u "$NEMU_INPUT_MANIFEST" "$STAGED_NEMU_INPUT_MANIFEST" >&2 || true
  exit 1
fi

# Sources outside nemu/ use absolute object paths. Duplicate the reference
# subtrees at the candidate paths, then rewrite fixdep files literally.
for obj_dir in "$NEMU_STAGE"/nemu/build/obj-*; do
  [[ -d "$obj_dir" ]] || continue
  embedded_ref="$obj_dir/${NEMU_REF#/}"
  embedded_wt="$obj_dir/${WT_DIR#/}"
  if [[ -d "$embedded_ref" ]]; then
    ensure_real_directory "$(dirname "$embedded_wt")"
    safe_copy_new "$embedded_ref" "$embedded_wt" reflink
  fi
done

escape_sed_bre() { # escape_sed_bre <literal>
  local input="$1" output="" character index
  for ((index = 0; index < ${#input}; index++)); do
    character="${input:index:1}"
    case "$character" in
      '\'|'.'|'['|']'|'^'|'$'|'*'|'|') output+="\\$character" ;;
      *) output+="$character" ;;
    esac
  done
  printf '%s' "$output"
}

escape_sed_replacement() { # escape_sed_replacement <literal>
  local input="$1" output="" character index
  for ((index = 0; index < ${#input}; index++)); do
    character="${input:index:1}"
    case "$character" in
      '\'|'&'|'|') output+="\\$character" ;;
      *) output+="$character" ;;
    esac
  done
  printf '%s' "$output"
}

NEMU_REF_SED="$(escape_sed_bre "$NEMU_REF")"
WT_DIR_SED="$(escape_sed_replacement "$WT_DIR")"
checked_find_list sorted "$NEMU_STAGE/nemu/build" -type f -name '*.d'
NEMU_DEP_LIST="$LAST_FIND_LIST"
while IFS= read -r -d '' dependency_file; do
  require_real_file "$dependency_file"
  require_real_directory "$(dirname "$dependency_file")"
  sed -i "s|$NEMU_REF_SED|$WT_DIR_SED|g" "$dependency_file"
  require_real_file "$dependency_file"
done <"$NEMU_DEP_LIST"

NEMU_PAYLOAD_PATHS=(
  nemu/.config
  nemu/include/generated
  nemu/include/config
  nemu/tools/fixdep/build
  nemu/tools/gen-inst/build
  nemu/tools/softfloat/repo/source/include
  nemu/tools/softfloat/repo/build/Linux-x86_64-GCC/softfloat.a
  nemu/build
  sdb/build/libsdb.a
)
NEMU_PAYLOAD_MANIFEST="$PREFLIGHT_STAGE/nemu-payload.sha256"
write_content_manifest "$NEMU_STAGE" "$NEMU_PAYLOAD_MANIFEST" "${NEMU_PAYLOAD_PATHS[@]}"
NEMU_PAYLOAD_SHA256="$(sha256sum "$NEMU_PAYLOAD_MANIFEST" | awk '{print $1}')"

# Copy the COE source into a worktree-local-input staging area first. All hash
# and workload-manifest decisions below use only the staged bytes, closing the
# source-check/source-copy race.
if [[ -z "$COE_DIR" ]]; then
  COE_DIR="$JYD_DATA_ROOT/archive/coremark-final-iter10000-d972f52-20260815T215500Z/build"
fi
COE_DIR="$(canonical_dir "$COE_DIR")"
CUR_COE_PARENT="$WT_DIR/jyd-vivado-proj/digital_twin.srcs/sources_1/imports"
ensure_real_directory "$CUR_COE_PARENT"
COE_STAGE="$(mktemp -d "$CUR_COE_PARENT/.cur_coe.create-opt-stage.XXXXXX")"
require_real_directory "$COE_STAGE"
TEMP_DIRS+=("$COE_STAGE")
for coe_source in \
  coremark-official-riscv32-jyd.text.coe \
  coremark-official-riscv32-jyd.data.coe; do
  require_real_file "$COE_DIR/$coe_source"
done
safe_copy_new "$COE_DIR/coremark-official-riscv32-jyd.text.coe" "$COE_STAGE/irom.coe"
safe_copy_new "$COE_DIR/coremark-official-riscv32-jyd.data.coe" "$COE_STAGE/dram.coe"
require_single_link_file "$COE_STAGE/irom.coe"
require_single_link_file "$COE_STAGE/dram.coe"

COE_SOURCE_MANIFEST=""
for manifest_candidate in \
  "$COE_DIR/coremark-workload.env" \
  "$(dirname "$COE_DIR")/formal-coe/coremark-workload.env"; do
  if [[ -e "$manifest_candidate" || -L "$manifest_candidate" ]]; then
    require_real_file "$manifest_candidate"
    COE_SOURCE_MANIFEST="$(cd "$(dirname "$manifest_candidate")" && pwd -P)/$(basename "$manifest_candidate")"
    safe_copy_new "$COE_SOURCE_MANIFEST" "$COE_STAGE/source-workload.env"
    require_single_link_file "$COE_STAGE/source-workload.env"
    break
  fi
done
COE_ITERATIONS=""
COE_TOTAL_DATA_SIZE=2000
if [[ -n "$COE_SOURCE_MANIFEST" ]]; then
  COE_SOURCE_MANIFEST_SHA256="$(sha256sum "$COE_STAGE/source-workload.env" | awk '{print $1}')"
  COE_ITERATIONS="$(sed -n 's/^COREMARK_ITERATIONS=//p' "$COE_STAGE/source-workload.env")"
  manifest_data_size="$(sed -n 's/^COREMARK_TOTAL_DATA_SIZE=//p' "$COE_STAGE/source-workload.env")"
  [[ -n "$manifest_data_size" ]] && COE_TOTAL_DATA_SIZE="$manifest_data_size"
else
  COE_SOURCE_MANIFEST_SHA256="none"
  case "/$COE_DIR/" in
    */iter10000-*) COE_ITERATIONS=10000 ;;
  esac
fi
if [[ "$COE_ITERATIONS" != 10000 ]]; then
  echo "error: COE input is not identified as ITERATIONS=10000: $COE_DIR" >&2
  echo "       provide coremark-workload.env or an iter10000-* build directory" >&2
  exit 1
fi
[[ "$COE_TOTAL_DATA_SIZE" =~ ^[0-9]+$ ]] || \
  die "invalid COREMARK_TOTAL_DATA_SIZE in ${COE_SOURCE_MANIFEST:-$COE_DIR}: $COE_TOTAL_DATA_SIZE"
COE_TEXT="$(sha256sum "$COE_STAGE/irom.coe" | awk '{print $1}')"
COE_DATA="$(sha256sum "$COE_STAGE/dram.coe" | awk '{print $1}')"
if [[ -n "$COE_SOURCE_MANIFEST" ]]; then
  manifest_text="$(sed -n 's/^COREMARK_IROM_SHA256=//p' "$COE_STAGE/source-workload.env")"
  manifest_data="$(sed -n 's/^COREMARK_DRAM_SHA256=//p' "$COE_STAGE/source-workload.env")"
  if [[ -z "$manifest_text" || -z "$manifest_data" || \
        "$COE_TEXT" != "$manifest_text" || "$COE_DATA" != "$manifest_data" ]]; then
    die "staged COE files do not match source workload manifest: $COE_SOURCE_MANIFEST"
  fi
fi

FROZEN_TEXT="3f72fd43a5598b2fec42a0b9da6fe86c1cb3417b0ff3c991cd1679549d38bbfd"
FROZEN_DATA="bf9d64534794fdef70b455c4076bb5d311068704095cd29450482002efee5638"
HIST_FORMAL_TEXT="3867cfc979c4e452b5f77be6ce568ea36cc2014bef6fe8fb68011ad0a451bf2b"
HIST_FORMAL_DATA="83fecbb32572a559fd3d9f09b8835cafb5ef70e5bd36aaf0cbd0289488fe56f4"
HIST_BOARD_TEXT="5067088dee8da04cc366d6334f5e8dda2cd97f13679ae8d377f146d6a3e008f9"
HIST_BOARD_DATA="07b2cff9328da907f4db3510ebfb1c441f509ceae6c8c70cf3075c94a41a8254"
if [[ "$SKIP_COE_CHECK" -eq 0 && \
      ( "$COE_TEXT" != "$FROZEN_TEXT" || "$COE_DATA" != "$FROZEN_DATA" ) ]]; then
  echo "error: staged COE pair differs from the frozen official b92/d972 pair" >&2
  echo "         frozen: text=$FROZEN_TEXT data=$FROZEN_DATA" >&2
  echo "         actual: text=$COE_TEXT data=$COE_DATA" >&2
  echo "         historical prior formal: text=$HIST_FORMAL_TEXT data=$HIST_FORMAL_DATA" >&2
  echo "         historical board-proven: text=$HIST_BOARD_TEXT data=$HIST_BOARD_DATA" >&2
  echo "         pass --skip-coe-check only for a deliberately non-formal experiment" >&2
  exit 1
fi
cat >"$COE_STAGE/coremark-workload.env" <<EOF
COREMARK_ITERATIONS=10000
COREMARK_TOTAL_DATA_SIZE=$COE_TOTAL_DATA_SIZE
COREMARK_SOURCE_DIR=$COE_DIR
COREMARK_IROM_SHA256=$COE_TEXT
COREMARK_DRAM_SHA256=$COE_DATA
EOF
require_single_link_file "$COE_STAGE/coremark-workload.env"
safe_remove_regular "$COE_STAGE/source-workload.env"
COE_DIRECTORY_MODE="$(stat -c '%a' -- "$CUR_COE_PARENT")"
require_real_directory "$COE_STAGE"
chmod "$COE_DIRECTORY_MODE" "$COE_STAGE"
require_real_directory "$COE_STAGE"
[[ "$(stat -c '%a' -- "$COE_STAGE")" == "$COE_DIRECTORY_MODE" ]] || \
  die "cannot normalize staged cur_coe directory mode"
COE_PAYLOAD_MANIFEST="$PREFLIGHT_STAGE/coe-payload.sha256"
write_content_manifest "$COE_STAGE" "$COE_PAYLOAD_MANIFEST" .
COE_PAYLOAD_SHA256="$(sha256sum "$COE_PAYLOAD_MANIFEST" | awk '{print $1}')"

# Locate a pinned, tracked-clean Abstract Machine SoftFloat checkout in strict
# target/SRC/NEMU_REF order. An existing target is an installed output and must
# validate in place; it is never silently replaced by a fallback.
AM_SOFTFLOAT_MAKEFILE="$WT_DIR/abstract-machine/softfloat/Makefile"
AM_SOFTFLOAT_DEST="$WT_DIR/abstract-machine/softfloat/repo"
[[ -f "$AM_SOFTFLOAT_MAKEFILE" ]] || \
  die "candidate has no Abstract Machine SoftFloat Makefile: $AM_SOFTFLOAT_MAKEFILE"
AM_SOFTFLOAT_COMMIT="$(awk '$1 == "REPO_COMMIT" && $2 == ":=" {print $3; exit}' "$AM_SOFTFLOAT_MAKEFILE")"
[[ -n "$AM_SOFTFLOAT_COMMIT" ]] || \
  die "cannot determine pinned AM SoftFloat commit from $AM_SOFTFLOAT_MAKEFILE"

valid_am_softfloat_repo() { # valid_am_softfloat_repo <repo> <commit>
  local repo="$1" commit="$2" head resolved
  resolved="$(readlink -f -- "$repo" 2>/dev/null || true)"
  [[ -n "$resolved" && -d "$resolved" && ! -L "$resolved" ]] || return 1
  [[ -f "$resolved/.am-commit-$commit" && ! -L "$resolved/.am-commit-$commit" ]] || return 1
  git -C "$resolved" rev-parse --is-inside-work-tree >/dev/null 2>&1 || return 1
  head="$(git -C "$resolved" rev-parse HEAD 2>/dev/null)" || return 1
  [[ "$head" == "$commit" ]] || return 1
  git -C "$resolved" diff --quiet -- || return 1
  git -C "$resolved" diff --cached --quiet -- || return 1
}

AM_SOFTFLOAT_SOURCE=""
AM_SOFTFLOAT_FALLBACKS=()
for softfloat_candidate in \
  "$SRC/abstract-machine/softfloat/repo" \
  "$NEMU_REF/abstract-machine/softfloat/repo"; do
  if valid_am_softfloat_repo "$softfloat_candidate" "$AM_SOFTFLOAT_COMMIT"; then
    softfloat_identity="$(canonical_dir "$softfloat_candidate")"
    if [[ " ${AM_SOFTFLOAT_FALLBACKS[*]} " != *" $softfloat_identity "* ]]; then
      AM_SOFTFLOAT_FALLBACKS+=("$softfloat_identity")
    fi
  fi
done
ensure_real_directory "$(dirname "$AM_SOFTFLOAT_DEST")"
if [[ -e "$AM_SOFTFLOAT_DEST" || -L "$AM_SOFTFLOAT_DEST" ]]; then
  if [[ -L "$AM_SOFTFLOAT_DEST" ]]; then
    AM_SOFTFLOAT_SOURCE="$(readlink -f -- "$AM_SOFTFLOAT_DEST" 2>/dev/null || true)"
    [[ -n "$AM_SOFTFLOAT_SOURCE" ]] || \
      die "existing target AM SoftFloat symlink is broken: $AM_SOFTFLOAT_DEST"
    softfloat_identity_allowed=0
    for softfloat_identity in "${AM_SOFTFLOAT_FALLBACKS[@]}"; do
      [[ "$AM_SOFTFLOAT_SOURCE" == "$softfloat_identity" ]] && softfloat_identity_allowed=1
    done
    [[ "$softfloat_identity_allowed" -eq 1 ]] || \
      die "existing target AM SoftFloat symlink has an unapproved identity: $AM_SOFTFLOAT_DEST -> $AM_SOFTFLOAT_SOURCE"
  elif [[ -d "$AM_SOFTFLOAT_DEST" ]]; then
    require_real_directory "$AM_SOFTFLOAT_DEST"
    AM_SOFTFLOAT_SOURCE="$(canonical_dir "$AM_SOFTFLOAT_DEST")"
  else
    die "existing target AM SoftFloat output is neither a real directory nor an approved symlink: $AM_SOFTFLOAT_DEST"
  fi
  valid_am_softfloat_repo "$AM_SOFTFLOAT_SOURCE" "$AM_SOFTFLOAT_COMMIT" || \
    die "existing target AM SoftFloat checkout is not pinned and tracked-clean: $AM_SOFTFLOAT_DEST"
else
  [[ "${#AM_SOFTFLOAT_FALLBACKS[@]}" -gt 0 ]] && AM_SOFTFLOAT_SOURCE="${AM_SOFTFLOAT_FALLBACKS[0]}"
  [[ -n "$AM_SOFTFLOAT_SOURCE" ]] || \
    die "no pinned, tracked-clean AM SoftFloat checkout found in target, SRC, or NEMU_REF"
fi
AM_SOFTFLOAT_STAMP_SHA256="$(sha256sum "$AM_SOFTFLOAT_SOURCE/.am-commit-$AM_SOFTFLOAT_COMMIT" | awk '{print $1}')"

# riscv-arch-test-am-jyd is referenced as ../riscv-arch-test-am-jyd from every
# optimization worktree. Bind the shared link identity before writing a resume
# marker; a broken, wrong, or non-symlink path is never silently retained.
ARCH_TEST_LINK="$WT_BASE/riscv-arch-test-am-jyd"
if [[ -d "$JYD_DATA_ROOT/riscv-arch-test-am-jyd" && ! -L "$JYD_DATA_ROOT/riscv-arch-test-am-jyd" ]]; then
  ARCH_TEST_SOURCE="$(canonical_dir "$JYD_DATA_ROOT/riscv-arch-test-am-jyd")"
else
  ARCH_TEST_SOURCE="$(canonical_dir "/home/hanpi/gitclone/riscv-arch-test-am-jyd")"
fi
git -C "$ARCH_TEST_SOURCE" rev-parse --is-inside-work-tree >/dev/null 2>&1 || \
  die "riscv-arch-test-am-jyd source is not a Git worktree: $ARCH_TEST_SOURCE"
ARCH_TEST_COMMIT="$(git -C "$ARCH_TEST_SOURCE" rev-parse HEAD)"
ensure_real_directory "$(dirname "$ARCH_TEST_LINK")"
validate_arch_test_link() {
  local link_identity
  if [[ -L "$ARCH_TEST_LINK" ]]; then
    link_identity="$(readlink -f -- "$ARCH_TEST_LINK" 2>/dev/null || true)"
    [[ -n "$link_identity" ]] || die "existing riscv-arch-test-am-jyd link is broken: $ARCH_TEST_LINK"
    [[ "$link_identity" == "$ARCH_TEST_SOURCE" ]] || \
      die "existing riscv-arch-test-am-jyd link has wrong identity: $ARCH_TEST_LINK -> $link_identity"
  elif [[ -e "$ARCH_TEST_LINK" ]]; then
    die "existing riscv-arch-test-am-jyd path is not a symlink: $ARCH_TEST_LINK"
  fi
}
validate_arch_test_link

# Reuse the pinned RT-Thread Nano checkout without allowing its Makefile to
# clone or fetch. Its expected working tree is the exact SOURCE_COMMIT archive
# plus this candidate's ordered patch series; Git metadata and historical pin
# stamps are excluded from the content comparison.
RT_NANO_ROOT="$WT_DIR/jyd-tests/rtthread-nano"
RT_NANO_DEST="$RT_NANO_ROOT/upstream"
RT_NANO_ENABLED=0
RT_NANO_COMMIT=none
RT_NANO_PAYLOAD_SHA256=none
RT_NANO_STAGE=""
if [[ -f "$RT_NANO_ROOT/Makefile" || -f "$RT_NANO_ROOT/SOURCE_COMMIT" ]]; then
  require_real_directory "$RT_NANO_ROOT"
  require_real_file "$RT_NANO_ROOT/Makefile"
  require_real_file "$RT_NANO_ROOT/SOURCE_COMMIT"
  RT_NANO_COMMIT="$(tr -d '[:space:]' <"$RT_NANO_ROOT/SOURCE_COMMIT")"
  [[ "$RT_NANO_COMMIT" =~ ^[0-9a-f]{40}$ ]] || \
    die "invalid RT-Thread Nano SOURCE_COMMIT: $RT_NANO_COMMIT"
  require_real_directory "$RT_NANO_ROOT/patches"
  checked_find_list sorted "$RT_NANO_ROOT/patches" -maxdepth 1 -type f -name '*.patch'
  RT_NANO_PATCH_LIST="$LAST_FIND_LIST"
  RT_NANO_PATCHES=()
  while IFS= read -r -d '' rt_patch; do
    require_real_file "$rt_patch"
    RT_NANO_PATCHES+=("$rt_patch")
  done <"$RT_NANO_PATCH_LIST"

  RT_NANO_SOURCE=""
  if [[ -e "$RT_NANO_DEST" || -L "$RT_NANO_DEST" ]]; then
    [[ ! -L "$RT_NANO_DEST" ]] || \
      die "existing RT-Thread Nano checkout is a symlink: $RT_NANO_DEST"
    require_real_directory "$RT_NANO_DEST"
    RT_NANO_SOURCE="$RT_NANO_DEST"
  else
    for rt_candidate in \
      "$SRC/jyd-tests/rtthread-nano/upstream" \
      "$NEMU_REF/jyd-tests/rtthread-nano/upstream"; do
      if [[ -d "$rt_candidate" && ! -L "$rt_candidate" ]] && \
          [[ "$(git -C "$rt_candidate" rev-parse HEAD 2>/dev/null || true)" == "$RT_NANO_COMMIT" ]]; then
        RT_NANO_SOURCE="$(canonical_dir "$rt_candidate")"
        break
      fi
    done
    [[ -n "$RT_NANO_SOURCE" ]] || \
      die "no local RT-Thread Nano checkout at pinned commit $RT_NANO_COMMIT; refusing network fetch"
  fi
  require_real_directory "$RT_NANO_SOURCE/.git"
  [[ "$(git -C "$RT_NANO_SOURCE" rev-parse HEAD)" == "$RT_NANO_COMMIT" ]] || \
    die "RT-Thread Nano checkout is not at pinned commit $RT_NANO_COMMIT: $RT_NANO_SOURCE"
  git -C "$RT_NANO_SOURCE" diff --cached --quiet -- || \
    die "RT-Thread Nano checkout has staged changes: $RT_NANO_SOURCE"
  require_real_file "$RT_NANO_SOURCE/.pinned-$RT_NANO_COMMIT"

  RT_NANO_EXPECTED="$PREFLIGHT_STAGE/rtthread-nano-expected"
  ensure_real_directory "$RT_NANO_EXPECTED"
  RT_NANO_ARCHIVE="$PREFLIGHT_STAGE/rtthread-nano.tar"
  [[ ! -e "$RT_NANO_ARCHIVE" && ! -L "$RT_NANO_ARCHIVE" ]] || \
    die "internal RT-Thread Nano archive already exists: $RT_NANO_ARCHIVE"
  if git -C "$RT_NANO_SOURCE" ls-tree -r "$RT_NANO_COMMIT" | \
      awk '$1 == "120000" { exit 1 }'; then
    :
  else
    die "pinned RT-Thread Nano commit contains symlinks; refusing unsafe archive extraction"
  fi
  git -C "$RT_NANO_SOURCE" archive --format=tar -o "$RT_NANO_ARCHIVE" "$RT_NANO_COMMIT"
  require_real_file "$RT_NANO_ARCHIVE"
  tar -xf "$RT_NANO_ARCHIVE" -C "$RT_NANO_EXPECTED"
  require_real_directory "$RT_NANO_EXPECTED"
  checked_find_list plain "$RT_NANO_EXPECTED" -type l
  RT_NANO_EXPECTED_LINK_LIST="$LAST_FIND_LIST"
  if IFS= read -r -d '' unexpected_rt_link <"$RT_NANO_EXPECTED_LINK_LIST"; then
    die "pinned RT-Thread Nano archive contains a symlink: $unexpected_rt_link"
  fi
  if [[ "${#RT_NANO_PATCHES[@]}" -gt 0 ]]; then
    (cd "$RT_NANO_EXPECTED" && git apply "${RT_NANO_PATCHES[@]}")
  fi
  checked_find_list plain "$RT_NANO_EXPECTED" -type l
  RT_NANO_PATCHED_LINK_LIST="$LAST_FIND_LIST"
  if IFS= read -r -d '' unexpected_rt_link <"$RT_NANO_PATCHED_LINK_LIST"; then
    die "patched RT-Thread Nano tree contains a symlink: $unexpected_rt_link"
  fi

  RT_NANO_EXPECTED_LIST="$PREFLIGHT_STAGE/rtthread-nano-expected.list"
  if ! (cd "$RT_NANO_EXPECTED" && find . \( -type f -o -type l \) -print0 | sort -z >"$RT_NANO_EXPECTED_LIST"); then
    die "failed to enumerate expected RT-Thread Nano checkout"
  fi
  require_real_file "$RT_NANO_EXPECTED_LIST"
  RT_NANO_EXPECTED_MANIFEST="$PREFLIGHT_STAGE/rtthread-nano-expected.manifest"
  write_manifest_from_list "$RT_NANO_EXPECTED" "$RT_NANO_EXPECTED_MANIFEST" "$RT_NANO_EXPECTED_LIST"

  verify_rt_nano_checkout() { # verify_rt_nano_checkout <checkout> <label>
    local checkout="$1" label="$2" checkout_list checkout_manifest
    require_real_directory "$checkout"
    require_real_directory "$checkout/.git"
    require_real_file "$checkout/.pinned-$RT_NANO_COMMIT"
    [[ "$(git -C "$checkout" rev-parse HEAD)" == "$RT_NANO_COMMIT" ]] || \
      die "$label RT-Thread Nano checkout changed commit: $checkout"
    git -C "$checkout" diff --cached --quiet -- || \
      die "$label RT-Thread Nano checkout has staged changes: $checkout"
    FIND_LIST_SEQUENCE=$((FIND_LIST_SEQUENCE + 1))
    checkout_list="$PREFLIGHT_STAGE/rtthread-nano-$FIND_LIST_SEQUENCE.list"
    if ! (cd "$checkout" && find . \
        \( -path './.git' -o -name '.pinned-*' \) -prune -o \
        \( -type f -o -type l \) -print0 | sort -z >"$checkout_list"); then
      die "failed to enumerate $label RT-Thread Nano checkout: $checkout"
    fi
    require_real_file "$checkout_list"
    checkout_manifest="$checkout_list.manifest"
    write_manifest_from_list "$checkout" "$checkout_manifest" "$checkout_list"
    if ! cmp -s "$RT_NANO_EXPECTED_MANIFEST" "$checkout_manifest"; then
      echo "error: $label RT-Thread Nano checkout does not equal SOURCE_COMMIT plus candidate patches" >&2
      diff -u "$RT_NANO_EXPECTED_MANIFEST" "$checkout_manifest" >&2 || true
      exit 1
    fi
  }
  verify_rt_nano_checkout "$RT_NANO_SOURCE" source

  RT_NANO_STAGE="$PREFLIGHT_STAGE/rtthread-nano-upstream"
  safe_copy_new "$RT_NANO_SOURCE" "$RT_NANO_STAGE" reflink
  verify_rt_nano_checkout "$RT_NANO_STAGE" staged
  RT_NANO_PAYLOAD_SHA256="$(sha256sum "$RT_NANO_EXPECTED_MANIFEST" | awk '{print $1}')"
  RT_NANO_ENABLED=1
fi

# Bind the tracked XCI identities as resume inputs. The actual ignored Vivado
# products are compared file-for-file before any missing product is copied.
write_xci_manifest() { # write_xci_manifest <ip-directory> <output>
  local ip_directory="$1" output="$2" xci_file relative_file xci_list
  require_real_directory "$ip_directory"
  ensure_real_directory "$(dirname "$output")"
  [[ ! -e "$output" && ! -L "$output" ]] || die "XCI manifest output already exists: $output"
  checked_find_list sorted "$ip_directory" -type f -name '*.xci'
  xci_list="$LAST_FIND_LIST"
  : >"$output"
  require_real_file "$output"
  while IFS= read -r -d '' xci_file; do
    require_real_file "$xci_file"
    relative_file=".${xci_file#"$ip_directory"}"
    printf '%s  %s\n' "$(sed -e '$a\\' "$xci_file" | sha256sum | awk '{print $1}')" "$relative_file" >>"$output"
  done <"$xci_list"
  require_real_file "$output"
}

VIVADO_REF_IP="$SRC/jyd-vivado-proj/digital_twin.srcs/sources_1/ip"
VIVADO_WT_IP="$WT_DIR/jyd-vivado-proj/digital_twin.srcs/sources_1/ip"
REF_XCI_MANIFEST="$PREFLIGHT_STAGE/ref-xci.sha256"
WT_XCI_MANIFEST="$PREFLIGHT_STAGE/wt-xci.sha256"
VIVADO_XCI_COMPATIBLE=0
if [[ -d "$VIVADO_REF_IP" && -d "$VIVADO_WT_IP" ]]; then
  write_xci_manifest "$VIVADO_REF_IP" "$REF_XCI_MANIFEST"
  write_xci_manifest "$VIVADO_WT_IP" "$WT_XCI_MANIFEST"
  REF_XCI_SHA256="$(sha256sum "$REF_XCI_MANIFEST" | awk '{print $1}')"
  WT_XCI_SHA256="$(sha256sum "$WT_XCI_MANIFEST" | awk '{print $1}')"
  cmp -s "$REF_XCI_MANIFEST" "$WT_XCI_MANIFEST" && VIVADO_XCI_COMPATIBLE=1
else
  REF_XCI_SHA256=missing
  WT_XCI_SHA256=missing
fi

# Recheck mutable Git state after staging, before persisting its identity. This
# closes the practical race where another process edits or advances a source
# worktree between the initial make-q/tree checks and the payload copy.
[[ "$(git -C "$WT_DIR" rev-parse HEAD)" == "$COMMIT_FULL" ]] || \
  die "candidate HEAD changed during input preflight"
[[ "$(git -C "$NEMU_REF" rev-parse HEAD)" == "$NEMU_REF_COMMIT" ]] || \
  die "--nemu-ref HEAD changed during input preflight"
for checked_repo in "$WT_DIR" "$NEMU_REF"; do
  git -C "$checked_repo" diff --quiet -- "${NEMU_TRACKED_PATHS[@]}" || \
    die "tracked prebuilt-NEMU sources changed during input preflight: $checked_repo"
  git -C "$checked_repo" diff --cached --quiet -- "${NEMU_TRACKED_PATHS[@]}" || \
    die "staged prebuilt-NEMU sources changed during input preflight: $checked_repo"
  reject_untracked_nemu_sources "$checked_repo"
done
for tracked_dir in "${NEMU_TRACKED_PATHS[@]}"; do
  candidate_tree="$(git -C "$WT_DIR" rev-parse "HEAD:$tracked_dir")"
  reference_tree="$(git -C "$NEMU_REF" rev-parse "HEAD:$tracked_dir")"
  [[ "$candidate_tree" == "$reference_tree" ]] || \
    die "tracked $tracked_dir tree changed during input preflight"
done

for identity_value in "$SRC" "$SRC_COMMON_GITDIR" "$WT_DIR" "$BRANCH" \
  "$NEMU_REF" "$COE_DIR" "$COE_SOURCE_MANIFEST" "$AM_SOFTFLOAT_SOURCE" \
  "$ARCH_TEST_SOURCE" "$ARCH_TEST_LINK"; do
  [[ "$identity_value" != *$'\n'* ]] || die "input identity contains a newline"
done
INPUT_STATE="$PREFLIGHT_STAGE/input-state"
[[ ! -e "$INPUT_STATE" && ! -L "$INPUT_STATE" ]] || die "internal input-state output already exists"
{
  printf 'FORMAT=2\n'
  printf 'SRC=%s\n' "$SRC"
  printf 'COMMON_GITDIR=%s\n' "$SRC_COMMON_GITDIR"
  printf 'WORKTREE=%s\n' "$WT_DIR"
  printf 'BRANCH=%s\n' "$BRANCH"
  printf 'COMMIT=%s\n' "$COMMIT_FULL"
  printf 'NEMU_REF=%s\n' "$NEMU_REF"
  printf 'NEMU_REF_COMMIT=%s\n' "$NEMU_REF_COMMIT"
  for tree_state in "${TRACKED_TREE_STATE[@]}"; do
    printf 'TRACKED_TREE_%s\n' "$tree_state"
  done
  printf 'NEMU_CONFIG_SHA256=%s\n' "$NEMU_CONFIG_SHA256"
  printf 'NEMU_INPUT_MANIFEST_SHA256=%s\n' "$NEMU_INPUT_MANIFEST_SHA256"
  printf 'NEMU_PAYLOAD_SHA256=%s\n' "$NEMU_PAYLOAD_SHA256"
  printf 'NEMU_INTERPRETER_SHA256=%s\n' "$NEMU_INTERPRETER_SHA256"
  printf 'NEMU_INTERPRETER_SO_SHA256=%s\n' "$NEMU_INTERPRETER_SO_SHA256"
  printf 'COE_DIR=%s\n' "$COE_DIR"
  printf 'COE_SOURCE_MANIFEST=%s\n' "${COE_SOURCE_MANIFEST:-none}"
  printf 'COE_SOURCE_MANIFEST_SHA256=%s\n' "$COE_SOURCE_MANIFEST_SHA256"
  printf 'COE_PAYLOAD_SHA256=%s\n' "$COE_PAYLOAD_SHA256"
  printf 'COE_TEXT_SHA256=%s\n' "$COE_TEXT"
  printf 'COE_DATA_SHA256=%s\n' "$COE_DATA"
  printf 'COE_DIRECTORY_MODE=%s\n' "$COE_DIRECTORY_MODE"
  printf 'SKIP_COE_CHECK=%s\n' "$SKIP_COE_CHECK"
  printf 'VERIFY_SIM=%s\n' "$VERIFY_SIM"
  printf 'AM_SOFTFLOAT_SOURCE=%s\n' "$AM_SOFTFLOAT_SOURCE"
  printf 'AM_SOFTFLOAT_COMMIT=%s\n' "$AM_SOFTFLOAT_COMMIT"
  printf 'AM_SOFTFLOAT_STAMP_SHA256=%s\n' "$AM_SOFTFLOAT_STAMP_SHA256"
  printf 'ARCH_TEST_SOURCE=%s\n' "$ARCH_TEST_SOURCE"
  printf 'ARCH_TEST_COMMIT=%s\n' "$ARCH_TEST_COMMIT"
  printf 'ARCH_TEST_LINK=%s\n' "$ARCH_TEST_LINK"
  printf 'RT_NANO_ENABLED=%s\n' "$RT_NANO_ENABLED"
  printf 'RT_NANO_COMMIT=%s\n' "$RT_NANO_COMMIT"
  printf 'RT_NANO_PAYLOAD_SHA256=%s\n' "$RT_NANO_PAYLOAD_SHA256"
  printf 'REF_XCI_SHA256=%s\n' "$REF_XCI_SHA256"
  printf 'WT_XCI_SHA256=%s\n' "$WT_XCI_SHA256"
} >"$INPUT_STATE"
require_real_file "$INPUT_STATE"

if [[ "$RESUME" -eq 1 ]]; then
  if [[ -L "$SETUP_MARKER" ]]; then
    die "resume marker is a symlink: $SETUP_MARKER"
  elif [[ -f "$SETUP_MARKER" ]]; then
    require_real_file "$SETUP_MARKER"
    [[ "$(stat -c '%a' -- "$SETUP_MARKER")" == 600 ]] || \
      die "resume marker must have mode 600: $SETUP_MARKER"
    if ! cmp -s "$INPUT_STATE" "$SETUP_MARKER"; then
      echo "error: --resume inputs differ from the saved incomplete setup" >&2
      diff -u "$SETUP_MARKER" "$INPUT_STATE" >&2 || true
      exit 1
    fi
    echo "   verified incomplete setup input marker: $SETUP_MARKER"
  elif [[ "$ADOPT_INCOMPLETE" -eq 1 ]]; then
    marker_tmp="$SETUP_MARKER.tmp.$$"
    safe_copy_new "$INPUT_STATE" "$marker_tmp"
    require_real_file "$marker_tmp"
    chmod 600 "$marker_tmp"
    [[ "$(stat -c '%a' -- "$marker_tmp")" == 600 ]] || die "cannot secure resume marker temporary"
    safe_move_new "$marker_tmp" "$SETUP_MARKER"
    echo "   adopted legacy incomplete worktree inputs: $SETUP_MARKER"
  else
    echo "error: --resume target has no incomplete setup marker: $SETUP_MARKER" >&2
    echo "       use --adopt-incomplete once only for a legacy partial worktree" >&2
    exit 1
  fi
else
  if [[ -e "$SETUP_MARKER" || -L "$SETUP_MARKER" ]]; then
    die "new worktree unexpectedly already has a setup marker: $SETUP_MARKER"
  fi
  marker_tmp="$SETUP_MARKER.tmp.$$"
  safe_copy_new "$INPUT_STATE" "$marker_tmp"
  require_real_file "$marker_tmp"
  chmod 600 "$marker_tmp"
  [[ "$(stat -c '%a' -- "$marker_tmp")" == 600 ]] || die "cannot secure resume marker temporary"
  safe_move_new "$marker_tmp" "$SETUP_MARKER"
  echo "   recorded incomplete setup inputs: $SETUP_MARKER"
fi

copy_missing_or_verify() { # copy_missing_or_verify <source-file> <destination-file>
  local source_file="$1" destination_file="$2" source_mode destination_mode
  require_real_directory "$(dirname "$source_file")"
  ensure_real_directory "$(dirname "$destination_file")"
  if [[ -e "$destination_file" || -L "$destination_file" ]]; then
    if [[ -L "$source_file" || -L "$destination_file" ]]; then
      [[ -L "$source_file" && -L "$destination_file" && \
         "$(readlink "$source_file")" == "$(readlink "$destination_file")" ]] || \
        die "existing output differs from staged input: $destination_file"
    elif [[ -f "$source_file" && -f "$destination_file" ]]; then
      require_real_file "$source_file"
      require_real_file "$destination_file"
      cmp -s "$source_file" "$destination_file" || \
        die "existing output differs from staged input: $destination_file"
    else
      die "existing output type differs from staged input: $destination_file"
    fi
    source_mode="$(stat -c '%a' -- "$source_file")"
    destination_mode="$(stat -c '%a' -- "$destination_file")"
    [[ "$source_mode" == "$destination_mode" ]] || \
      die "existing output mode differs from staged input: $destination_file ($destination_mode != $source_mode)"
  else
    safe_copy_new "$source_file" "$destination_file" reflink
  fi
}

merge_missing_or_verify_tree() { # merge_missing_or_verify_tree <source-dir> <destination-dir>
  local source_dir="$1" destination_dir="$2" entry relative source_entry destination_entry
  local destination_list source_list source_mode destination_mode
  require_real_directory "$source_dir"
  ensure_real_directory "$(dirname "$destination_dir")"
  if [[ ! -e "$destination_dir" && ! -L "$destination_dir" ]]; then
    safe_copy_new "$source_dir" "$destination_dir" reflink
    return
  fi
  require_real_directory "$destination_dir"
  source_mode="$(stat -c '%a' -- "$source_dir")"
  destination_mode="$(stat -c '%a' -- "$destination_dir")"
  [[ "$source_mode" == "$destination_mode" ]] || \
    die "existing output directory mode differs from staged input: $destination_dir ($destination_mode != $source_mode)"

  checked_find_list plain "$destination_dir" -mindepth 1
  destination_list="$LAST_FIND_LIST"
  while IFS= read -r -d '' entry; do
    relative="${entry#"$destination_dir"/}"
    source_entry="$source_dir/$relative"
    if [[ ! -e "$source_entry" && ! -L "$source_entry" ]]; then
      die "existing output has no staged counterpart: $entry"
    fi
  done <"$destination_list"

  checked_find_list plain "$source_dir" -mindepth 1
  source_list="$LAST_FIND_LIST"
  while IFS= read -r -d '' entry; do
    relative="${entry#"$source_dir"/}"
    destination_entry="$destination_dir/$relative"
    if [[ -d "$entry" && ! -L "$entry" ]]; then
      if [[ -e "$destination_entry" || -L "$destination_entry" ]]; then
        require_real_directory "$destination_entry"
        source_mode="$(stat -c '%a' -- "$entry")"
        destination_mode="$(stat -c '%a' -- "$destination_entry")"
        [[ "$source_mode" == "$destination_mode" ]] || \
          die "existing output directory mode differs from staged input: $destination_entry ($destination_mode != $source_mode)"
      fi
    elif [[ -e "$destination_entry" || -L "$destination_entry" ]]; then
      copy_missing_or_verify "$entry" "$destination_entry"
    fi
  done <"$source_list"

  # Only after every existing entry has compared equal do we fill missing
  # entries. Thus a later mismatch can never leave an earlier partial update.
  while IFS= read -r -d '' entry; do
    relative="${entry#"$source_dir"/}"
    destination_entry="$destination_dir/$relative"
    if [[ ! -e "$destination_entry" && ! -L "$destination_entry" ]]; then
      if [[ -d "$entry" && ! -L "$entry" ]]; then
        ensure_real_directory "$(dirname "$destination_entry")"
        source_mode="$(stat -c '%a' -- "$entry")"
        mkdir -m "$source_mode" -- "$destination_entry"
        require_real_directory "$destination_entry"
      else
        copy_missing_or_verify "$entry" "$destination_entry"
      fi
    fi
  done <"$source_list"
}

# --- link local dependencies (symlink, never copy) --------------------------
link_dep() { # link_dep <src> <dst>
  local s="$1" d="$2" source_identity destination_identity
  if [[ -e "$s" ]]; then
    source_identity="$(readlink -f -- "$s" 2>/dev/null || true)"
    [[ -n "$source_identity" ]] || die "dependency source cannot be resolved: $s"
    ensure_real_directory "$(dirname "$d")"
    if [[ -L "$d" ]]; then
      destination_identity="$(readlink -f -- "$d" 2>/dev/null || true)"
      if [[ -z "$destination_identity" || "$source_identity" != "$destination_identity" ]]; then
        die "existing dependency symlink differs: $d -> $(readlink "$d")"
      fi
      echo "   retained dependency link $d -> $(readlink "$d")"
    elif [[ -e "$d" ]]; then
      die "refusing to replace non-symlink dependency path: $d"
    else
      safe_link_new "$source_identity" "$d"
      echo "   linked $s -> $d"
    fi
  else
    if [[ -e "$d" || -L "$d" ]]; then
      die "dependency source is missing but an unbound target exists: $d"
    fi
    echo "   skipped (source missing): $s"
  fi
}

# Keep the am-kernels directory hierarchy local to the worktree.  GNU make
# resolves `-C am-kernels/...` through a directory symlink, which makes CURDIR
# point into SRC and causes Abstract Machine to select SRC/npc as its simulator.
# A symlink farm shares the source files while preserving worktree-local build
# directories, generated Makefiles, and repository-relative AM/NPC paths.
if [[ -d "$SRC/am-kernels" ]]; then
  require_real_directory "$SRC/am-kernels"
  ensure_real_directory "$WT_DIR/am-kernels"
  checked_find_list plain "$SRC/am-kernels" \
    \( -name .git -o -name build -o -name out \) -prune -o \
    -type d
  AM_KERNELS_DIR_LIST="$LAST_FIND_LIST"
  while IFS= read -r -d '' source_dir; do
    relative_dir="${source_dir#"$SRC/am-kernels"/}"
    [[ "$source_dir" == "$SRC/am-kernels" ]] && relative_dir=""
    target_dir="$WT_DIR/am-kernels/$relative_dir"
    ensure_real_directory "$target_dir"
  done <"$AM_KERNELS_DIR_LIST"
  checked_find_list plain "$SRC/am-kernels" \
    \( -name .git -o -name build -o -name out \) -prune -o \
    \( -name .result -o -name 'Makefile.*' \) -prune -o \
    \( -type f -o -type l \)
  AM_KERNELS_FILE_LIST="$LAST_FIND_LIST"
  while IFS= read -r -d '' source_file; do
    relative_file="${source_file#"$SRC/am-kernels"/}"
    target_file="$WT_DIR/am-kernels/$relative_file"
    ensure_real_directory "$(dirname "$target_file")"
    source_identity="$(readlink -f -- "$source_file" 2>/dev/null || true)"
    [[ -n "$source_identity" ]] || die "am-kernels source cannot be resolved: $source_file"
    if [[ -L "$target_file" ]]; then
      if [[ "$source_identity" != "$(readlink -f -- "$target_file" 2>/dev/null || true)" ]]; then
        die "existing am-kernels source link differs: $target_file -> $(readlink "$target_file")"
      fi
    elif [[ -e "$target_file" ]]; then
      die "refusing to replace non-symlink am-kernels file: $target_file"
    else
      safe_link_new "$source_identity" "$target_file"
    fi
  done <"$AM_KERNELS_FILE_LIST"
  if [[ -L "$WT_DIR/am-kernels" || -L "$WT_DIR/am-kernels/tests/cpu-tests" ]]; then
    echo "error: am-kernels directory hierarchy must remain worktree-local" >&2
    exit 1
  fi
  echo "   linked am-kernels source files into $WT_DIR/am-kernels"
else
  echo "   skipped (source missing): $SRC/am-kernels"
fi
link_dep "$SRC/npc/deps" "$WT_DIR/npc/deps"

# Abstract Machine SoftFloat was validated during input preflight. Preserve a
# verified target-local clone, or link the first verified fallback selected in
# strict target/SRC/NEMU_REF order.
if [[ -e "$AM_SOFTFLOAT_DEST" || -L "$AM_SOFTFLOAT_DEST" ]]; then
  if [[ -L "$AM_SOFTFLOAT_DEST" ]]; then
    [[ "$(readlink -f -- "$AM_SOFTFLOAT_DEST" 2>/dev/null || true)" == "$AM_SOFTFLOAT_SOURCE" ]] || \
      die "AM SoftFloat link identity changed after preflight: $AM_SOFTFLOAT_DEST"
  else
    require_real_directory "$AM_SOFTFLOAT_DEST"
  fi
  valid_am_softfloat_repo "$AM_SOFTFLOAT_DEST" "$AM_SOFTFLOAT_COMMIT" || \
    die "AM SoftFloat checkout changed after preflight: $AM_SOFTFLOAT_DEST"
  echo "   retained verified target AM SoftFloat source: $AM_SOFTFLOAT_DEST"
else
  link_dep "$AM_SOFTFLOAT_SOURCE" "$AM_SOFTFLOAT_DEST"
fi

link_dep "$SRC/jyd-tests/coremark-official/build" \
  "$WT_DIR/jyd-tests/coremark-official/build"
link_dep "$SRC/rt-thread-am" "$WT_DIR/rt-thread-am"

# Install the already-validated RT-Thread Nano checkout before any workload can
# invoke its network-capable fetch rule.
if [[ "$RT_NANO_ENABLED" -eq 1 ]]; then
  if [[ -e "$RT_NANO_DEST" || -L "$RT_NANO_DEST" ]]; then
    [[ ! -L "$RT_NANO_DEST" ]] || die "RT-Thread Nano destination became a symlink: $RT_NANO_DEST"
    verify_rt_nano_checkout "$RT_NANO_DEST" installed
  else
    safe_copy_new "$RT_NANO_STAGE" "$RT_NANO_DEST" reflink
    verify_rt_nano_checkout "$RT_NANO_DEST" installed
  fi
  safe_touch_regular "$RT_NANO_DEST/.pinned-$RT_NANO_COMMIT"
  make -s -q -C "$RT_NANO_ROOT" fetch || \
    die "installed RT-Thread Nano checkout would still run its network fetch rule"
  echo "   verified/installed pinned RT-Thread Nano checkout: $RT_NANO_COMMIT"
fi

# The shared architecture-test identity was bound before the resume marker.
validate_arch_test_link
[[ "$(git -C "$ARCH_TEST_SOURCE" rev-parse HEAD)" == "$ARCH_TEST_COMMIT" ]] || \
  die "riscv-arch-test-am-jyd source changed after input preflight: $ARCH_TEST_SOURCE"
if [[ -L "$ARCH_TEST_LINK" ]]; then
  echo "   retained existing riscv-arch-test-am-jyd path: $ARCH_TEST_LINK"
else
  safe_link_new "$ARCH_TEST_SOURCE" "$ARCH_TEST_LINK"
  echo "   linked $ARCH_TEST_SOURCE -> $ARCH_TEST_LINK"
fi

# --- reusable Vivado IP/OOC state ------------------------------------------
# These ignored products are independent of candidate RTL, but only when the
# checked-out IP configurations match the source project. Vivado strips a
# terminal newline while generating output products, so normalize only that
# byte when comparing XCI manifests.
VIVADO_REF_PROJECT="$SRC/jyd-vivado-proj"
VIVADO_WT_PROJECT="$WT_DIR/jyd-vivado-proj"
if [[ "$VIVADO_XCI_COMPATIBLE" -eq 1 ]]; then
    echo "warning: TODO: reuse Vivado caches only while the source project is idle; source outputs are not snapshotted"
    require_real_directory "$VIVADO_REF_IP"
    require_real_directory "$VIVADO_WT_IP"
    # Validate all already-installed in-tree generated files first. Tracked XCI
    # files are deliberately excluded because their normalized manifests were
    # bound above.
    checked_find_list plain "$VIVADO_WT_IP" \( -type f -o -type l \) ! -name '*.xci'
    VIVADO_WT_FILE_LIST="$LAST_FIND_LIST"
    while IFS= read -r -d '' generated_file; do
      relative_file="${generated_file#"$VIVADO_WT_IP"/}"
      source_file="$VIVADO_REF_IP/$relative_file"
      if [[ ! -e "$source_file" && ! -L "$source_file" ]]; then
        die "existing Vivado IP output has no source counterpart: $generated_file"
      fi
      copy_missing_or_verify "$source_file" "$generated_file"
    done <"$VIVADO_WT_FILE_LIST"
    checked_find_list plain "$VIVADO_REF_IP" \( -type f -o -type l \) ! -name '*.xci'
    VIVADO_REF_FILE_LIST="$LAST_FIND_LIST"
    while IFS= read -r -d '' generated_file; do
      relative_file="${generated_file#"$VIVADO_REF_IP"/}"
      target_file="$VIVADO_WT_IP/$relative_file"
      if [[ -e "$target_file" || -L "$target_file" ]]; then
        copy_missing_or_verify "$generated_file" "$target_file"
      fi
    done <"$VIVADO_REF_FILE_LIST"

    # Vivado emits some synthesis products beside each XCI instead of under
    # digital_twin.gen. Fill only files proven missing after the validation
    # pass above; never replace the target commit's tracked IP configuration.
    checked_find_list plain "$VIVADO_REF_IP" -type d
    VIVADO_REF_DIR_LIST="$LAST_FIND_LIST"
    while IFS= read -r -d '' generated_dir; do
      relative_dir="${generated_dir#"$VIVADO_REF_IP"/}"
      [[ "$generated_dir" == "$VIVADO_REF_IP" ]] && relative_dir=""
      target_dir="$VIVADO_WT_IP/$relative_dir"
      ensure_real_directory "$target_dir"
    done <"$VIVADO_REF_DIR_LIST"
    while IFS= read -r -d '' generated_file; do
      relative_file="${generated_file#"$VIVADO_REF_IP"/}"
      copy_missing_or_verify "$generated_file" "$VIVADO_WT_IP/$relative_file"
    done <"$VIVADO_REF_FILE_LIST"
    echo "   verified/filled Vivado IP in-tree output products"

    for cache_dir in digital_twin.gen digital_twin.cache digital_twin.ip_user_files; do
      if [[ -d "$VIVADO_REF_PROJECT/$cache_dir" ]]; then
        require_real_directory "$VIVADO_REF_PROJECT/$cache_dir"
        merge_missing_or_verify_tree \
          "$VIVADO_REF_PROJECT/$cache_dir" "$VIVADO_WT_PROJECT/$cache_dir"
        echo "   verified/filled Vivado IP state: $cache_dir"
      elif [[ -e "$VIVADO_WT_PROJECT/$cache_dir" || -L "$VIVADO_WT_PROJECT/$cache_dir" ]]; then
        die "existing Vivado state has no source counterpart: $VIVADO_WT_PROJECT/$cache_dir"
      fi
    done
    if [[ -d "$VIVADO_REF_PROJECT/digital_twin.runs" ]]; then
      require_real_directory "$VIVADO_REF_PROJECT/digital_twin.runs"
      ensure_real_directory "$VIVADO_WT_PROJECT/digital_twin.runs"
      checked_find_list sorted "$VIVADO_REF_PROJECT/digital_twin.runs" \
        -mindepth 1 -maxdepth 1 -type d -name '*_synth_1'
      VIVADO_RUN_LIST="$LAST_FIND_LIST"
      while IFS= read -r -d '' run_dir; do
        checked_find_list plain "$run_dir" -maxdepth 1 -type f -name '*.dcp'
        VIVADO_DCP_LIST="$LAST_FIND_LIST"
        VIVADO_RUN_HAS_DCP=0
        IFS= read -r -d '' first_dcp <"$VIVADO_DCP_LIST" && VIVADO_RUN_HAS_DCP=1 || true
        if [[ -f "$run_dir/.vivado.end.rst" && ! -L "$run_dir/.vivado.end.rst" && \
              "$VIVADO_RUN_HAS_DCP" -eq 1 ]]; then
          require_real_file "$run_dir/.vivado.end.rst"
          merge_missing_or_verify_tree \
            "$run_dir" "$VIVADO_WT_PROJECT/digital_twin.runs/$(basename "$run_dir")"
          echo "   verified/filled completed Vivado OOC run: $(basename "$run_dir")"
        else
          echo "   skipped incomplete Vivado OOC run: $(basename "$run_dir")"
        fi
      done <"$VIVADO_RUN_LIST"
    fi
elif [[ -d "$VIVADO_REF_IP" && -d "$VIVADO_WT_IP" ]]; then
  echo "   skipped Vivado IP/OOC state: source and target XCI manifests differ"
else
  echo "   skipped Vivado IP/OOC state: IP source directory missing"
fi

# --- prebuilt NEMU artifacts -------------------------------------------------
# Candidate worktrees do not rebuild NEMU unless their NEMU source or
# configuration changes.  Copy the verified native executable as well as the
# difftest shared object so offline NEMU profiling and NPC difftest use known
# inputs without a dependency rebuild or network fetch.
copy_missing_or_verify "$NEMU_STAGE/nemu/.config" "$WT_DIR/nemu/.config"
merge_missing_or_verify_tree "$NEMU_STAGE/nemu/include/generated" "$WT_DIR/nemu/include/generated"
merge_missing_or_verify_tree "$NEMU_STAGE/nemu/include/config" "$WT_DIR/nemu/include/config"
merge_missing_or_verify_tree "$NEMU_STAGE/nemu/tools/fixdep/build" "$WT_DIR/nemu/tools/fixdep/build"
merge_missing_or_verify_tree "$NEMU_STAGE/nemu/tools/gen-inst/build" "$WT_DIR/nemu/tools/gen-inst/build"
merge_missing_or_verify_tree "$NEMU_STAGE/nemu/build" "$WT_DIR/nemu/build"
merge_missing_or_verify_tree \
  "$NEMU_STAGE/nemu/tools/softfloat/repo/source/include" \
  "$WT_DIR/nemu/tools/softfloat/repo/source/include"
copy_missing_or_verify \
  "$NEMU_STAGE/nemu/tools/softfloat/repo/build/Linux-x86_64-GCC/softfloat.a" \
  "$WT_DIR/nemu/tools/softfloat/repo/build/Linux-x86_64-GCC/softfloat.a"
copy_missing_or_verify "$NEMU_STAGE/sdb/build/libsdb.a" "$WT_DIR/sdb/build/libsdb.a"

# Verify the complete installed payload before touching anything. This catches
# both a changed existing file and a missing/extra ignored build product.
INSTALLED_NEMU_MANIFEST="$PREFLIGHT_STAGE/installed-nemu-payload.sha256"
write_content_manifest "$WT_DIR" "$INSTALLED_NEMU_MANIFEST" "${NEMU_PAYLOAD_PATHS[@]}"
if ! cmp -s "$NEMU_PAYLOAD_MANIFEST" "$INSTALLED_NEMU_MANIFEST"; then
  echo "error: installed NEMU payload does not match the validated stage" >&2
  diff -u "$NEMU_PAYLOAD_MANIFEST" "$INSTALLED_NEMU_MANIFEST" >&2 || true
  exit 1
fi
for installed_executable in \
  "$WT_DIR/nemu/build/riscv32-nemu-interpreter" \
  "$WT_DIR/nemu/build/riscv32-nemu-interpreter-so" \
  "$WT_DIR/nemu/tools/fixdep/build/fixdep"; do
  require_real_file "$installed_executable"
  [[ -x "$installed_executable" ]] || \
    die "installed prebuilt dependency is not executable: $installed_executable"
done

# Give every copied/generated NEMU input and output one explicit common mtime
# newer than the checked-out sources. A shared reference avoids the one-
# nanosecond inversion observed with consecutive UTIME_NOW calls.
NEMU_MTIME_REF="$WT_DIR/nemu/.config"
checked_find_list plain \
  "$WT_DIR/nemu/include/generated" "$WT_DIR/nemu/include/config" \
  "$WT_DIR/nemu/tools/fixdep/build" "$WT_DIR/nemu/tools/gen-inst/build" \
  "$WT_DIR/nemu/tools/softfloat/repo/source/include" \
  "$WT_DIR/nemu/build" -type l
NEMU_TOUCH_LINK_LIST="$LAST_FIND_LIST"
if IFS= read -r -d '' unexpected_nemu_link <"$NEMU_TOUCH_LINK_LIST"; then
  die "refusing to retimestamp NEMU payload containing a symlink: $unexpected_nemu_link"
fi
checked_find_list plain \
  "$WT_DIR/nemu/include/generated" "$WT_DIR/nemu/include/config" \
  "$WT_DIR/nemu/tools/fixdep/build" "$WT_DIR/nemu/tools/gen-inst/build" \
  "$WT_DIR/nemu/tools/softfloat/repo/source/include" \
  "$WT_DIR/nemu/build" -type f
NEMU_TOUCH_FILE_LIST="$LAST_FIND_LIST"
safe_touch_regular "$NEMU_MTIME_REF"
while IFS= read -r -d '' nemu_touch_file; do
  safe_touch_regular "$nemu_touch_file" "$NEMU_MTIME_REF"
done <"$NEMU_TOUCH_FILE_LIST"
safe_touch_regular \
  "$WT_DIR/nemu/tools/softfloat/repo/build/Linux-x86_64-GCC/softfloat.a" "$NEMU_MTIME_REF"
safe_touch_regular "$WT_DIR/sdb/build/libsdb.a" "$NEMU_MTIME_REF"

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
[[ "$(sha256sum "$WT_DIR/nemu/.config" | awk '{print $1}')" == "$NEMU_CONFIG_SHA256" ]] || \
  die "installed nemu/.config hash changed after validation"
[[ "$(sha256sum "$WT_DIR/nemu/build/riscv32-nemu-interpreter" | awk '{print $1}')" == "$NEMU_INTERPRETER_SHA256" ]] || \
  die "installed NEMU interpreter hash changed after validation"
[[ "$(sha256sum "$WT_DIR/nemu/build/riscv32-nemu-interpreter-so" | awk '{print $1}')" == "$NEMU_INTERPRETER_SO_SHA256" ]] || \
  die "installed NEMU interpreter-so hash changed after validation"
echo "   nemu/.config sha256: $NEMU_CONFIG_SHA256"
echo "   interpreter sha256: $NEMU_INTERPRETER_SHA256"
echo "   interpreter-so sha256: $NEMU_INTERPRETER_SO_SHA256"

# --- formal COE pair ---------------------------------------------------------
CUR_COE="$WT_DIR/jyd-vivado-proj/digital_twin.srcs/sources_1/imports/cur_coe"
reject_symlink_components "$CUR_COE"
if [[ ! -e "$CUR_COE" && ! -L "$CUR_COE" ]]; then
  # COE_STAGE is a sibling of CUR_COE, so this publishes the complete,
  # already-validated pair and manifest with one same-filesystem rename.
  safe_move_new "$COE_STAGE" "$CUR_COE"
else
  require_real_directory "$CUR_COE"
  [[ "$(stat -c '%a' -- "$CUR_COE")" == "$COE_DIRECTORY_MODE" ]] || \
    die "existing cur_coe directory mode differs from staged payload: $CUR_COE"

  # First reject extras and compare every existing file. No missing entry is
  # installed until this complete validation pass succeeds.
  checked_find_list plain "$CUR_COE" -mindepth 1
  EXISTING_COE_LIST="$LAST_FIND_LIST"
  while IFS= read -r -d '' existing_coe; do
    relative_coe="${existing_coe#"$CUR_COE"/}"
    staged_coe="$COE_STAGE/$relative_coe"
    [[ -e "$staged_coe" || -L "$staged_coe" ]] || \
      die "existing cur_coe output has no staged counterpart: $existing_coe"
    if [[ -d "$existing_coe" && ! -L "$existing_coe" ]]; then
      [[ -d "$staged_coe" && ! -L "$staged_coe" ]] || \
        die "existing cur_coe output type differs: $existing_coe"
    else
      copy_missing_or_verify "$staged_coe" "$existing_coe"
    fi
  done <"$EXISTING_COE_LIST"

  for relative_coe in irom.coe dram.coe coremark-workload.env; do
    staged_coe="$COE_STAGE/$relative_coe"
    installed_coe="$CUR_COE/$relative_coe"
    if [[ ! -e "$installed_coe" && ! -L "$installed_coe" ]]; then
      # The source is already staged beside CUR_COE; rename each missing file
      # into place atomically after all existing files have compared equal.
      safe_move_new "$staged_coe" "$installed_coe"
    fi
    require_single_link_file "$installed_coe"
  done
fi

for installed_coe in "$CUR_COE/irom.coe" "$CUR_COE/dram.coe" "$CUR_COE/coremark-workload.env"; do
  require_single_link_file "$installed_coe"
done

INSTALLED_COE_MANIFEST="$PREFLIGHT_STAGE/installed-coe-payload.sha256"
write_content_manifest "$CUR_COE" "$INSTALLED_COE_MANIFEST" .
if ! cmp -s "$COE_PAYLOAD_MANIFEST" "$INSTALLED_COE_MANIFEST"; then
  echo "error: installed cur_coe payload does not match the validated stage" >&2
  diff -u "$COE_PAYLOAD_MANIFEST" "$INSTALLED_COE_MANIFEST" >&2 || true
  exit 1
fi
echo "   cur_coe/irom.coe sha256: $COE_TEXT"
echo "   cur_coe/dram.coe sha256: $COE_DATA"
echo "   cur_coe workload: ITERATIONS=10000"
if [[ "$SKIP_COE_CHECK" -eq 1 ]]; then
  echo "   frozen COE hash check: skipped by explicit --skip-coe-check"
fi

# --- optional simulator identity smoke test ---------------------------------
if [[ "$VERIFY_SIM" -eq 1 ]]; then
  VERIFY_DIR="$JYD_DATA_ROOT/tmp/create-opt-worktree-verification"
  VERIFY_LOG="$VERIFY_DIR/$NAME-add.log"
  ensure_real_directory "$VERIFY_DIR"
  safe_remove_regular "$VERIFY_LOG"
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
  require_real_directory "$WT_DIR/npc/build/bin"
  checked_find_list plain "$WT_DIR/npc/build/bin" -maxdepth 1 -type f -executable \
    -name 'JYDSoC-jyd-*'
  VERIFY_SIM_LIST="$LAST_FIND_LIST"
  if ! IFS= read -r -d '' verified_simulator <"$VERIFY_SIM_LIST"; then
    echo "error: worktree-local simulator was not built under $WT_DIR/npc/build/bin" >&2
    exit 1
  fi
  echo "   verified simulator commit: $COMMIT_FULL"
  echo "   verification log: $VERIFY_LOG"
fi

[[ -f "$SETUP_MARKER" && ! -L "$SETUP_MARKER" ]] || \
  die "incomplete setup marker disappeared during installation: $SETUP_MARKER"
cmp -s "$INPUT_STATE" "$SETUP_MARKER" || \
  die "incomplete setup marker changed during installation: $SETUP_MARKER"
safe_remove_regular "$SETUP_MARKER"
echo "   cleared completed setup marker: $SETUP_MARKER"

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
