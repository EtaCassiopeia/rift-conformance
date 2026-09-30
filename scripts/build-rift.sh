#!/usr/bin/env bash
#
# build-rift.sh — the single source of Rift engine artifacts for every conformance run.
#
# rift-conformance is self-contained (#38): the engine is fetched as a published GitHub Release
# tarball for the host platform — no sibling clone, no `cargo build`. See DESIGN.md §3.2.
#
# Usage:  scripts/build-rift.sh
#
# On success the ONLY thing written to stdout is a single line:
#   export RIFT_BUILD_DIR=<dir with the fetched binaries + BUILD_INFO>
# so a caller can `eval "$(scripts/build-rift.sh)"`. All diagnostics go to stderr.
#
# Environment overrides:
#   RIFT_BUILD_DIR    if set (and it already contains the binaries), skips the fetch entirely
#                     (deliberate branch/local-build testing) and is echoed back as-is.
#   RIFT_VERSION      the released rift version to fetch (default: 0.19.0).
#   RIFT_BUILD_CACHE  cache ROOT for downloads (default: ~/.cache/rift-conformance). Each release
#                     lands in <root>/rift-v<version>-<triple>; a dir that already has
#                     bin/rift + bin/BUILD_INFO is reused without re-downloading (idempotent).
set -euo pipefail

REPO="achird-labs/rift"
RIFT_VERSION="${RIFT_VERSION:-0.19.0}"
CACHE_ROOT="${RIFT_BUILD_CACHE:-$HOME/.cache/rift-conformance}"
BINARIES="rift rift-verify rift-lint"

log() { echo "build-rift: $*" >&2; }
first_line() { printf '%s' "$1" | head -1; }

sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  else
    shasum -a 256 "$1" | awk '{print $1}'
  fi
}

verify_sha256() { # <file> <sha256-file> ; runs in the containing directory so the checksum
                   # file's (relative) filename reference resolves.
  local dir file
  dir="$(cd "$(dirname "$1")" && pwd)"
  file="$(basename "$1")"
  # Redirect the tool's own "<file>: OK" report to stderr — stdout must carry ONLY the final
  # `export RIFT_BUILD_DIR=...` line (the `eval "$(...)"` contract), never a checksum report.
  if command -v sha256sum >/dev/null 2>&1; then
    ( cd "$dir" && sha256sum -c "$(basename "$2")" ) >&2
  else
    ( cd "$dir" && shasum -a 256 -c "$(basename "$2")" ) >&2
  fi
}

emit() { # print the one clean stdout line
  echo "export RIFT_BUILD_DIR=\"$1\""
}

# ---- escape hatch: a ready-built dir supplied by the caller ----------------------------
if [ -n "${RIFT_BUILD_DIR:-}" ]; then
  log "RIFT_BUILD_DIR set — skipping fetch, using $RIFT_BUILD_DIR"
  for b in $BINARIES; do
    [ -x "$RIFT_BUILD_DIR/$b" ] || { log "escape-hatch dir missing binary: $b"; exit 1; }
  done
  emit "$RIFT_BUILD_DIR"
  exit 0
fi

# ---- detect the platform triple ---------------------------------------------------------
detect_triple() {
  local os arch
  os="$(uname -s)"
  arch="$(uname -m)"
  case "$os-$arch" in
    Darwin-arm64) echo "aarch64-apple-darwin" ;;
    Darwin-x86_64) echo "x86_64-apple-darwin" ;;
    Linux-aarch64) echo "aarch64-unknown-linux-gnu" ;;
    Linux-x86_64) echo "x86_64-unknown-linux-gnu" ;;
    *)
      log "unsupported platform: uname -s='$os' uname -m='$arch'"
      log "supported: Darwin/arm64, Darwin/x86_64, Linux/aarch64, Linux/x86_64 (Windows out of scope)"
      exit 1
      ;;
  esac
}
TRIPLE="$(detect_triple)"

TARGET_DIR="$CACHE_ROOT/rift-v$RIFT_VERSION-$TRIPLE"
BUILD_DIR="$TARGET_DIR/bin"

# ---- cache hit: a complete previous download for this version+triple --------------------
if [ -x "$BUILD_DIR/rift" ] && [ -f "$BUILD_DIR/BUILD_INFO" ]; then
  log "cache hit for rift v$RIFT_VERSION ($TRIPLE) — reusing $BUILD_DIR"
  emit "$BUILD_DIR"
  exit 0
fi

mkdir -p "$CACHE_ROOT"
ASSET="rift-v$RIFT_VERSION-$TRIPLE.tar.gz"
WORK="$(mktemp -d "$CACHE_ROOT/.download-XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

log "fetching $ASSET (rift v$RIFT_VERSION release)"
if command -v gh >/dev/null 2>&1; then
  gh release download "v$RIFT_VERSION" --repo "$REPO" --pattern "$ASSET*" --dir "$WORK" --clobber
else
  log "gh CLI not found — falling back to curl"
  BASE_URL="https://github.com/$REPO/releases/download/v$RIFT_VERSION"
  curl -fsSL -o "$WORK/$ASSET" "$BASE_URL/$ASSET"
  curl -fsSL -o "$WORK/$ASSET.sha256" "$BASE_URL/$ASSET.sha256"
fi

[ -f "$WORK/$ASSET" ] || { log "download failed: $ASSET missing from $WORK"; exit 1; }
[ -f "$WORK/$ASSET.sha256" ] || { log "download failed: $ASSET.sha256 missing from $WORK"; exit 1; }

log "verifying sha256 of $ASSET"
verify_sha256 "$WORK/$ASSET" "$WORK/$ASSET.sha256" \
  || { log "sha256 verification FAILED for $ASSET"; exit 1; }

log "extracting $ASSET into $CACHE_ROOT"
rm -rf "$TARGET_DIR"
tar -xzf "$WORK/$ASSET" -C "$CACHE_ROOT"
[ -d "$BUILD_DIR" ] || { log "extraction did not produce expected dir: $BUILD_DIR"; exit 1; }

# Only the binaries we actually use are required; rift-tui ships in the tarball but is never
# invoked, so don't let its (hypothetical) absence abort the fetch under `set -e`.
for b in $BINARIES; do chmod +x "$BUILD_DIR/$b"; done
[ -f "$BUILD_DIR/rift-tui" ] && chmod +x "$BUILD_DIR/rift-tui" || true

# ---- resolve the release tag's commit sha (best-effort; falls back to the tag name) -----
resolve_commit() {
  if command -v gh >/dev/null 2>&1; then
    local sha
    sha="$(gh api "repos/$REPO/git/refs/tags/v$RIFT_VERSION" --jq .object.sha 2>/dev/null || true)"
    if [ -n "$sha" ]; then
      printf '%s' "$sha"
      return
    fi
    log "gh api could not resolve the v$RIFT_VERSION tag sha — falling back to the tag name"
  fi
  printf 'v%s' "$RIFT_VERSION"
}
COMMIT="$(resolve_commit)"

# ---- write BUILD_INFO: the release ships none, but RiftBuildInfo + the consistency gate --
# ---- require it. branch = the release tag (not "master" — we consume official releases). ---
write_build_info() {
  local builtAt binaries versions name file sha ver_out ver rc
  builtAt="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  binaries='{}'
  versions='{}'
  for name in $BINARIES; do
    file="$BUILD_DIR/$name"
    [ -x "$file" ] || { log "expected artifact missing: $file"; return 1; }
    sha="$(sha256_of "$file")"
    binaries="$(printf '%s' "$binaries" | jq --arg n "$name" --arg s "$sha" '. + {($n): $s}')"
    # Actually run --version so BUILD_INFO records what the live binary reports (the gate's
    # coherence check compares the two) rather than an assumed string.
    ver_out="$("$file" --version 2>&1)" && rc=0 || rc=$?
    if [ "$rc" -ne 0 ]; then
      log "artifact $name crashed on --version (exit $rc): $(first_line "$ver_out")"
      return 1
    fi
    ver="$(first_line "$ver_out")"
    versions="$(printf '%s' "$versions" | jq --arg n "$name" --arg v "$ver" '. + {($n): $v}')"
  done
  jq -n \
    --arg commit "$COMMIT" --arg branch "v$RIFT_VERSION" --arg builtAt "$builtAt" \
    --argjson binaries "$binaries" --argjson versions "$versions" \
    '{commit: $commit, branch: $branch, builtAt: $builtAt, binaries: $binaries, versions: $versions}' \
    > "$BUILD_DIR/BUILD_INFO"
}
write_build_info

log "fetch ready: $BUILD_DIR"
emit "$BUILD_DIR"
