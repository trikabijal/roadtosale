#!/usr/bin/env bash
# build.sh — Road to Sale App build (JS/TS bundle prep)
#
# Usage:
#   ./build.sh [local|dev|prod]
#
# What it does (NO native prebuild by default — this is JS/TS prep only):
#   1. Verify prerequisites (Node >= 20, npm).
#   2. Install dependencies with `npm ci` (falls back to `npm install` if no lockfile).
#   3. Typecheck the TypeScript sources (`tsc --noEmit`).
#   4. Compile the vehicle catalog : vehicle-feature-catalog/data/*.yaml
#                                  → src/catalog/bundle.json
#   5. Compile the cue pack        : src/cue-packs/road-to-sale-v1.yaml
#                                  → src/cue-packs/road-to-sale-v1.json
#
# Native iOS/Android projects are NOT generated here. Those are produced on
# demand by ./deploy-local.sh (which runs `expo prebuild` + `expo run:*`).
# The generated ios/ and android/ trees are gitignored, so we deliberately
# keep them out of the default build to avoid polluting the working tree.
#
# Prerequisites:
#   - Node.js >= 20 and npm           (required)
#   - Sibling module vehicle-feature-catalog/data/ present in the repo (required
#     for the catalog compile step — this repo is a monorepo and the app reads
#     the catalog YAML from ../vehicle-feature-catalog)
#   - Xcode (iOS) / Android SDK       (NOT needed for this script — only for
#                                      ./deploy-local.sh device/simulator runs)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV="${1:-local}"
MIN_NODE_MAJOR=20

cd "$SCRIPT_DIR"

echo "=== Road to Sale App — build (env: $ENV) ==="

# ── Step 0: prerequisite checks ─────────────────────────────────────────────
echo ""
echo "→ [0/4] Checking prerequisites..."

if ! command -v node >/dev/null 2>&1; then
  echo "    ERROR: node not found. Install Node.js >= ${MIN_NODE_MAJOR} (https://nodejs.org)."
  exit 1
fi
if ! command -v npm >/dev/null 2>&1; then
  echo "    ERROR: npm not found. It ships with Node.js (https://nodejs.org)."
  exit 1
fi

NODE_MAJOR="$(node -p 'process.versions.node.split(".")[0]')"
if [ "$NODE_MAJOR" -lt "$MIN_NODE_MAJOR" ]; then
  echo "    ERROR: Node.js >= ${MIN_NODE_MAJOR} required, found $(node --version)."
  exit 1
fi
echo "    node $(node --version), npm $(npm --version) — OK"

CATALOG_DATA_DIR="$SCRIPT_DIR/../vehicle-feature-catalog/data"
if [ ! -d "$CATALOG_DATA_DIR" ]; then
  echo "    WARNING: vehicle-feature-catalog/data not found at:"
  echo "             $CATALOG_DATA_DIR"
  echo "             The catalog compile step (4/4) will fail. Clone the full monorepo."
fi

# ── Step 1: install dependencies ────────────────────────────────────────────
echo ""
echo "→ [1/4] Installing npm dependencies..."
if [ -f "package-lock.json" ]; then
  npm ci
else
  echo "    No package-lock.json found — falling back to 'npm install'."
  npm install
fi

# ── Step 2: typecheck ───────────────────────────────────────────────────────
echo ""
echo "→ [2/4] Typechecking (tsc --noEmit)..."
npx tsc --noEmit

# ── Step 3: compile cue pack ────────────────────────────────────────────────
echo ""
echo "→ [3/4] Compiling cue pack (YAML → src/cue-packs/road-to-sale-v1.json)..."
if [ -f "src/cue-packs/build-cue-pack.ts" ]; then
  # build-cue-pack.ts guards import.meta.url behind a runtime check, but the TS
  # compiler still needs a module setting that permits import.meta. Use esnext
  # module with node resolution for this single script.
  npx ts-node \
    --compiler-options '{"module":"esnext","moduleResolution":"node","esModuleInterop":true,"resolveJsonModule":true,"skipLibCheck":true,"target":"ES2020","types":["node"]}' \
    src/cue-packs/build-cue-pack.ts
else
  echo "    WARNING: src/cue-packs/build-cue-pack.ts not found — skipping cue pack compile."
fi

# ── Step 4: compile vehicle catalog ─────────────────────────────────────────
echo ""
echo "→ [4/4] Compiling vehicle catalog (YAML → src/catalog/bundle.json)..."
if [ -f "src/catalog/build-catalog.ts" ]; then
  npx ts-node --project src/catalog/tsconfig.build.json src/catalog/build-catalog.ts
else
  echo "    WARNING: src/catalog/build-catalog.ts not found — skipping catalog compile."
fi

# ── Summary ─────────────────────────────────────────────────────────────────
echo ""
echo "=== Build complete (env: $ENV) ==="
echo ""
echo "  Artifacts:"
echo "    src/cue-packs/road-to-sale-v1.json — compiled cue pack"
echo "    src/catalog/bundle.json            — vehicle catalog bundle"
echo ""
echo "  Next steps:"
echo "    Start the dev server          : ./run.sh [ios|android|web]"
echo "    Run tests                     : ./test.sh"
echo "    Build + run on a device/sim   : ./deploy-local.sh [ios|android]"
