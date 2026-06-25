#!/usr/bin/env bash
# Road to Sale — headless full-stack E2E.
#
# One command to boot a real Postgres (embedded, NO Docker) + Java Core + Node
# BFF, drive the full session lifecycle through the BFF over HTTP, and assert
# the data was persisted in Postgres. Exits non-zero on any failure.
set -euo pipefail

QA_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$QA_DIR"

# Install deps if missing (embedded-postgres + pg + vitest).
if [ ! -d node_modules ] || [ ! -d node_modules/embedded-postgres ]; then
  echo "[test.sh] installing qa dependencies..."
  npm install
fi

# Ensure the BFF has its deps too (the harness spawns `npx tsx src/server.ts`).
if [ ! -d "$QA_DIR/../bff/node_modules" ]; then
  echo "[test.sh] installing bff dependencies..."
  (cd "$QA_DIR/../bff" && npm install)
fi

echo "[test.sh] running headless E2E (embedded Postgres + Core + BFF)..."
exec npx vitest run
