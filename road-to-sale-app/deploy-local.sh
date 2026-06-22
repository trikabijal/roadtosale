#!/usr/bin/env bash
# deploy-local.sh — build + install + run a local dev build on THIS machine
#
# Usage:
#   ./deploy-local.sh ios [extra expo run:ios args...]
#   ./deploy-local.sh android [extra expo run:android args...]
#
# "Local deploy" here means: compile the native app and install + launch it on
# a connected device or simulator/emulator on this machine. This is NOT an
# app-store / TestFlight / Play Store deployment.
#
# What it does:
#   1. Verify prerequisites (Node, and the platform toolchain).
#   2. Generate native project (expo prebuild) if the platform dir is missing.
#      The voice config plugin (plugins/withVoiceModule.ts) wires the native
#      module into the generated project during prebuild.
#   3. Build, install, and launch via:
#         ios     → npx expo run:ios
#         android → npx expo run:android
#      Extra args are passed straight through, e.g.:
#         ./deploy-local.sh ios --device            # pick a physical device
#         ./deploy-local.sh ios --configuration Release
#         ./deploy-local.sh android --variant release
#
# NOTE on generated native folders:
#   ios/ and android/ are gitignored EXCEPT the hand-authored native module
#   source (ios/VoiceModule/, android/.../voice/). `expo prebuild` fills in the
#   rest of the native tree around those sources. Re-run prebuild with --clean
#   (see ./build.sh history) if the generated tree gets into a bad state.
#
# Prerequisites:
#   ios:
#     - macOS with Xcode (full app, not just CLT) + an iOS Simulator, or a
#       connected/provisioned iPhone for --device runs.
#     - CocoaPods (Expo installs pods automatically; have Ruby/CocoaPods ready).
#   android:
#     - Android SDK (Android Studio) with ANDROID_HOME / ANDROID_SDK_ROOT set,
#       a JDK, and a running emulator (AVD) or a connected device with USB
#       debugging enabled.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

PLATFORM="${1:-}"
if [ -z "$PLATFORM" ]; then
  echo "ERROR: missing platform. Usage: ./deploy-local.sh [ios|android] [extra args...]"
  exit 1
fi
shift || true  # remaining args pass through to expo run:*

# ── Prerequisite checks ─────────────────────────────────────────────────────
if ! command -v node >/dev/null 2>&1; then
  echo "ERROR: node not found. Install Node.js >= 20 (https://nodejs.org)."
  exit 1
fi
if [ ! -d "node_modules" ]; then
  echo "ERROR: node_modules/ not found. Run ./build.sh first."
  exit 1
fi

case "$PLATFORM" in
  ios)
    if [ "$(uname)" != "Darwin" ]; then
      echo "ERROR: iOS builds require macOS with Xcode."
      exit 1
    fi
    if ! command -v xcodebuild >/dev/null 2>&1; then
      echo "ERROR: xcodebuild not found. Install Xcode from the App Store and run:"
      echo "       sudo xcode-select -s /Applications/Xcode.app/Contents/Developer"
      exit 1
    fi
    ;;
  android)
    if [ -z "${ANDROID_HOME:-}" ] && [ -z "${ANDROID_SDK_ROOT:-}" ]; then
      echo "WARNING: ANDROID_HOME / ANDROID_SDK_ROOT not set."
      echo "         Install Android Studio + SDK and export ANDROID_HOME."
    fi
    ;;
  *)
    echo "ERROR: Unknown platform '${PLATFORM}'. Valid values: ios, android"
    exit 1
    ;;
esac

echo "=== Road to Sale App — local deploy (platform: $PLATFORM) ==="

# ── Step 1: prebuild native project if missing ──────────────────────────────
NATIVE_DIR="$SCRIPT_DIR/$PLATFORM"
NEEDS_PREBUILD=0
if [ ! -f "$NATIVE_DIR/Podfile" ] && [ ! -f "$NATIVE_DIR/build.gradle" ] && [ ! -f "$NATIVE_DIR/settings.gradle" ]; then
  NEEDS_PREBUILD=1
fi

if [ "$NEEDS_PREBUILD" -eq 1 ]; then
  echo ""
  echo "→ Native $PLATFORM project not found — running expo prebuild..."
  npx expo prebuild --platform "$PLATFORM"
else
  echo ""
  echo "→ Native $PLATFORM project already present — skipping prebuild."
  echo "  (To regenerate cleanly: npx expo prebuild --platform $PLATFORM --clean)"
fi

# ── Step 2: build + install + launch ────────────────────────────────────────
echo ""
echo "→ Building, installing, and launching on $PLATFORM..."
npx expo run:"$PLATFORM" "$@"

echo ""
echo "=== Done. The dev build is installed and running on $PLATFORM. ==="
echo "    For subsequent JS-only changes, iterate with: ./run.sh $PLATFORM"
