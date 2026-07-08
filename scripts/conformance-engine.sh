#!/usr/bin/env bash
# Plane A runner (rift-conformance issue #15): the samples.rift.* ZIO-BDD suites against a real
# rift child (RiftMode.External). Fetches the released engine first if RIFT_BUILD_DIR is unset.
#
# Usage: scripts/conformance-engine.sh
#
# Env:
#   RIFT_BUILD_DIR    pre-fetched engine dir; if unset, fetched via build-rift.sh (released rift)
#   RIFT_MODE         harness mode (default: external — the only mode implemented so far)
#   RIFT_CORPUS_ROOT  imposter corpus root (default: corpus, matches RiftHarness)
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib.sh
source "$SCRIPT_DIR/lib.sh"
require sbt

if [ -z "${RIFT_BUILD_DIR:-}" ]; then
  echo "conformance-engine: RIFT_BUILD_DIR unset — fetching released rift via build-rift.sh" >&2
  eval "$("$SCRIPT_DIR/build-rift.sh")" || { echo "conformance-engine: build-rift.sh failed" >&2; exit 1; }
fi
export RIFT_BUILD_DIR
export RIFT_MODE="${RIFT_MODE:-external}"
export RIFT_CORPUS_ROOT="${RIFT_CORPUS_ROOT:-corpus}"

echo "conformance-engine: RIFT_BUILD_DIR=$RIFT_BUILD_DIR RIFT_MODE=$RIFT_MODE RIFT_CORPUS_ROOT=$RIFT_CORPUS_ROOT"

cd "$PROJECT_ROOT"
sbt "testOnly samples.rift.*"
