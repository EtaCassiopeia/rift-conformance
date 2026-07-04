#!/usr/bin/env bash
# Plane B "embedded" backend runner — forward-compat stub (rift-conformance issue #15).
#
# Not yet implemented: the Plane B mock corpus/backend work lands in #13 (corpus migration) and
# #14 (Plane B backend: MOCK_BACKEND=rift-external). This stub gives run-all.sh and the Makefile
# a stable entry point now, so flipping this layer from SKIP to a real run is a content-only
# change to this file once that work lands.
#
# Forward-compat env (accepted now, wired up once the backend exists):
#   RIFT_FFI_LIB   path to librift_ffi.<so|dylib>, as produced by `build-rift.sh --ffi`
set -uo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=scripts/lib.sh
source "$SCRIPT_DIR/lib.sh"

: "${RIFT_FFI_LIB:=}"
echo "conformance-embedded: not yet implemented — tracked by #13/#14 (RIFT_FFI_LIB=${RIFT_FFI_LIB:-<unset>})" >&2
exit "$SKIP_EXIT_CODE"
