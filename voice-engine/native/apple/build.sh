#!/usr/bin/env bash
#
# Build the AppleSTT Swift CLI and print the resulting binary path.
#
# Usage:
#   ./build.sh              -- release build (default; what the Python lab uses)
#   ./build.sh debug        -- debug build
#
# Output:
#   - Builds to native/apple/AppleSTT/.build/{release|debug}/AppleSTT
#   - Prints the absolute path of the resulting binary on the LAST line of stdout
#     so callers can `BIN=$(./build.sh | tail -1)`.

set -euo pipefail

CONFIG="${1:-release}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PKG_DIR="$SCRIPT_DIR/AppleSTT"

case "$CONFIG" in
  release)
    SWIFT_FLAGS="-c release"
    OUT_DIR="$PKG_DIR/.build/release"
    ;;
  debug)
    SWIFT_FLAGS=""
    OUT_DIR="$PKG_DIR/.build/debug"
    ;;
  *)
    echo "unknown config: $CONFIG (expected release|debug)" >&2
    exit 2
    ;;
esac

if ! command -v swift >/dev/null 2>&1; then
  echo "error: swift toolchain not on PATH. Install Xcode or the Swift toolchain." >&2
  exit 2
fi

cd "$PKG_DIR"
# shellcheck disable=SC2086
swift build $SWIFT_FLAGS

BIN="$OUT_DIR/AppleSTT"
if [[ ! -x "$BIN" ]]; then
  echo "error: build succeeded but binary not found at $BIN" >&2
  exit 3
fi

echo "$BIN"
