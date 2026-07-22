#!/usr/bin/env bash
# Feature step-matching gate (rift-conformance issue #142): loads the Plane A BDD suites under
# --dry-run and checks every Gherkin step resolves to a step definition. Spawns NO engine (the
# `lintFeatures` alias sets -Drift.dryRun=true so RiftHarness.layer yields RiftHarnessDryRun), so it
# needs no RIFT_BUILD_DIR, no Docker, and no fetched rift — the cheapest conformance layer, safe to
# run in the compile/format CI job.
#
# Scope note: Plane B (samples.mock.*) is NOT dry-run-clean yet — a scenario carrying both @rift and
# @mock(orders) has its Rift-only fixture provisioned on the wiremock backend even under
# --exclude-tags rift (an upstream zio-bdd tag-exclusion bug). The alias is Plane-A-only until that
# is fixed; see the `lintFeatures` comment in build.sbt.
#
# Usage: scripts/lint-features.sh
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib.sh
source "$SCRIPT_DIR/lib.sh"
require sbt

echo "lint-features: dry-run step-matching over samples.rift.*Suite (no engine)"

cd "$PROJECT_ROOT"
sbt -batch lintFeatures
