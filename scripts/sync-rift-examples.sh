#!/usr/bin/env bash
# Refresh corpus/rift-examples/ from rift's shipped examples/ at the pinned tag.
#
# The release tarball does NOT ship examples/ (only bin/, CHANGELOG.md, LICENSE, README.md), so the
# oracle's inputs have to come from the repo tree at the tag. They are vendored rather than fetched
# at test time so ShippedExamples120Spec stays hermetic (no network, no rate limits in CI).
#
# Run this as part of the engine-pin bump: a stale vendored copy would let the oracle keep passing
# against examples the engine no longer ships. The git blob SHAs recorded in PROVENANCE.md are what
# make that staleness detectable — --check verifies them without writing anything.
set -euo pipefail

REPO="${RIFT_REPO:-achird-labs/rift}"
VERSION="${RIFT_VERSION:-0.19.0}"
TAG="v${VERSION}"
DEST="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/corpus/rift-examples"
CHECK_ONLY=0
[[ "${1:-}" == "--check" ]] && CHECK_ONLY=1

command -v gh >/dev/null || { echo "sync-rift-examples: gh is required" >&2; exit 2; }

# git blob SHA: sha1("blob <bytelen>\0" + content) — the same identity gh reports per file, so a
# mismatch means the vendored copy and the tag have genuinely diverged.
blob_sha() { python3 -c '
import hashlib,sys
d=open(sys.argv[1],"rb").read()
sys.stdout.write(hashlib.sha1(b"blob %d\0" % len(d)+d).hexdigest())' "$1"; }

listing="$(gh api "repos/${REPO}/contents/examples?ref=${TAG}" --jq '.[] | select(.name|endswith(".json")) | "\(.name) \(.sha)"')"
[[ -n "$listing" ]] || { echo "sync-rift-examples: no examples/*.json at ${REPO}@${TAG}" >&2; exit 1; }

mkdir -p "$DEST"
rc=0
while read -r name sha; do
  [[ -n "$name" ]] || continue
  if (( CHECK_ONLY )); then
    if [[ ! -f "$DEST/$name" ]]; then
      echo "MISSING  $name (present at ${TAG})"; rc=3
    elif [[ "$(blob_sha "$DEST/$name")" != "$sha" ]]; then
      echo "DRIFTED  $name (vendored copy differs from ${TAG})"; rc=3
    else
      echo "ok       $name"
    fi
  else
    gh api "repos/${REPO}/contents/examples/${name}?ref=${TAG}" --jq .content | base64 -d > "$DEST/$name"
    got="$(blob_sha "$DEST/$name")"
    [[ "$got" == "$sha" ]] || { echo "sync-rift-examples: sha mismatch for $name ($got != $sha)" >&2; exit 1; }
    echo "synced   $name  $sha"
  fi
done <<< "$listing"

# A file we vendor that the tag no longer ships is drift too — the oracle would keep replaying a
# document the engine has dropped.
shipped=()
while read -r name _; do [[ -n "$name" ]] && shipped+=("$name"); done <<< "$listing"
for f in "$DEST"/*.json; do
  [[ -e "$f" ]] || continue   # unmatched glob stays literal when the dir has no .json files
  n="$(basename "$f")"
  found=0
  for s in "${shipped[@]}"; do [[ "$s" == "$n" ]] && { found=1; break; }; done
  (( found )) || { echo "EXTRA    $n (not present at ${TAG})"; rc=3; }
done

if (( CHECK_ONLY )); then
  (( rc == 0 )) && echo "corpus/rift-examples is in sync with ${REPO}@${TAG}"
  exit $rc
fi
# Also non-zero in sync mode: the EXTRA loop above can detect a vendored file the tag no longer
# ships, and sync mode does not rewrite that file. Exiting 0 there would let a stale example survive
# a pin bump that reported success.
(( rc == 0 )) || { echo "sync-rift-examples: stale vendored file(s) — remove them and re-run" >&2; exit $rc; }
echo "Update corpus/rift-examples/PROVENANCE.md with tag ${TAG} and the SHAs above."
