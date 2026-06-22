#!/usr/bin/env bash
# test.sh — Road to Sale App test suite (Jest, jest-expo preset)
#
# Usage:
#   ./test.sh                   # run the full suite once
#   ./test.sh --watch           # watch mode (re-runs on file save)
#   ./test.sh --coverage        # generate a coverage report
#   ./test.sh src/api           # run tests under src/api/ only
#
# All arguments are passed straight through to Jest. Exits non-zero if any
# test fails (set -e + Jest's own exit code), so it is safe to gate on.
#
# The suite uses the jest-expo preset (configured in package.json) and covers
# the catalog, CRM, voice, DB, API, and session engines (~106 tests across 7
# test files).
#
# Prerequisites:
#   - node_modules/ present (run ./build.sh first, or `npm ci`)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

cd "$SCRIPT_DIR"

# ── Guard: node_modules must exist ──────────────────────────────────────────
if [ ! -d "node_modules" ]; then
  echo "ERROR: node_modules/ not found."
  echo "       Run ./build.sh first (or 'npm ci') to install dependencies."
  exit 1
fi

echo "=== Road to Sale App — running tests ==="
echo ""

npx jest "$@"
