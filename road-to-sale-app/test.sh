#!/usr/bin/env bash
# Usage: ./test.sh [--watch] [--coverage] [<jest-args>...]
#
# Runs the Jest test suite using the jest-expo preset.
# All arguments are passed through to Jest directly, e.g.:
#
#   ./test.sh                   # run all tests once
#   ./test.sh --watch           # watch mode (re-runs on file save)
#   ./test.sh --coverage        # generate coverage report
#   ./test.sh src/api           # run tests under src/api/ only

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

cd "$SCRIPT_DIR"

# ── Guard: node_modules must exist ───────────────────────────────────────────
if [ ! -d "node_modules" ]; then
  echo "ERROR: node_modules/ not found."
  echo "       Run ./build.sh first to install dependencies."
  exit 1
fi

echo "=== Road to Sale App — running tests ==="
echo ""

npx jest "$@"
