#!/usr/bin/env bash
# Plane B "rift-container" backend runner — forward-compat stub (rift-conformance issue #15).
#
# Not yet implemented: RIFT_MODE=container + `build-rift.sh --image` land in #12. This stub is
# already Docker/RIFT_IT-gated so flipping it on later is a content-only change to this file.
#
# Forward-compat env (accepted now, wired up once the backend exists):
#   RIFT_IMAGE   docker image tag to run against, e.g. rift-proxy:local (see build-rift.sh --image)
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib.sh
source "$SCRIPT_DIR/lib.sh"

: "${RIFT_IMAGE:=}"

if [ "${RIFT_IT:-}" != "1" ]; then
  echo "conformance-rift: Docker-gated (RIFT_IT unset) — see #12" >&2
  exit "$SKIP_EXIT_CODE"
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "conformance-rift: Docker-gated (docker not found) — see #12" >&2
  exit "$SKIP_EXIT_CODE"
fi

echo "conformance-rift: not yet implemented — tracked by #12 (RIFT_IT=1, RIFT_IMAGE=${RIFT_IMAGE:-<unset>})" >&2
exit "$SKIP_EXIT_CODE"
