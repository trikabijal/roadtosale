#!/usr/bin/env bash
# Run the BFF test suite.
set -euo pipefail
cd "$(dirname "$0")"
npx vitest run
