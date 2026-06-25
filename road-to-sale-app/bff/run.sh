#!/usr/bin/env bash
# Run the BFF locally on port 8089, pointed at the Core API.
set -euo pipefail
cd "$(dirname "$0")"
export PORT="${PORT:-8089}"
export CORE_BASE_URL="${CORE_BASE_URL:-http://localhost:8090}"
npx tsx src/server.ts
