#!/usr/bin/env bash
#
# fetch-sdk-corpus.sh — fetch the upstream SDK conformance corpus release artifact (#83).
#
# Each Rift release publishes `sdk-conformance-<version>.tar.gz` (imposter fixtures with `_verify`
# transcripts, data/, injection modules, manifest.json) — the engine-canonical, upstream-authored
# DSL↔engine parity oracle. This mirrors build-rift.sh: fetch the pinned tarball, verify its
# sha256, extract it into the shared cache, and echo a single `export RIFT_SDK_CORPUS_DIR=...` line
# so a caller can `eval "$(scripts/fetch-sdk-corpus.sh)"`. All diagnostics go to stderr.
#
# Environment overrides:
#   RIFT_SDK_CORPUS_DIR  if set (and it already holds manifest.json), skips the fetch and is echoed
#                        back as-is (branch/local-corpus testing).
#   RIFT_VERSION         the released rift version to fetch (default: the engine pin, read from
#                        build-rift.sh) — the corpus version equals the engine release version
#                        (there is no independent corpus version).
#   RIFT_BUILD_CACHE     cache ROOT for downloads (default: ~/.cache/rift-conformance). The corpus
#                        lands in <root>/sdk-conformance-v<version>; a dir that already has
#                        manifest.json is reused without re-downloading (idempotent).
set -euo pipefail

REPO="achird-labs/rift"
# Default to the engine pin so the corpus always matches the engine under test; build-rift.sh is
# the one place the pin lives.
PIN="$(sed -n 's/^RIFT_VERSION="\${RIFT_VERSION:-\([^}"]*\)}"$/\1/p' "$(dirname "$0")/build-rift.sh")"
[ -n "$PIN" ] || { echo "fetch-sdk-corpus: no RIFT_VERSION default in build-rift.sh" >&2; exit 1; }
RIFT_VERSION="${RIFT_VERSION:-$PIN}"
CACHE_ROOT="${RIFT_BUILD_CACHE:-$HOME/.cache/rift-conformance}"

log() { echo "fetch-sdk-corpus: $*" >&2; }

emit() { echo "export RIFT_SDK_CORPUS_DIR=\"$1\""; }

verify_sha256() { # <file> <sha256-file> — run in the containing dir so the checksum's filename ref resolves.
  local dir
  dir="$(cd "$(dirname "$1")" && pwd)"
  if command -v sha256sum >/dev/null 2>&1; then
    ( cd "$dir" && sha256sum -c "$(basename "$2")" ) >&2
  else
    ( cd "$dir" && shasum -a 256 -c "$(basename "$2")" ) >&2
  fi
}

# ---- escape hatch: a ready corpus dir supplied by the caller ---------------------------
if [ -n "${RIFT_SDK_CORPUS_DIR:-}" ]; then
  log "RIFT_SDK_CORPUS_DIR set — skipping fetch, using $RIFT_SDK_CORPUS_DIR"
  [ -f "$RIFT_SDK_CORPUS_DIR/manifest.json" ] || { log "escape-hatch dir missing manifest.json"; exit 1; }
  emit "$RIFT_SDK_CORPUS_DIR"
  exit 0
fi

TARGET_DIR="$CACHE_ROOT/sdk-conformance-v$RIFT_VERSION"

# ---- cache hit: a complete previous extraction for this version -------------------------
if [ -f "$TARGET_DIR/manifest.json" ]; then
  log "cache hit for sdk-conformance v$RIFT_VERSION — reusing $TARGET_DIR"
  emit "$TARGET_DIR"
  exit 0
fi

mkdir -p "$CACHE_ROOT"
ASSET="sdk-conformance-v$RIFT_VERSION.tar.gz"
WORK="$(mktemp -d "$CACHE_ROOT/.sdk-download-XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

# Download the asset + its .sha256. gh is preferred (matches build-rift.sh), but the release assets
# are PUBLIC, so curl is the robust fallback whenever gh is absent OR fails — notably in a GitHub
# Actions test step, where `gh` is installed but has no GH_TOKEN and hard-fails. Without this
# fallback the fetch would abort under `set -e` and the replay gate would silently no-op in CI.
download_curl() {
  local base="https://github.com/$REPO/releases/download/v$RIFT_VERSION"
  curl -fsSL -o "$WORK/$ASSET" "$base/$ASSET" \
    && curl -fsSL -o "$WORK/$ASSET.sha256" "$base/$ASSET.sha256"
}

log "fetching $ASSET (rift v$RIFT_VERSION release)"
fetched=0
if command -v gh >/dev/null 2>&1 && [ -n "${GH_TOKEN:-${GITHUB_TOKEN:-}}" ]; then
  if gh release download "v$RIFT_VERSION" --repo "$REPO" --pattern "$ASSET*" --dir "$WORK" --clobber; then
    fetched=1
  else
    log "gh release download failed — falling back to curl"
  fi
fi
if [ "$fetched" -ne 1 ]; then
  log "downloading via curl (public release asset)"
  download_curl || { log "curl download failed for $ASSET"; exit 1; }
fi

[ -f "$WORK/$ASSET" ] || { log "download failed: $ASSET missing from $WORK"; exit 1; }
[ -f "$WORK/$ASSET.sha256" ] || { log "download failed: $ASSET.sha256 missing from $WORK"; exit 1; }

log "verifying sha256 of $ASSET"
verify_sha256 "$WORK/$ASSET" "$WORK/$ASSET.sha256" \
  || { log "sha256 verification FAILED for $ASSET"; exit 1; }

log "extracting $ASSET into $CACHE_ROOT"
rm -rf "$TARGET_DIR"
tar -xzf "$WORK/$ASSET" -C "$CACHE_ROOT"
[ -f "$TARGET_DIR/manifest.json" ] || { log "extraction did not produce expected dir: $TARGET_DIR"; exit 1; }

log "fetch ready: $TARGET_DIR"
emit "$TARGET_DIR"
