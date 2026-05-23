#!/bin/bash
#
# One-time setup to grant Speech Recognition permission to AppleSTT.
#
# Run this before the first lab invocation. It opens the app bundle,
# which triggers macOS to show the authorization prompt. Accept the
# prompt in System Settings.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_BUNDLE="$SCRIPT_DIR/AppleSTT.app"

if [[ ! -d "$APP_BUNDLE" ]]; then
    echo "Error: AppleSTT.app not found at $APP_BUNDLE"
    echo "Run: voice-engine/native/apple/build.sh"
    exit 1
fi

echo "Opening AppleSTT.app to trigger authorization prompt..."
echo ""
echo "If this is your first time, macOS will show a speech recognition"
echo "permission dialog. Click 'Allow' to proceed."
echo ""
echo "If nothing happens, open System Settings > Privacy & Security >"
echo "Speech Recognition and add AppleSTT to the list, or toggle Terminal"
echo "if it's already there."
echo ""

# Use open -a to launch via LaunchServices (makes macOS recognize it as the app)
open -a "$APP_BUNDLE" 2>/dev/null || open "$APP_BUNDLE"

echo "Done. You can now run voice-lab commands."
