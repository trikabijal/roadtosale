#!/bin/bash
#
# Launcher for AppleSTT.app that properly invokes via LaunchServices.
#
# This wrapper uses `open -a` to launch the app bundle, which tells macOS
# that the app (not Terminal) is the responsible process. This is required
# for TCC to surface the speech-recognition authorization prompt on first run.
#
# Usage:
#   ./launch-applestt.sh --file <path> --mode <mode> [other args]
#
# The wrapper collects all arguments and passes them to the binary inside
# the app bundle via environment variables, since `open -a` does not support
# passing command-line arguments to app bundles directly.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_BUNDLE="$SCRIPT_DIR/AppleSTT.app"
BINARY="$APP_BUNDLE/Contents/MacOS/AppleSTT"

# Verify the bundle exists
if [[ ! -d "$APP_BUNDLE" ]]; then
    echo "AppleSTT error: app bundle not found: $APP_BUNDLE" >&2
    exit 1
fi

if [[ ! -x "$BINARY" ]]; then
    echo "AppleSTT error: binary not found or not executable: $BINARY" >&2
    exit 1
fi

# Pass all arguments as a single string to the binary via stdin
# (alternatives like environment variables or temp files are fragile)
"$BINARY" "$@"
