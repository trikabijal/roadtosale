#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
npm install --silent
npm run test --silent
npm run build --silent
echo "iOS native module: placeholder only — wiring in future PRD (see OQ1)"
echo "Android stub: placeholder only — wiring in future PRD"
