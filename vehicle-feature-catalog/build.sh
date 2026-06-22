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

MIN_PY_MAJOR=3
MIN_PY_MINOR=11

# --- prerequisite checks ----------------------------------------------------
info "Checking prerequisites"

if ! command -v python3 >/dev/null 2>&1; then
  err "python3 not found."
  err "  macOS:  brew install python@3.11   (or install from python.org)"
  err "  Linux:  sudo apt-get install python3 python3-venv python3-pip"
  exit 1
fi

PY_VER="$(python3 -c 'import sys; print("%d.%d.%d" % sys.version_info[:3])')"
PY_OK="$(python3 -c "import sys; print(1 if sys.version_info[:2] >= (${MIN_PY_MAJOR}, ${MIN_PY_MINOR}) else 0)")"
if [ "$PY_OK" != "1" ]; then
  err "python3 is ${PY_VER}; this module needs >= ${MIN_PY_MAJOR}.${MIN_PY_MINOR}."
  err "  macOS:  brew install python@3.11"
  err "  Linux:  sudo apt-get install python3.11 python3.11-venv"
  exit 1
fi
ok "python3 ${PY_VER}"

if ! command -v node >/dev/null 2>&1; then
  err "node not found."
  err "  macOS:  brew install node   (or use nvm: https://github.com/nvm-sh/nvm)"
  err "  Linux:  see https://nodejs.org/en/download/package-manager"
  exit 1
fi
ok "node $(node --version)"

if ! command -v npm >/dev/null 2>&1; then
  err "npm not found (it ships with Node — reinstall Node)."
  exit 1
fi
ok "npm $(npm --version)"

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
