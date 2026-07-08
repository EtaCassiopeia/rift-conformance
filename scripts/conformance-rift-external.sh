#!/usr/bin/env bash
# Plane B "rift-external" backend runner (rift-conformance issue #14): the samples.mock.*
# MockControl corpus against a REAL rift engine, spawned by the Plane A `RiftHarness` (no Docker,
# no FFM/JDK-22 floor — any JDK the harness itself supports). The MockControl adapter
# (`zio.bdd.mock.rift.Rift.connect`) drives the same engine build Plane A uses, over its admin API.
#
# Usage: scripts/conformance-rift-external.sh
#
# Env:
#   RIFT_BUILD_DIR    pre-fetched engine dir (skips build-rift.sh's download if already set)
#   RIFT_CORPUS_ROOT  imposter corpus root the harness spawns rift with as cwd (default
#                     corpus) — RiftHarness.layer requires it to start.
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib.sh
source "$SCRIPT_DIR/lib.sh"
require sbt

if [ -z "${RIFT_BUILD_DIR:-}" ]; then
  eval "$("$SCRIPT_DIR/build-rift.sh")" || { echo "conformance-rift-external: build failed" >&2; exit 1; }
fi
export RIFT_BUILD_DIR

echo "conformance-rift-external: MOCK_BACKEND=rift-external RIFT_BUILD_DIR=$RIFT_BUILD_DIR (no Docker)"

cd "$PROJECT_ROOT"
MOCK_BACKEND=rift-external sbt -batch mockRiftExternal
