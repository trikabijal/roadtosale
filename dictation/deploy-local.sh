#!/usr/bin/env bash
#
# deploy-local.sh — "Local deploy": build Just Talk (Release) and install it as the
# daily-driver app on this Mac.
#
# Usage:
#   ./deploy-local.sh              Install into /Applications (needs write access; may sudo)
#   ./deploy-local.sh --user       Install into ~/Applications (no admin rights needed)
#
# What it does:
#   1. Builds the JustTalk macOS app in Release config (via build.sh).
#   2. Copies JustTalk.app into the chosen Applications folder, replacing any prior copy.
#   3. Prints the first-launch permission + Gatekeeper notes.
#
# Gatekeeper / "unsigned app" caveat:
#   This local build is signed with a development (or ad-hoc) identity, NOT a notarized
#   Developer ID. On THIS Mac (where it was built) it launches normally. If you copy it to
#   ANOTHER Mac, Gatekeeper will block it with "JustTalk can't be opened" — bypass with a
#   right-click → Open the first time, or run:
#       xattr -dr com.apple.quarantine "/Applications/Just Talk.app"
#   Real distribution to other machines needs a paid Developer ID + notarization.
#
set -euo pipefail
cd "$(dirname "$0")"

DEST_DIR="/Applications"
if [[ "${1:-}" == "--user" ]]; then
  DEST_DIR="$HOME/Applications"
  mkdir -p "$DEST_DIR"
fi

# Build in Release (CONFIG is consumed by build.sh).
CONFIG=Release ./build.sh macos

APP=$(find "build/Build/Products/Release" -maxdepth 1 -name "*.app" | head -1)
if [[ -z "${APP:-}" ]]; then
  echo "✗ Build product not found." >&2
  exit 1
fi

DEST="$DEST_DIR/$(basename "$APP")"
echo "▶ Installing $(basename "$APP") to $DEST…"

# /Applications usually needs admin rights; ~/Applications does not.
if [[ -w "$DEST_DIR" ]]; then
  rm -rf "$DEST"
  cp -R "$APP" "$DEST"
else
  echo "  (need admin rights to write to $DEST_DIR — you may be prompted for your password)"
  sudo rm -rf "$DEST"
  sudo cp -R "$APP" "$DEST"
fi

echo "✓ Installed: $DEST"
echo
echo "First launch:"
echo "  • Grant Microphone + Accessibility in System Settings → Privacy & Security."
echo "    (Accessibility lets Fn paste text and suppresses the emoji picker.)"
echo "  • If macOS says the app \"can't be opened\" (Gatekeeper), right-click the app → Open,"
echo "    or run: xattr -dr com.apple.quarantine \"$DEST\""
echo
echo "Launch it now with:  open \"$DEST\""
