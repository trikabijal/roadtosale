#!/usr/bin/env bash
# Usage: ./run.sh [ios|android|web]
#
# Starts the Expo dev server for the given platform.
# Defaults to ios if no platform is supplied.
#
# Prerequisites:
#   - node_modules/ must exist (run ./build.sh first)
#   - For ios:     Xcode + iOS Simulator installed
#   - For android: Android Studio + an AVD or physical device connected
#   - For web:     a modern browser (Metro web bundler)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLATFORM="${1:-ios}"

cd "$SCRIPT_DIR"

# ── Guard: node_modules must exist ───────────────────────────────────────────
if [ ! -d "node_modules" ]; then
  echo "ERROR: node_modules/ not found."
  echo "       Run ./build.sh first to install dependencies."
  exit 1
fi

# ── Validate platform arg ────────────────────────────────────────────────────
case "$PLATFORM" in
  ios|android|web)
    ;;
  *)
    echo "ERROR: Unknown platform '${PLATFORM}'. Valid values: ios, android, web"
    exit 1
    ;;
esac

echo "=== Road to Sale App — starting Expo dev server (platform: $PLATFORM) ==="
echo ""
echo "  Press Ctrl+C to stop."
echo ""

npx expo start --"$PLATFORM"
