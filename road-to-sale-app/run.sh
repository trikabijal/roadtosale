#!/usr/bin/env bash
# run.sh — start the Expo dev server (Metro) for the Road to Sale App
#
# Usage:
#   ./run.sh [ios|android|web]      # default: ios
#
# This starts the Metro/Expo dev server and (for ios/android) attempts to open
# the app on a simulator/emulator. It serves the JS bundle to an ALREADY
# INSTALLED build of the app — it does NOT compile native code.
#
#   - For day-to-day JS/TS development against an installed dev build, use this.
#   - To compile + install the native app on a device/simulator the first time
#     (or after changing native modules), use ./deploy-local.sh instead.
#
# IMPORTANT — native modules vs Expo Go:
#   This app ships a custom native voice module (plugins/withVoiceModule.ts).
#   Expo Go CANNOT load custom native modules, so the audio/voice features will
#   not work under Expo Go. Use a DEV BUILD on a simulator/device — build it
#   once with ./deploy-local.sh, then iterate with ./run.sh.
#
# Prerequisites:
#   - node_modules/ present (run ./build.sh first)
#   - ios     : macOS + Xcode + an iOS Simulator, and a dev build installed
#   - android : Android SDK + an emulator (AVD) or connected device, dev build installed
#   - web     : a modern browser (Metro web bundler)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLATFORM="${1:-ios}"

cd "$SCRIPT_DIR"

# ── Guard: node_modules must exist ──────────────────────────────────────────
if [ ! -d "node_modules" ]; then
  echo "ERROR: node_modules/ not found."
  echo "       Run ./build.sh first to install dependencies."
  exit 1
fi

# ── Validate platform arg ───────────────────────────────────────────────────
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
echo "  Targeting an installed dev build. If no dev build is installed yet,"
echo "  run:  ./deploy-local.sh $PLATFORM"
echo ""
echo "  Press Ctrl+C to stop."
echo ""

npx expo start --"$PLATFORM"
