#!/usr/bin/env bash
#
# run.sh — Build and launch the macOS Just Talk app locally.
#
# Usage:
#   ./run.sh
#
# Builds the JustTalk macOS app (via build.sh) and opens the resulting .app. The app
# lives in the menu bar (no Dock icon). Hold your activation key to talk.
#
set -euo pipefail
cd "$(dirname "$0")"

CONFIG="${CONFIG:-Release}"

./build.sh macos

APP=$(find "build/Build/Products/$CONFIG" -maxdepth 1 -name "*.app" | head -1)
if [[ -z "${APP:-}" ]]; then
  echo "✗ Build product not found." >&2
  exit 1
fi

echo "▶ Launching $APP…"
open "$APP"
echo "✓ Running in the menu bar. Hold your activation key to talk."
