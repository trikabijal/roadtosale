#!/usr/bin/env bash
#
# Build and launch the macOS Dictation app locally.
#
set -euo pipefail
cd "$(dirname "$0")"

./build.sh

APP=$(find build/Build/Products/Release -maxdepth 1 -name "*.app" | head -1)
if [[ -z "${APP:-}" ]]; then
  echo "✗ Build product not found." >&2
  exit 1
fi

echo "▶ Launching $APP…"
open "$APP"
echo "✓ Running in the menu bar. Hold Fn (Globe) to dictate."
