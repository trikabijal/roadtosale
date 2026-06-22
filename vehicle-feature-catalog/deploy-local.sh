#!/usr/bin/env bash
#
# deploy-local.sh — "Deploy" this library into the local environment.
#
# This module is a LIBRARY, not a server. There is no service to start and no
# cloud to push to. "Local deploy" therefore means: make the catalog importable
# by local consumers (e.g. the voice-engine lab) in the active environment.
#
# What it does:
#   1. Activates ./.venv (run ./build.sh first if missing).
#   2. Editable-installs the Python package so consumers can `import
#      vehicle_feature_catalog` against the live source tree.
#   3. Builds the TypeScript dist/ so TS consumers can import the package.
#   4. Verifies the Python import actually works.
#
# Usage:
#   ./deploy-local.sh
#
# There is NO server. Consumers depend on this package by importing it from the
# same checkout / environment. See docs/build.md.
#
set -euo pipefail
cd "$(dirname "$0")"

info()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
ok()    { printf '\033[1;32m  ✓\033[0m %s\n' "$*"; }
err()   { printf '\033[1;31m  ✗\033[0m %s\n' "$*" >&2; }

if [ ! -d .venv ]; then
  err ".venv not found. Run ./build.sh first."
  exit 1
fi
# shellcheck disable=SC1091
source .venv/bin/activate

info "Editable-installing vehicle_feature_catalog into the active environment"
python -m pip install -e '.[scrapers,dev]' --quiet
ok "editable install complete"

info "Building TypeScript dist/ for TS consumers"
if [ ! -d node_modules ]; then
  if [ -f package-lock.json ]; then npm ci; else npm install; fi
fi
npm run build
ok "TypeScript dist/ built"

info "Verifying the Python import works"
python -c "import vehicle_feature_catalog; print('import OK:', vehicle_feature_catalog.__name__)"
ok "Python import verified"

echo
ok "Local deploy complete."
echo "  • Python consumers: import vehicle_feature_catalog (this venv / environment)"
echo "  • TS consumers:     import from dist/index.js"
echo "  • There is no server — this is a library."
