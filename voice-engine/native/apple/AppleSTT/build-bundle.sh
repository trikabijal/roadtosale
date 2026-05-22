#!/usr/bin/env bash
set -euo pipefail

# Build AppleSTT and wrap it as a proper .app bundle so macOS TCC can resolve
# Speech Recognition consent. The bare Mach-O with an embedded Info.plist is
# necessary but not sufficient on macOS 26: TCC keys consent off a bundle URL.

cd "$(dirname "$0")"

echo "[1/4] swift build -c release"
swift build -c release

BIN_SRC=".build/release/AppleSTT"
APP_DIR="AppleSTT.app"
CONTENTS_DIR="$APP_DIR/Contents"
MACOS_DIR="$CONTENTS_DIR/MacOS"

echo "[2/4] scaffold $APP_DIR"
rm -rf "$APP_DIR"
mkdir -p "$MACOS_DIR"
cp Info.plist "$CONTENTS_DIR/Info.plist"
cp "$BIN_SRC" "$MACOS_DIR/AppleSTT"
chmod +x "$MACOS_DIR/AppleSTT"

echo "[3/4] codesign $APP_DIR"
codesign -f -s - \
  --identifier com.auditpro.voiceengine.applestt \
  --timestamp=none \
  "$APP_DIR"

echo "[4/4] verify"
codesign -d -vv "$APP_DIR" 2>&1 | head -10

echo ""
echo "Bundle path: $(pwd)/$APP_DIR"
echo "Run binary:  $(pwd)/$APP_DIR/Contents/MacOS/AppleSTT"
echo ""
echo "Set this for the Python wrapper:"
echo "  export APPLE_STT_BIN=$(pwd)/$APP_DIR/Contents/MacOS/AppleSTT"
