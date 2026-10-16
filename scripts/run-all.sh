#!/usr/bin/env bash
# One-command layered conformance summary (rift-conformance issue #15).
#
# Layer list (DESIGN.md §9):
#   engine (Plane A)          — samples.rift.* suites against a real rift             [RUNS]
#   engine embedded (Plane A) — 01–08 + 11 suites against in-process Rift (FFM, #18) [SKIP <JDK22]
#   engine container (Plane A)— 01 (gate, -@full-corpus-verify) + 03 against a Docker rift (#12) [SKIP no RIFT_IT]
#
# Plane B (the MockControl corpus) moved to EtaCassiopeia/zio-bdd (zio-bdd#362).
#
# Usage:
#   ./scripts/run-all.sh            # run against an already-fetched engine (fetched lazily otherwise)
#   ./scripts/run-all.sh --build    # fetch the released rift via build-rift.sh first
#
# Env:
#   RIFT_BUILD_DIR    pre-fetched engine dir (skips build-rift.sh's download)
#   RIFT_CORPUS_ROOT  imposter corpus root (default ./corpus, vendored in-repo)
#   RIFT_IT           set to 1 to attempt the Docker-gated engine container layer (#12)
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

# Same Plane A oracle, in-process over FFM (#18): zero spawn, no Docker. SKIPs on JDK < 22.
run_layer "engine embedded (Plane A)" env RIFT_MODE=embedded "$SCRIPT_DIR/conformance-engine.sh"

# Same Plane A oracle, in a Docker container on a published image (#12). Docker/RIFT_IT-gated → SKIPs
# unless RIFT_IT=1 + docker. Runs feature 01 (gate, incl. @full-corpus serve, minus
# @full-corpus-verify) + 03 (transport faults).
run_layer "engine container (Plane A)" env RIFT_MODE=container "$SCRIPT_DIR/conformance-engine.sh"

print_summary
exit $?
