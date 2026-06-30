#!/usr/bin/env bash
#
# test.sh — Run the full test suite (Python + TypeScript).
#
# What it does:
#   1. Activates ./.venv (run ./build.sh first if it doesn't exist).
#   2. Runs the Python suite with pytest  (tests/python — 52 tests).
#   3. Runs the TypeScript suite with vitest (tests/ts — 27 tests).
#   Exits non-zero if EITHER suite fails. Both suites always run so you see
#   all failures in one pass.
#
# Usage:
#   ./test.sh
#
# See docs/build.md.
#
set -euo pipefail
cd "$(dirname "$0")"

info()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
ok()    { printf '\033[1;32m  ✓\033[0m %s\n' "$*"; }
err()   { printf '\033[1;31m  ✗\033[0m %s\n' "$*" >&2; }

# --- prerequisite checks ----------------------------------------------------
# shellcheck source=scripts/prereqs.sh disable=SC1091
source "$(dirname "$0")/scripts/prereqs.sh"
require_python_version 3 11
require_node_version 20
require_npm_version 10

# --- activate venv ----------------------------------------------------------
if [ ! -d .venv ]; then
  err ".venv not found. Run ./build.sh first."
  exit 1
fi
# shellcheck disable=SC1091
source .venv/bin/activate

if [ ! -d node_modules ]; then
  err "node_modules not found. Run ./build.sh first."
  exit 1
fi

# Track failures across both suites so we run them all, then exit non-zero.
py_status=0
ts_status=0

info "Running Python tests (pytest)"
if python -m pytest tests/python; then
  ok "Python tests passed"
else
  py_status=$?
  err "Python tests FAILED (exit ${py_status})"
fi

info "Running TypeScript tests (vitest)"
if npm test --silent; then
  ok "TypeScript tests passed"
else
  ts_status=$?
  err "TypeScript tests FAILED (exit ${ts_status})"
fi

echo
if [ "$py_status" -ne 0 ] || [ "$ts_status" -ne 0 ]; then
  err "Test suite FAILED."
  exit 1
fi
ok "All tests passed."
