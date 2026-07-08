#!/usr/bin/env bash
# Plane B "wiremock" backend runner (rift-conformance issue #13): the samples.mock.* MockControl
# corpus against the in-process WireMock backend (zio-bdd-wiremock) — no Docker, works on any JDK.
# This is the default Plane B leg; ported from zio-bdd-samples' scripts/conformance.sh.
#
# Usage: scripts/conformance-mock.sh
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib.sh
source "$SCRIPT_DIR/lib.sh"
require sbt

echo "conformance-mock: MOCK_BACKEND=wiremock (in-process, no Docker)"

cd "$PROJECT_ROOT"
MOCK_BACKEND=wiremock sbt -batch mockConformance
