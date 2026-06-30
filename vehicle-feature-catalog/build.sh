#!/usr/bin/env bash
#
# build.sh — Build the vehicle-feature-catalog module from a fresh clone.
#
# What it does (idempotent, safe to re-run):
#   1. Checks prerequisites (python3 >= 3.11, node, npm) and prints install hints.
#   2. Creates/activates a local virtualenv at ./.venv.
#   3. Installs the Python package editable, including the [scrapers] extra
#      (requests / beautifulsoup4 / pdfplumber) and [dev] extra (pytest / mypy).
#   4. Installs Node deps (npm ci when a lockfile exists, else npm install).
#   5. Builds the TypeScript dist/ (tsc).
#
# Usage:
#   ./build.sh            # full build (Python venv + TS dist)
#
# Artifacts:
#   ./.venv               local Python virtualenv (gitignored)
#   ./dist                compiled TypeScript output (gitignored)
#
# No personal paths, no network beyond pip/npm registries. See docs/build.md.
#
set -euo pipefail
cd "$(dirname "$0")"

# --- pretty logging ---------------------------------------------------------
info()  { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
ok()    { printf '\033[1;32m  ✓\033[0m %s\n' "$*"; }
err()   { printf '\033[1;31m  ✗\033[0m %s\n' "$*" >&2; }

# --- prerequisite checks ----------------------------------------------------
# Shared, DRY guards. Each fails loudly (ERROR: ... Install: ...) and exits 1
# before any real work, so nothing dies deep inside pip/npm.
# shellcheck source=scripts/prereqs.sh disable=SC1091
source "$(dirname "$0")/scripts/prereqs.sh"

info "Checking prerequisites"
require_python_version 3 11
ok "python3 ${_VFC_PYTHON_VERSION}"
require_node_version 20
ok "node ${_VFC_NODE_VERSION}"
require_npm_version 10
ok "npm ${_VFC_NPM_VERSION}"

# --- Python: venv + editable install ---------------------------------------
info "Setting up Python virtualenv (./.venv)"
if [ ! -d .venv ]; then
  python3 -m venv .venv
  ok "created .venv"
else
  ok ".venv already present"
fi
# shellcheck disable=SC1091
source .venv/bin/activate

info "Upgrading pip"
python -m pip install --upgrade pip --quiet

info "Installing vehicle-feature-catalog (editable) with [scrapers] + [dev] extras"
# Editable install of the core library plus the scraper pipeline deps and dev
# tooling. The core catalog only needs PyYAML; the extras add the seeding +
# test/type-check toolchain.
python -m pip install -e '.[scrapers,dev]' --quiet
ok "installed vehicle_feature_catalog (editable)"

# --- TypeScript: deps + build ----------------------------------------------
info "Installing Node dependencies"
if [ -f package-lock.json ]; then
  npm ci
else
  npm install
fi
ok "Node deps installed"

info "Building TypeScript (npm run build → dist/)"
npm run build
ok "TypeScript built → dist/"

echo
ok "Build complete. Next: ./test.sh   |   ./run.sh   |   ./deploy-local.sh"
