#!/usr/bin/env bash
# Usage: ./build.sh [dev|prod]
#
# Installs npm deps, compiles catalog bundle.json, compiles the cue pack JSON,
# then runs expo prebuild to generate the Xcode and Android Gradle projects
# from app.json plugins.
#
# Steps
#   1. npm install
#   2. Compile vehicle catalog YAML → src/catalog/bundle.json
#   3. Compile cue pack YAML       → src/cue-packs/road-to-sale-v1.json
#   4. expo prebuild --clean       → ios/ and android/ native project trees

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ENV="${1:-local}"

cd "$SCRIPT_DIR"

echo "=== Road to Sale App Build (env: $ENV) ==="

# ── Step 1: install dependencies ────────────────────────────────────────────
echo ""
echo "→ [1/4] Installing npm dependencies..."
npm install

# ── Step 2: compile vehicle catalog ─────────────────────────────────────────
echo ""
echo "→ [2/4] Compiling vehicle catalog (YAML → src/catalog/bundle.json)..."
if [ -f "src/catalog/build-catalog.ts" ]; then
  npx ts-node --project src/catalog/tsconfig.build.json src/catalog/build-catalog.ts
else
  echo "    WARNING: src/catalog/build-catalog.ts not found — skipping catalog compile."
fi

# ── Step 3: compile cue pack ─────────────────────────────────────────────────
echo ""
echo "→ [3/4] Compiling cue pack (YAML → src/cue-packs/road-to-sale-v1.json)..."
if [ -f "src/cue-packs/build-cue-pack.ts" ]; then
  npx ts-node --project src/catalog/tsconfig.build.json src/cue-packs/build-cue-pack.ts
else
  echo "    WARNING: src/cue-packs/build-cue-pack.ts not found — skipping cue pack compile."
fi

# ── Step 4: expo prebuild ────────────────────────────────────────────────────
echo ""
echo "→ [4/4] Running expo prebuild --clean (generates ios/ and android/ projects)..."
npx expo prebuild

# ── Summary ──────────────────────────────────────────────────────────────────
echo ""
echo "=== Build complete (env: $ENV) ==="
echo ""
echo "  Artifacts:"
echo "    src/catalog/bundle.json          — vehicle catalog bundle"
echo "    src/cue-packs/road-to-sale-v1.json — compiled cue pack"
echo "    ios/                              — Xcode project (open ios/*.xcworkspace)"
echo "    android/                          — Gradle project (open in Android Studio)"
echo ""
echo "  Next steps:"
echo "    Run on device/simulator : ./run.sh [ios|android]"
echo "    Run tests               : ./test.sh"
