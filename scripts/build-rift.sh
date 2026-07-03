#!/usr/bin/env bash
#
# build-rift.sh — the single source of Rift engine artifacts for every conformance run.
#
# Standing rule (project owner, non-negotiable): conformance ALWAYS builds from a clean clone
# of `master` — never the developer's working checkout, which may carry unfinished branch work.
# See DESIGN.md §3.2.
#
# Usage:  scripts/build-rift.sh [--ffi] [--image]
#   --ffi    also build the rift-ffi cdylib (librift_ffi.<ext>) for the Plane B embedded leg
#   --image  also `docker build -t rift-proxy:local` for container mode (#12)
#
# On success the ONLY thing written to stdout is a single line:
#   export RIFT_BUILD_DIR=<dir with the freshly built binaries + BUILD_INFO>
# so a caller can `eval "$(scripts/build-rift.sh)"`. All diagnostics go to stderr.
#
# Environment overrides:
#   RIFT_BUILD_DIR    if set, treated as a ready-built dir: skips the clone AND the build
#                     entirely (deliberate branch testing). Default path is always clean-master.
#   RIFT_BUILD_CACHE  cache ROOT for clones (default: ~/.cache/rift-conformance). Each build
#                     lands in <root>/<date>-<sha>; reuse is keyed on the master SHA, so a dir
#                     already built for HEAD's SHA is reused without rebuilding (idempotent).
#   RIFT_SRC_REMOTE   git remote to clone (default: the canonical rift repo). Overridable so
#                     the gate can point at a fast fake engine; production never sets it.
set -euo pipefail

DEFAULT_REMOTE="https://github.com/EtaCassiopeia/rift.git"
REMOTE="${RIFT_SRC_REMOTE:-$DEFAULT_REMOTE}"
BRANCH="master"
CACHE_ROOT="${RIFT_BUILD_CACHE:-$HOME/.cache/rift-conformance}"

BUILD_FFI=0
BUILD_IMAGE=0
for arg in "$@"; do
  case "$arg" in
    --ffi)   BUILD_FFI=1 ;;
    --image) BUILD_IMAGE=1 ;;
    *) echo "build-rift.sh: unknown argument: $arg" >&2; exit 2 ;;
  esac
done

log() { echo "build-rift: $*" >&2; }
first_line() { printf '%s' "$1" | head -1; }

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

lib_ext() {
  case "$(uname -s)" in
    Darwin) echo "dylib" ;;
    *)      echo "so" ;;
  esac
}

# Write BUILD_INFO into $build_dir: {commit, branch, builtAt, binaries:{name:sha256}, versions:{name:--version}}.
# $2.. are entries "name[:noversion]" — a :noversion suffix (used for the ffi lib) records a
# sha256 but no --version output.
write_build_info() {
  local build_dir="$1" clone_dir="$2"; shift 2
  local commit builtAt binaries versions name file sha ver ver_out rc
  commit="$(git -C "$clone_dir" rev-parse HEAD)"
  builtAt="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  binaries='{}'
  versions='{}'
  for entry in "$@"; do
    name="${entry%%:*}"
    file="$build_dir/$name"
    [ -f "$file" ] || { log "expected artifact missing: $file"; return 1; }
    sha="$(sha256_of "$file")"
    binaries="$(printf '%s' "$binaries" | jq --arg n "$name" --arg s "$sha" '. + {($n): $s}')"
    if [ "$entry" = "$name" ]; then
      # Capture stderr too and surface it: a binary that crashes on --version is a real
      # engine-build regression, not something to swallow into a contextless script abort.
      ver_out="$("$file" --version 2>&1)" && rc=0 || rc=$?
      if [ "$rc" -ne 0 ]; then
        log "artifact $name crashed on --version (exit $rc): $(first_line "$ver_out")"
        return 1
      fi
      ver="$(first_line "$ver_out")"
      versions="$(printf '%s' "$versions" | jq --arg n "$name" --arg v "$ver" '. + {($n): $v}')"
    fi
  done
  jq -n \
    --arg commit "$commit" --arg branch "$BRANCH" --arg builtAt "$builtAt" \
    --argjson binaries "$binaries" --argjson versions "$versions" \
    '{commit: $commit, branch: $branch, builtAt: $builtAt, binaries: $binaries, versions: $versions}' \
    > "$build_dir/BUILD_INFO"
}

emit() { # print the one clean stdout line
  echo "export RIFT_BUILD_DIR=\"$1\""
}

# ---- escape hatch: a ready-built dir supplied by the caller ----------------------------
if [ -n "${RIFT_BUILD_DIR:-}" ]; then
  log "RIFT_BUILD_DIR set — skipping clone/build, using $RIFT_BUILD_DIR"
  for b in rift-http-proxy rift-verify rift-lint; do
    [ -x "$RIFT_BUILD_DIR/$b" ] || { log "escape-hatch dir missing binary: $b"; exit 1; }
  done
  if [ "$BUILD_FFI" -eq 1 ] || [ "$BUILD_IMAGE" -eq 1 ]; then
    log "note: --ffi/--image are ignored when RIFT_BUILD_DIR is preset (using it as-is)"
  fi
  emit "$RIFT_BUILD_DIR"
  exit 0
fi

# ---- resolve master SHA up front so the cache lookup never needs a clone ----------------
log "resolving $BRANCH on $REMOTE"
FULL_SHA="$(git ls-remote "$REMOTE" "refs/heads/$BRANCH" | awk '{print $1}')"
[ -n "$FULL_SHA" ] || { log "could not resolve $BRANCH on $REMOTE"; exit 1; }
SHORT_SHA="${FULL_SHA:0:12}"

mkdir -p "$CACHE_ROOT"
# Reuse is keyed on the SHA suffix — a dir built for this SHA on any earlier date still hits.
CLONE_DIR=""
for d in "$CACHE_ROOT"/*-"$SHORT_SHA"; do
  [ -d "$d" ] || continue
  CLONE_DIR="$d"
  break
done

# A cache hit means the clone + base binaries already exist — so the base clone/build is
# skipped and never re-run (that's what keeps the base binaries' timestamps stable across
# runs). Requested extras (--ffi / --image) are still produced idempotently below, since a
# prior run may have built the base without them.
# A cache hit requires ALL base binaries — a dir with only some (interrupted/partial build)
# must NOT be treated as a hit, or the script would emit a RIFT_BUILD_DIR missing binaries.
CACHE_HIT=0
if [ -n "$CLONE_DIR" ] \
  && [ -x "$CLONE_DIR/target/release/rift-http-proxy" ] \
  && [ -x "$CLONE_DIR/target/release/rift-verify" ] \
  && [ -x "$CLONE_DIR/target/release/rift-lint" ]; then
  CACHE_HIT=1
  log "cache hit for $SHORT_SHA — reusing $CLONE_DIR (no base rebuild)"
else
  # ---- clean-master clone + release build ----------------------------------------------
  BUILD_DATE="$(date -u +%Y%m%d)"
  if [ -z "$CLONE_DIR" ]; then
    CLONE_DIR="$CACHE_ROOT/$BUILD_DATE-$SHORT_SHA"
  fi
  if [ ! -d "$CLONE_DIR/.git" ]; then
    log "cloning $REMOTE@$BRANCH ($SHORT_SHA) into $CLONE_DIR"
    rm -rf "$CLONE_DIR"
    git clone --quiet --depth 1 --branch "$BRANCH" "$REMOTE" "$CLONE_DIR"
    # master may advance between ls-remote and clone; keep the cache dir name honest with
    # its actual contents so the SHA-keyed reuse never serves a mislabeled commit.
    ACTUAL_SHA="$(git -C "$CLONE_DIR" rev-parse HEAD)"
    if [ "$ACTUAL_SHA" != "$FULL_SHA" ]; then
      NEW_DIR="$CACHE_ROOT/$BUILD_DATE-${ACTUAL_SHA:0:12}"
      log "master advanced during clone ($SHORT_SHA → ${ACTUAL_SHA:0:12}); relocating cache dir"
      rm -rf "$NEW_DIR"
      mv "$CLONE_DIR" "$NEW_DIR"
      CLONE_DIR="$NEW_DIR"
    fi
  fi
  log "cargo build --release (rift-http-proxy, rift-verify, rift-lint)"
  cargo build --release --manifest-path "$CLONE_DIR/Cargo.toml" \
    -p rift-http-proxy --bin rift-http-proxy --bin rift-verify \
    -p rift-lint --bin rift-lint
fi
BUILD_DIR="$CLONE_DIR/target/release"

INFO_ENTRIES="rift-http-proxy rift-verify rift-lint"
BUILT_EXTRA=0

if [ "$BUILD_FFI" -eq 1 ]; then
  LIB="librift_ffi.$(lib_ext)"
  if [ ! -f "$BUILD_DIR/$LIB" ]; then
    log "cargo build --release -p rift-ffi (cdylib)"
    cargo build --release --manifest-path "$CLONE_DIR/Cargo.toml" -p rift-ffi
    BUILT_EXTRA=1
  fi
  [ -f "$BUILD_DIR/$LIB" ] || { log "ffi build produced no $LIB"; exit 1; }
  INFO_ENTRIES="$INFO_ENTRIES $LIB:noversion"
fi

if [ "$BUILD_IMAGE" -eq 1 ]; then
  command -v docker >/dev/null 2>&1 || { log "--image requested but docker not found"; exit 1; }
  log "docker build -t rift-proxy:local $CLONE_DIR"
  docker build -t rift-proxy:local "$CLONE_DIR" >&2
  BUILT_EXTRA=1
fi

# Keep the existing BUILD_INFO on a pure cache hit (nothing new built) so its timestamp is
# stable; otherwise (re)write it to reflect what was just produced.
if [ "$CACHE_HIT" -eq 1 ] && [ "$BUILT_EXTRA" -eq 0 ] && [ -f "$BUILD_DIR/BUILD_INFO" ]; then
  log "cache hit — BUILD_INFO unchanged"
else
  write_build_info "$BUILD_DIR" "$CLONE_DIR" $INFO_ENTRIES
fi
log "build ready: $BUILD_DIR"
emit "$BUILD_DIR"
