# native-prereqs.sh — shared prerequisite checks for native device/simulator runs
#
# This file is SOURCED (not executed) by run.sh and deploy-local.sh to keep the
# platform toolchain checks DRY. Every check fails loudly with a clear
# "ERROR: <tool> is required but not found. Install: <command>" message + exit 1,
# so a missing prerequisite never turns into a confusing deep failure later.
#
# Functions:
#   require_node          — node present and >= MIN_NODE_MAJOR (default 20)
#   require_node_modules  — node_modules/ installed (i.e. ./build.sh was run)
#   require_ios_toolchain — macOS + Xcode + an iOS Simulator + CocoaPods
#   require_android_toolchain — Android SDK + a JDK + an emulator (AVD)
#
# Each function expects SCRIPT_DIR to be set by the caller.

MIN_NODE_MAJOR="${MIN_NODE_MAJOR:-20}"

require_node() {
  if ! command -v node >/dev/null 2>&1; then
    echo "ERROR: node is required but not found. Install: https://nodejs.org (Node.js >= ${MIN_NODE_MAJOR})"
    exit 1
  fi
  local node_major
  node_major="$(node -p 'process.versions.node.split(".")[0]')"
  if [ "$node_major" -lt "$MIN_NODE_MAJOR" ]; then
    echo "ERROR: Node.js >= ${MIN_NODE_MAJOR} is required, found $(node --version). Install: https://nodejs.org"
    exit 1
  fi
}

require_node_modules() {
  if [ ! -d "$SCRIPT_DIR/node_modules" ]; then
    echo "ERROR: node_modules/ is required but not found. Install: run ./build.sh first (or 'npm ci')"
    exit 1
  fi
}

# iOS: macOS + Xcode (full app) + an available Simulator + CocoaPods.
require_ios_toolchain() {
  if [ "$(uname)" != "Darwin" ]; then
    echo "ERROR: iOS builds require macOS. Install: run on a Mac with Xcode (iOS builds are not supported on this OS)."
    exit 1
  fi
  if ! command -v xcodebuild >/dev/null 2>&1; then
    echo "ERROR: xcodebuild is required but not found. Install: Xcode from the App Store, then run 'sudo xcode-select -s /Applications/Xcode.app/Contents/Developer'"
    exit 1
  fi
  # xcrun simctl ships with Xcode; an installed Simulator runtime is needed.
  if ! command -v xcrun >/dev/null 2>&1; then
    echo "ERROR: xcrun is required but not found. Install: Xcode from the App Store (then 'sudo xcode-select -s /Applications/Xcode.app/Contents/Developer')"
    exit 1
  fi
  if ! xcrun simctl list devices available 2>/dev/null | grep -qi 'iphone'; then
    echo "ERROR: no iOS Simulator is available. Install: open Xcode > Settings > Components and add an iOS Simulator runtime, or create one with 'xcrun simctl create'."
    exit 1
  fi
  if ! command -v pod >/dev/null 2>&1; then
    echo "ERROR: CocoaPods (pod) is required but not found. Install: 'sudo gem install cocoapods' (or 'brew install cocoapods')"
    exit 1
  fi
}

# Android: Android SDK (ANDROID_HOME/ANDROID_SDK_ROOT) + a JDK + an emulator (AVD).
require_android_toolchain() {
  local sdk_root="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -z "$sdk_root" ]; then
    echo "ERROR: Android SDK is required but ANDROID_HOME / ANDROID_SDK_ROOT is not set. Install: Android Studio + SDK, then 'export ANDROID_HOME=\$HOME/Library/Android/sdk'"
    exit 1
  fi
  if [ ! -d "$sdk_root" ]; then
    echo "ERROR: Android SDK directory not found at '$sdk_root'. Install: Android Studio + SDK, then point ANDROID_HOME at the SDK location."
    exit 1
  fi
  if ! command -v java >/dev/null 2>&1; then
    echo "ERROR: a JDK (java) is required but not found. Install: a JDK 17 (e.g. 'brew install --cask temurin@17') and ensure 'java' is on PATH"
    exit 1
  fi
  # An emulator binary + at least one AVD, OR a connected device, is needed.
  local emulator_bin="$sdk_root/emulator/emulator"
  local have_avd=0
  if [ -x "$emulator_bin" ] && [ -n "$("$emulator_bin" -list-avds 2>/dev/null)" ]; then
    have_avd=1
  fi
  local have_device=0
  if command -v adb >/dev/null 2>&1 && adb devices 2>/dev/null | grep -qE 'device$'; then
    have_device=1
  fi
  if [ "$have_avd" -eq 0 ] && [ "$have_device" -eq 0 ]; then
    echo "ERROR: no Android emulator (AVD) or connected device found. Install: create an AVD in Android Studio (Device Manager), or connect a device with USB debugging enabled."
    exit 1
  fi
}
