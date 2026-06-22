#!/usr/bin/env bash
#
# build.sh — Build the Just Talk app from a clean clone.
#
# Usage:
#   ./build.sh                 Build the macOS "JustTalk" app (Release) into ./build  (default)
#   ./build.sh macos           Same as above (explicit)
#   ./build.sh install         Build macOS app and copy it into /Applications
#   ./build.sh ios             Build the (paused) iOS container app for the Simulator
#
# Environment:
#   CONFIG=Debug|Release       Build configuration for the macOS app (default: Release)
#   DEVELOPMENT_TEAM=XXXXXXXXXX Override the signing team. Set for a stable signature so
#                              macOS keeps Mic / Accessibility / Input-Monitoring grants
#                              across rebuilds. If unset, project.yml's pinned identity is
#                              used; override with an empty string ("") to force ad-hoc.
#
# What it does:
#   1. Verifies macOS + Xcode + xcodegen are present.
#   2. Generates JustTalk.xcodeproj from project.yml via xcodegen.
#   3. Builds the requested scheme with xcodebuild into ./build (a derived-data dir).
#
# Notes:
#   - The iOS targets (DictationContainerApp + DictationKeyboard) are PAUSED and secondary.
#     On this branch the DictationKeyboard/*.swift files are diagnostic stubs, so the
#     extension may not build meaningfully. See docs/build.md.
#
set -euo pipefail
cd "$(dirname "$0")"

# ---- Configuration ---------------------------------------------------------
PROJECT="JustTalk.xcodeproj"
DERIVED="build"
CONFIG="${CONFIG:-Release}"
TARGET="${1:-macos}"

# ---- Prerequisite checks ---------------------------------------------------
if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "✗ This app builds only on macOS (uname is '$(uname -s)')." >&2
  exit 1
fi

if ! command -v xcodebuild >/dev/null 2>&1; then
  echo "✗ xcodebuild not found. Install Xcode from the App Store, then run:" >&2
  echo "    sudo xcode-select -s /Applications/Xcode.app/Contents/Developer" >&2
  exit 1
fi

if ! command -v xcodegen >/dev/null 2>&1; then
  echo "✗ xcodegen not found. Install it with: brew install xcodegen" >&2
  exit 1
fi

# ---- Generate the Xcode project --------------------------------------------
echo "▶ Generating $PROJECT from project.yml…"
xcodegen generate >/dev/null

# ---- Build -----------------------------------------------------------------
build_macos() {
  echo "▶ Building JustTalk (macOS, $CONFIG)…"
  if [[ -n "${DEVELOPMENT_TEAM:-}" ]]; then
    # Stable, team-based signing — TCC permissions persist across rebuilds.
    xcodebuild -project "$PROJECT" -scheme "JustTalk" -configuration "$CONFIG" \
      -derivedDataPath "$DERIVED" \
      DEVELOPMENT_TEAM="$DEVELOPMENT_TEAM" CODE_SIGN_STYLE=Automatic \
      -allowProvisioningUpdates build
  elif [[ "${DEVELOPMENT_TEAM+set}" == "set" ]]; then
    # DEVELOPMENT_TEAM explicitly set to "" → force ad-hoc signing.
    xcodebuild -project "$PROJECT" -scheme "JustTalk" -configuration "$CONFIG" \
      -derivedDataPath "$DERIVED" \
      CODE_SIGN_IDENTITY="-" CODE_SIGNING_REQUIRED=NO CODE_SIGNING_ALLOWED=NO build
  else
    # Default: use the signing identity pinned in project.yml.
    xcodebuild -project "$PROJECT" -scheme "JustTalk" -configuration "$CONFIG" \
      -derivedDataPath "$DERIVED" build
  fi

  local app
  app=$(find "$DERIVED/Build/Products/$CONFIG" -maxdepth 1 -name "*.app" | head -1)
  if [[ -z "${app:-}" ]]; then
    echo "✗ Build product not found under $DERIVED/Build/Products/$CONFIG." >&2
    exit 1
  fi
  echo "✓ Built: $app"
  BUILT_APP="$app"
}

build_ios() {
  echo "▶ Building DictationContainerApp (iOS Simulator)…"
  echo "  Note: the iOS line is PAUSED. The keyboard extension may not build on this"
  echo "  branch (diagnostic stubs). Continuing with the container app target…"
  xcodebuild -project "$PROJECT" -scheme "DictationContainerApp" -configuration Debug \
    -derivedDataPath build-ios \
    -destination 'generic/platform=iOS Simulator' \
    CODE_SIGNING_REQUIRED=NO CODE_SIGNING_ALLOWED=NO build
  echo "✓ iOS container app built into build-ios/."
}

case "$TARGET" in
  macos)
    build_macos
    ;;
  install)
    build_macos
    DEST="/Applications/$(basename "$BUILT_APP")"
    echo "▶ Installing to $DEST…"
    rm -rf "$DEST"
    cp -R "$BUILT_APP" "$DEST"
    echo "✓ Installed."
    echo "  First launch: grant Microphone + Accessibility in"
    echo "  System Settings → Privacy & Security (Accessibility lets Fn paste & suppresses the emoji picker)."
    ;;
  ios)
    build_ios
    ;;
  *)
    echo "✗ Unknown target '$TARGET'. Use: macos | install | ios" >&2
    exit 1
    ;;
esac
