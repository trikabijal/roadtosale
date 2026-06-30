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
#   - Node.js >= 20 and node_modules/ present (run ./build.sh first)
#   - ios     : macOS + Xcode + an iOS Simulator + CocoaPods, and a dev build installed
#   - android : Android SDK + a JDK + an emulator (AVD) or connected device, dev build installed
#   - web     : a modern browser (Metro web bundler)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLATFORM="${1:-ios}"

cd "$SCRIPT_DIR"

# Shared, DRY prerequisite checks (require_node, require_node_modules, and the
# per-platform native toolchain checks).
# shellcheck source=scripts/native-prereqs.sh
. "$SCRIPT_DIR/scripts/native-prereqs.sh"

# ── Prerequisite checks (fail loudly, before any real work) ─────────────────
require_node
require_node_modules

# ── Validate platform arg + check its native toolchain ──────────────────────
case "$PLATFORM" in
  ios)
    require_ios_toolchain
    ;;
  android)
    require_android_toolchain
    ;;
  web)
    # Metro web bundler — no native toolchain required.
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
