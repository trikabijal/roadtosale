#!/usr/bin/env bash
set -euo pipefail

echo "=== Road to Sale App Build ==="

# Step 1: compile catalog YAML → JSON bundle
if [ -f "src/catalog/build-catalog.ts" ]; then
  echo "→ Compiling vehicle catalog..."
  npx ts-node src/catalog/build-catalog.ts
else
  echo "→ Catalog builder not yet present — skipping"
fi

# Step 2: Expo prebuild (generates ios/ and android/ native dirs)
echo "→ Running expo prebuild..."
npx expo prebuild --clean

echo "✅ Build complete"
