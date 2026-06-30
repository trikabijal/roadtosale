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
#   - Node.js >= 20 and npm   (required)
#   - node_modules/ present (run ./build.sh first, or `npm ci`)
#   - Sibling module vehicle-feature-catalog/data/ present (the suite imports the
#     compiled catalog bundle — run from the full monorepo checkout)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MIN_NODE_MAJOR=20

cd "$SCRIPT_DIR"

# ── Prerequisite checks (fail loudly, before any real work) ─────────────────
if ! command -v node >/dev/null 2>&1; then
  echo "ERROR: node is required but not found. Install: https://nodejs.org (Node.js >= ${MIN_NODE_MAJOR})"
  exit 1
fi
if ! command -v npm >/dev/null 2>&1; then
  echo "ERROR: npm is required but not found. Install: it ships with Node.js (https://nodejs.org)"
  exit 1
fi

NODE_MAJOR="$(node -p 'process.versions.node.split(".")[0]')"
if [ "$NODE_MAJOR" -lt "$MIN_NODE_MAJOR" ]; then
  echo "ERROR: Node.js >= ${MIN_NODE_MAJOR} is required, found $(node --version). Install: https://nodejs.org"
  exit 1
fi

CATALOG_DATA_DIR="$SCRIPT_DIR/../vehicle-feature-catalog/data"
if [ ! -d "$CATALOG_DATA_DIR" ]; then
  echo "ERROR: vehicle-feature-catalog/data is required but not found at:"
  echo "       $CATALOG_DATA_DIR"
  echo "       Run from the full monorepo checkout (clone the whole repo, not just"
  echo "       road-to-sale-app/)."
  exit 1
fi

# ── Guard: node_modules must exist ──────────────────────────────────────────
if [ ! -d "node_modules" ]; then
  echo "ERROR: node_modules/ not found."
  echo "       Run ./build.sh first (or 'npm ci') to install dependencies."
  exit 1
fi

echo "=== Road to Sale App — running tests ==="
echo ""

# jest-expo's preset logs asynchronously AFTER the test environment tears down
# (the ExpoModulesCoreJSLogger / winter-fetch setup). That leaves jest with an
# open handle, so it exits non-zero even when every test passes — a false
# failure. `--forceExit` makes jest exit cleanly once tests finish. We skip it
# in watch mode, where it is incompatible.
case " $* " in
  *" --watch "*|*" --watchAll "*) npx jest "$@" ;;
  *)                              npx jest --forceExit "$@" ;;
esac
