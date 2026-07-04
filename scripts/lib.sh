#!/usr/bin/env bash
# Shared conventions for rift-conformance harness scripts (issue #15). Sourced, not executed.
#
# Provides:
#   find_bin <bin-name> <ENV_OVERRIDE_VAR>   — resolve a rift binary
#   require <cmd>                             — fail fast if a command is missing
#   run_layer <label> <cmd...>                — run one layer, record PASS/FAIL/SKIP
#   print_summary                             — print the combined summary; returns 1 iff any FAIL
#
# Skip convention: a layer command that wants to report SKIP (rather than pass/fail) exits with
# $SKIP_EXIT_CODE (77 — the autoconf/automake "skip" convention). Any other nonzero exit is FAIL.
set -uo pipefail

# Project root (parent of scripts/).
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

SKIP_EXIT_CODE=77

RESULTS=()

# find_bin <crate-binary-name> <env-override-var>
# Resolves a rift binary: explicit env override → $RIFT_BUILD_DIR → PATH.
# Prints the path on stdout, or exits with guidance on stderr.
find_bin() {
  local name="$1" override_var="$2" override="${!2:-}"
  local candidates=(
    "$override"
    "${RIFT_BUILD_DIR:-}/$name"
    "$(command -v "$name" 2>/dev/null || true)"
  )
  local c
  for c in "${candidates[@]}"; do
    if [ -n "$c" ] && [ -x "$c" ]; then printf '%s\n' "$c"; return 0; fi
  done
  echo "error: could not find '$name'." >&2
  echo "  Build it:   eval \"\$(scripts/build-rift.sh)\"   (sets RIFT_BUILD_DIR)" >&2
  echo "  Or set:     $override_var=/path/to/$name" >&2
  return 1
}

require() {
  command -v "$1" >/dev/null 2>&1 || { echo "error: '$1' is required but not installed." >&2; exit 1; }
}

# run_layer <label> <command...> — runs the command, records PASS/FAIL/SKIP into $RESULTS.
run_layer() {
  local label="$1"; shift
  echo "──────── $label ────────"
  "$@"
  local rc=$?
  if [ "$rc" -eq 0 ]; then
    RESULTS+=("PASS  $label")
  elif [ "$rc" -eq "$SKIP_EXIT_CODE" ]; then
    RESULTS+=("SKIP  $label")
  else
    RESULTS+=("FAIL  $label (exit $rc)")
  fi
  echo
}

# print_summary — prints the colored combined summary and returns 1 iff any layer FAILed
# (SKIP never counts as failure). Callers should `print_summary; exit $?`.
print_summary() {
  echo "════════ summary ════════"
  local fail=0 r
  for r in "${RESULTS[@]}"; do
    case "$r" in
      PASS*) printf '  \033[32m%s\033[0m\n' "$r" ;;
      SKIP*) printf '  \033[33m%s\033[0m\n' "$r" ;;
      FAIL*) printf '  \033[31m%s\033[0m\n' "$r"; fail=1 ;;
      *)     printf '  %s\n' "$r" ;;
    esac
  done
  echo
  if [ "$fail" = 0 ]; then
    echo "all layers passed (SKIPs are not failures)"
  else
    echo "one or more layers FAILed — see per-layer output above"
  fi
  return "$fail"
}
