#!/usr/bin/env bash
# One-command layered conformance summary (rift-conformance issue #15).
#
# Layer list (DESIGN.md §9):
#   engine (Plane A)          — samples.rift.* suites against a real rift             [RUNS]
#   wiremock (Plane B)        — not yet migrated                                  [SKIP see #13/#14]
#   embedded (Plane B)        — not yet migrated                                  [SKIP see #13/#14]
#   rift-external (Plane B)   — not yet migrated                                  [SKIP see #13/#14]
#   rift-container (Plane B)  — Docker-gated (RIFT_IT)                              [SKIP see #12]
#
# Usage:
#   ./scripts/run-all.sh            # run against an already-fetched engine (fetched lazily otherwise)
#   ./scripts/run-all.sh --build    # fetch the released rift via build-rift.sh first
#
# Env:
#   RIFT_BUILD_DIR    pre-fetched engine dir (skips build-rift.sh's download)
#   RIFT_CORPUS_ROOT  imposter corpus root (default ../rift-imposter-samples)
#   RIFT_IT           set to 1 to attempt the Docker-gated rift-container layer (#12)
#
# Exit code: 0 iff the Plane A layer passed and no layer FAILed (SKIPs never fail the run).
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib.sh
source "$SCRIPT_DIR/lib.sh"

DO_BUILD=0
for arg in "$@"; do
  case "$arg" in
    --build) DO_BUILD=1 ;;
    *) echo "run-all: unknown flag: $arg" >&2; exit 2 ;;
  esac
done

if [ "$DO_BUILD" = 1 ] && [ -z "${RIFT_BUILD_DIR:-}" ]; then
  echo "== build-rift.sh =="
  eval "$("$SCRIPT_DIR/build-rift.sh")" || { echo "run-all: build failed" >&2; exit 1; }
  export RIFT_BUILD_DIR
fi

run_layer "engine (Plane A)" "$SCRIPT_DIR/conformance-engine.sh"

run_layer "wiremock (Plane B)" bash -c \
  'echo "SKIP: wiremock backend not yet migrated — see #13/#14" >&2; exit 77'

run_layer "embedded (Plane B)" "$SCRIPT_DIR/conformance-embedded.sh"

run_layer "rift-external (Plane B)" bash -c \
  'echo "SKIP: rift-external backend not yet migrated — see #13/#14" >&2; exit 77'

run_layer "rift-container (Plane B)" "$SCRIPT_DIR/conformance-rift.sh"

print_summary
exit $?
