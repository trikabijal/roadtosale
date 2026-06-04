#!/usr/bin/env bash
#
# Build the macOS Dictation app.
#
#   ./build.sh            Build a Release .app into ./build
#   ./build.sh install    Build and copy the app into /Applications
#
# Signing:
#   - Set DEVELOPMENT_TEAM=XXXXXXXXXX for a stable signature (Accessibility/Mic
#     permissions then persist across rebuilds).
#   - Unset → ad-hoc signing (works locally, but macOS re-prompts for permissions
#     after each rebuild because the signature changes).
#
set -euo pipefail
cd "$(dirname "$0")"

SCHEME="DictationApp"
CONFIG="Release"
DERIVED="build"

if ! command -v xcodegen >/dev/null 2>&1; then
  echo "✗ xcodegen not found. Install it with: brew install xcodegen" >&2
  exit 1
fi

echo "▶ Generating Xcode project…"
xcodegen generate >/dev/null

echo "▶ Building $SCHEME ($CONFIG)…"
if [[ -n "${DEVELOPMENT_TEAM:-}" ]]; then
  xcodebuild -project DictationApp.xcodeproj -scheme "$SCHEME" -configuration "$CONFIG" \
    -derivedDataPath "$DERIVED" \
    DEVELOPMENT_TEAM="$DEVELOPMENT_TEAM" CODE_SIGN_STYLE=Automatic \
    -allowProvisioningUpdates build
else
  xcodebuild -project DictationApp.xcodeproj -scheme "$SCHEME" -configuration "$CONFIG" \
    -derivedDataPath "$DERIVED" \
    CODE_SIGN_IDENTITY="-" CODE_SIGNING_REQUIRED=NO CODE_SIGNING_ALLOWED=NO build
fi

APP=$(find "$DERIVED/Build/Products/$CONFIG" -maxdepth 1 -name "*.app" | head -1)
if [[ -z "${APP:-}" ]]; then
  echo "✗ Build product not found." >&2
  exit 1
fi
echo "✓ Built: $APP"

if [[ "${1:-}" == "install" ]]; then
  DEST="/Applications/$(basename "$APP")"
  echo "▶ Installing to $DEST…"
  rm -rf "$DEST"
  cp -R "$APP" "$DEST"
  echo "✓ Installed."
  echo "  First launch: grant Microphone + Accessibility in"
  echo "  System Settings → Privacy & Security (Accessibility lets Fn paste & suppresses the emoji picker)."
fi
