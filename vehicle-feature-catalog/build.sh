#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

# Python
python3 -m pip install -e . --quiet
python3 -m pytest tests/python -q

# TS
npm install --silent
npm run test --silent
npm run build --silent
