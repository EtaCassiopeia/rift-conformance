#!/usr/bin/env bash
# Plane A runner (rift-conformance issue #15): the samples.rift.* ZIO-BDD suites against a real rift.
# RIFT_MODE=external (default) spawns a real rift child (RiftMode.External); RIFT_MODE=embedded (#18)
# runs the engine in-process over Panama FFM — zero spawn, no Docker — and runs the embedded-supported
# subset (engineEmbedded: every feature but 09 and 16; COVERAGE.md's feature × mode matrix, #206). Fetches the released
# engine first if RIFT_BUILD_DIR is unset (the lint/verify tools + BUILD_INFO come from it in BOTH
# modes; embedded only swaps how the *engine* runs).
#
# Usage: scripts/conformance-engine.sh
#
# RIFT_MODE=container (#12) runs the engine in a Docker container on a published image (RIFT_IMAGE)
# and runs the container-safe subset (engineContainer: every feature but 09, 11 and 16, minus the
# tags build.sbt names per mode). Docker/RIFT_IT-gated — SKIPs when RIFT_IT!=1 or docker is absent.
#
# Usage: scripts/conformance-engine.sh
#
# Env:
#   RIFT_BUILD_DIR    pre-fetched engine dir; if unset, fetched via build-rift.sh (released rift)
#   RIFT_MODE         harness mode: external (default) | embedded | container
#   RIFT_CORPUS_ROOT  imposter corpus root (default: corpus, matches RiftHarness)
#   RIFT_FFI_LIB      (embedded) explicit librift_ffi path; else the bundled natives jar is used
#   RIFT_IT           (container) set to 1 to opt into the Docker-gated container leg
#   RIFT_IMAGE        (container) image tag to run (default: zainalpour/rift-proxy:v0.19.0)
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib.sh
source "$SCRIPT_DIR/lib.sh"
require sbt

export RIFT_MODE="${RIFT_MODE:-external}"
export RIFT_CORPUS_ROOT="${RIFT_CORPUS_ROOT:-corpus}"

# The sbt alias per mode: embedded runs only the suites the in-process FFM oracle supports.
SBT_TASK="engineConformance"
if [ "$RIFT_MODE" = "embedded" ]; then
  jdk_major() {
    "${JAVA_HOME:+$JAVA_HOME/bin/}java" -version 2>&1 \
      | awk -F'"' '/version/ {print $2}' | awk -F. '{ if ($1=="1") print $2; else print $1 }'
  }
  JDK_MAJOR="$(jdk_major)"
  if [ -z "$JDK_MAJOR" ] || [ "$JDK_MAJOR" -lt 22 ]; then
    # The FFM binding jar is Java-22 bytecode (zio-bdd#247); embedded can't load on JDK < 22. SKIP
    # cleanly so run-all.sh stays green on JDK 11/17/21 (external already covered the engine there).
    echo "conformance-engine: SKIP embedded — needs JDK 22+ (have ${JDK_MAJOR:-unknown}); see #18" >&2
    exit "$SKIP_EXIT_CODE"
  fi
  SBT_TASK="engineEmbedded"
elif [ "$RIFT_MODE" = "container" ]; then
  # Docker-gated: opt-in via RIFT_IT=1, and docker must be usable.
  if [ "${RIFT_IT:-}" != "1" ]; then
    echo "conformance-engine: SKIP container — Docker-gated (RIFT_IT unset); see #12" >&2
    exit "$SKIP_EXIT_CODE"
  fi
  if ! command -v docker >/dev/null 2>&1 || ! docker info >/dev/null 2>&1; then
    echo "conformance-engine: SKIP container — docker not available; see #12" >&2
    exit "$SKIP_EXIT_CODE"
  fi
  SBT_TASK="engineContainer"
fi

if [ -z "${RIFT_BUILD_DIR:-}" ]; then
  echo "conformance-engine: RIFT_BUILD_DIR unset — fetching released rift via build-rift.sh" >&2
  eval "$("$SCRIPT_DIR/build-rift.sh")" || { echo "conformance-engine: build-rift.sh failed" >&2; exit 1; }
fi
export RIFT_BUILD_DIR

echo "conformance-engine: RIFT_BUILD_DIR=$RIFT_BUILD_DIR RIFT_MODE=$RIFT_MODE RIFT_CORPUS_ROOT=$RIFT_CORPUS_ROOT task=$SBT_TASK"

cd "$PROJECT_ROOT"
sbt -batch "$SBT_TASK"
rc=$?

# Zero-spawn assertion (#18): a full embedded run must spawn no rift engine child. Spawned engines
# carry `--allow-injection` (external-mode rawSpawn); none should be running now. RiftEmbeddedHarnessSpec
# asserts the stronger "none spawned *during* the run" in-JVM; this is the belt-and-braces sweep.
if [ "$RIFT_MODE" = "embedded" ] && [ "$rc" -eq 0 ]; then
  # Distinguish "pgrep found nothing" (exit 1) from "pgrep can't run" (absent/errors): without this
  # guard a slim image lacking procps would print "OK" for a check that never ran.
  if ! command -v pgrep >/dev/null 2>&1; then
    echo "conformance-engine: FAIL embedded — pgrep not available; cannot verify zero-spawn" >&2
    exit 1
  fi
  if pgrep -f -- '--allow-injection' >/dev/null 2>&1; then
    echo "conformance-engine: FAIL embedded — a rift engine child (--allow-injection) is running; embedded must not spawn" >&2
    exit 1
  fi
  echo "conformance-engine: embedded zero-spawn OK (no rift engine child)"
fi

exit "$rc"
