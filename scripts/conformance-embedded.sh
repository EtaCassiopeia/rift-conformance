#!/usr/bin/env bash
# Plane B "embedded" backend runner (rift-conformance issue #13): the samples.mock.* MockControl
# corpus against the in-process Rift engine over Panama FFM (rift-java-embedded + rift-java-natives).
# No Docker, but needs a JDK 22+ (rift-java-embedded is Java-22 bytecode, and the
# forked test JVM adds --enable-native-access via build.sbt's ffmEnabled gate; FFM is stable on 22+
# so no --enable-preview) — SKIPs cleanly on older JDKs so run-all.sh stays green on JDK 11/17/21.
# Ported from zio-bdd-samples' scripts/conformance-embedded.sh.
#
# Usage: scripts/conformance-embedded.sh
#
# Forward-compat env:
#   RIFT_FFI_LIB   path to librift_ffi.<so|dylib>, as produced by `build-rift.sh --ffi`
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib.sh
source "$SCRIPT_DIR/lib.sh"
require sbt

jdk_major() {
  "${JAVA_HOME:+$JAVA_HOME/bin/}java" -version 2>&1 \
    | awk -F'"' '/version/ {print $2}' | awk -F. '{ if ($1=="1") print $2; else print $1 }'
}

JDK_MAJOR="$(jdk_major)"
if [ -z "$JDK_MAJOR" ] || [ "$JDK_MAJOR" -lt 22 ]; then
  # zio-bdd 1.3.1 ships the FFM binding only as the `-jdk21` module, but that module is compiled to
  # Java-22 bytecode (class 66), so it needs a 22+ runtime to load (upstream: zio-bdd#247).
  echo "conformance-embedded: SKIP — needs JDK 22+ (have ${JDK_MAJOR:-unknown}); see #13" >&2
  exit "$SKIP_EXIT_CODE"
fi

: "${RIFT_FFI_LIB:=}"
echo "conformance-embedded: MOCK_BACKEND=embedded JDK_MAJOR=$JDK_MAJOR RIFT_FFI_LIB=${RIFT_FFI_LIB:-<bundled>}"

cd "$PROJECT_ROOT"
MOCK_BACKEND=embedded sbt -batch mockEmbedded
