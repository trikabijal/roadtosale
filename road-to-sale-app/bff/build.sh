#!/usr/bin/env bash
# Install deps and type-check the BFF.
set -euo pipefail
cd "$(dirname "$0")"
npm install
npx tsc --noEmit
