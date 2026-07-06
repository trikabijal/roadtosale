#!/usr/bin/env bash
#
# package.sh — Build, Developer-ID sign, notarize, staple, and DMG the JustTalk app for
# distribution to other people's Macs. This is the ONLY path Gatekeeper trusts off-device:
# the app can't be sandboxed (it needs Accessibility for the global
# hotkey and paste), so App Store / TestFlight are not options.
#
# Prerequisites (one-time, on this machine):
#   1. Apple Developer Program membership ($99/yr).
#   2. A "Developer ID Application: <Name> (<TEAMID>)" certificate in your login keychain
#      (Xcode ▸ Settings ▸ Accounts ▸ Manage Certificates ▸ + ▸ Developer ID Application).
#   3. A stored notarization credential profile:
#        xcrun notarytool store-credentials notary \
#          --apple-id you@example.com --team-id TEAMID --password <app-specific-password>
#      (app-specific password: appleid.apple.com ▸ Sign-In & Security ▸ App-Specific Passwords)
#
# Usage:
#   ./package.sh                 # build + sign + notarize + staple + DMG  (default)
#   SKIP_NOTARIZE=1 ./package.sh # build + sign + DMG only (local smoke test; NOT distributable)
#
# Env overrides:
#   DEV_ID="Developer ID Application: Name (TEAMID)"   # auto-detected if exactly one is present
#   NOTARY_PROFILE=notary                              # notarytool keychain profile name
#   OUT_DIR=dist-prod                                   # output directory
#
set -euo pipefail
cd "$(dirname "$0")"

OUT_DIR="${OUT_DIR:-dist-prod}"
NOTARY_PROFILE="${NOTARY_PROFILE:-notary}"
SCHEME="JustTalk"          # Xcode scheme/project name (internal)
APP_NAME="Just Talk"       # user-facing product name → "Just Talk.app" / .dmg
DERIVED="build-dist"

# --- 1. Resolve the Developer ID Application identity -------------------------------------------
if [[ -z "${DEV_ID:-}" ]]; then
  DEV_ID="$(security find-identity -v -p codesigning 2>/dev/null \
    | grep "Developer ID Application" | head -1 | sed -E 's/.*"(.*)"/\1/')"
fi
if [[ -z "${DEV_ID:-}" ]]; then
  echo "ERROR: no 'Developer ID Application' certificate found." >&2
  echo "  You have only development certs — those won't open on other Macs." >&2
  echo "  Get one: Xcode ▸ Settings ▸ Accounts ▸ Manage Certificates ▸ + ▸ Developer ID Application" >&2
  echo "  (requires a paid Apple Developer Program membership)." >&2
  exit 1
fi
TEAM_ID="$(sed -E 's/.*\(([A-Z0-9]+)\)$/\1/' <<< "$DEV_ID")"
echo "Signing identity: $DEV_ID  (team $TEAM_ID)"

# --- 2. Generate project + build Release, signed with the Developer ID + hardened runtime -------
command -v xcodegen >/dev/null || { echo "ERROR: xcodegen not installed (brew install xcodegen)"; exit 1; }
xcodegen generate
rm -rf "$DERIVED" "$OUT_DIR"
mkdir -p "$OUT_DIR"

xcodebuild -project "$SCHEME.xcodeproj" -scheme "$SCHEME" -configuration Release \
  -derivedDataPath "$DERIVED" \
  CODE_SIGN_STYLE=Manual \
  CODE_SIGN_IDENTITY="$DEV_ID" \
  DEVELOPMENT_TEAM="$TEAM_ID" \
  ENABLE_HARDENED_RUNTIME=YES \
  CODE_SIGN_INJECT_BASE_ENTITLEMENTS=NO \
  OTHER_CODE_SIGN_FLAGS="--timestamp" \
  build

APP_PATH="$DERIVED/Build/Products/Release/$APP_NAME.app"
[[ -d "$APP_PATH" ]] || { echo "ERROR: build produced no app at $APP_PATH"; exit 1; }

# Verify the signature + hardened runtime before notarizing (fails fast with a clear message).
codesign --verify --deep --strict --verbose=2 "$APP_PATH"
# Capture first (don't pipe): `grep -q` closes the pipe early, and under `set -o pipefail`
# that SIGPIPEs codesign into a non-zero exit → false "not enabled" failure.
CS_FLAGS="$(codesign -dv --verbose=4 "$APP_PATH" 2>&1)"
grep -q "flags=.*runtime" <<<"$CS_FLAGS" \
  || { echo "ERROR: hardened runtime not enabled — notarization would reject it"; exit 1; }

# The debug entitlement get-task-allow makes notarization reject the archive. A plain
# `xcodebuild build` injects it even in Release (only `archive` strips it); we disable that
# injection above, so assert it actually stuck.
APP_ENTS="$(codesign -d --entitlements - --xml "$APP_PATH" 2>/dev/null || true)"
grep -q "get-task-allow" <<<"$APP_ENTS" \
  && { echo "ERROR: get-task-allow entitlement present — notarization would reject it"; exit 1; } || true

# --- 3. Notarize (zip the .app, submit, wait) --------------------------------------------------
if [[ "${SKIP_NOTARIZE:-0}" != "1" ]]; then
  ZIP="$OUT_DIR/$APP_NAME.zip"
  /usr/bin/ditto -c -k --keepParent "$APP_PATH" "$ZIP"
  echo "Submitting to Apple notary service (this can take a few minutes)…"
  xcrun notarytool submit "$ZIP" --keychain-profile "$NOTARY_PROFILE" --wait
  rm -f "$ZIP"
  # Staple the ticket so the app validates OFFLINE on the recipient's Mac.
  xcrun stapler staple "$APP_PATH"
  xcrun stapler validate "$APP_PATH"
else
  echo "SKIP_NOTARIZE=1 — DMG will be signed but NOT notarized (local test only)."
fi

# --- 4. Build a DMG ----------------------------------------------------------------------------
DMG="$OUT_DIR/$APP_NAME.dmg"
STAGE="$OUT_DIR/dmg-stage"
rm -rf "$STAGE"; mkdir -p "$STAGE"
cp -R "$APP_PATH" "$STAGE/"
ln -s /Applications "$STAGE/Applications"          # drag-to-install affordance
hdiutil create -volname "$APP_NAME" -srcfolder "$STAGE" -ov -format UDZO "$DMG"
rm -rf "$STAGE"
# Sign the DMG too (recommended; notarize it as well for the cleanest first-open experience).
codesign --force --sign "$DEV_ID" --timestamp "$DMG"
if [[ "${SKIP_NOTARIZE:-0}" != "1" ]]; then
  xcrun notarytool submit "$DMG" --keychain-profile "$NOTARY_PROFILE" --wait
  xcrun stapler staple "$DMG"
fi

echo
echo "✓ Done: $DMG"
echo "  Send this DMG. Recipient: open it, drag JustTalk to Applications, launch, and grant"
echo "  Microphone + Accessibility on first run. Needs Apple Silicon + 8 GB RAM + macOS 14+."
