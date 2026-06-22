#!/usr/bin/env bash
#
# voice-engine/test.sh — run the full local test suite for the module.
#
# Runs:
#   1. Python lab pytest suite   (lab/tests, ~85 tests)
#   2. TS vitest suite           (tests/,    ~20 tests)
#
# Exits non-zero if EITHER suite fails. Run ./build.sh first (this script
# assumes lab/.venv exists and node_modules is installed).
#
# Usage:
#   ./test.sh            Run both suites.
#   ./test.sh -h|--help  Show this help.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

case "${1:-}" in
  -h|--help)
    sed -n '2,16p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 0
    ;;
  "") ;;
  *) echo "error: unknown argument '$1' (try --help)" >&2; exit 2 ;;
esac

log()  { printf '\033[1;34m[test]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[test][error]\033[0m %s\n' "$*" >&2; exit 1; }

VENV_DIR="$SCRIPT_DIR/lab/.venv"
[ -d "$VENV_DIR" ] || fail "lab/.venv not found. Run ./build.sh first."
[ -d "$SCRIPT_DIR/node_modules" ] || fail "node_modules not found. Run ./build.sh first."

RC=0

# ---------------------------------------------------------------------------
# 1. Python lab — pytest
# ---------------------------------------------------------------------------
log "Running Python lab tests (pytest)…"
# shellcheck disable=SC1091
source "$VENV_DIR/bin/activate"
if ( cd "$SCRIPT_DIR/lab" && pytest tests -q ); then
  log "  Python lab tests passed."
else
  RC=1
  printf '\033[1;31m[test][error]\033[0m Python lab tests FAILED.\n' >&2
fi
deactivate

# ---------------------------------------------------------------------------
# 2. TS skeleton — vitest
# ---------------------------------------------------------------------------
log "Running TS skeleton tests (vitest)…"
if npm run test --silent; then
  log "  TS tests passed."
else
  RC=1
  printf '\033[1;31m[test][error]\033[0m TS tests FAILED.\n' >&2
fi

if [ "$RC" -eq 0 ]; then
  log "All test suites passed."
else
  printf '\033[1;31m[test][error]\033[0m One or more test suites FAILED.\n' >&2
fi
exit "$RC"
