#!/usr/bin/env bash
#
# test.sh — Run the Just Talk test suites.
#
# Usage:
#   ./test.sh              Run everything: DictationCore unit tests + JustTalkTests (default)
#   ./test.sh core         Run only the DictationCore SwiftPM tests (fast, no Xcode project)
#   ./test.sh app          Run only the JustTalkTests xcodebuild scheme
#
# What it does:
#   - core: `swift test` in Shared/ — exercises the cleanup / pipeline / store / telemetry
#           tests in Shared/Tests/DictationCoreTests.
#   - app:  generates the project (xcodegen) and runs `xcodebuild test` on JustTalkTests,
#           which covers the macOS app's hotkey config (JustTalkTests/).
#
# Exits non-zero if any suite fails.
#
set -euo pipefail
cd "$(dirname "$0")"

WHICH="${1:-all}"

if [[ "$(uname -s)" != "Darwin" ]]; then
  echo "✗ Tests run only on macOS (uname is '$(uname -s)')." >&2
  exit 1
fi

run_core() {
  echo "▶ Running DictationCore tests (swift test)…"
  if ! command -v swift >/dev/null 2>&1; then
    echo "✗ swift not found. Install Xcode / the Command Line Tools." >&2
    exit 1
  fi
  ( cd Shared && swift test )
  echo "✓ DictationCore tests passed."
}

run_app() {
  echo "▶ Running JustTalkTests (xcodebuild test)…"
  if ! command -v xcodebuild >/dev/null 2>&1; then
    echo "✗ xcodebuild not found. Install Xcode." >&2
    exit 1
  fi
  if ! command -v xcodegen >/dev/null 2>&1; then
    echo "✗ xcodegen not found. Install it with: brew install xcodegen" >&2
    exit 1
  fi
  echo "▶ Generating JustTalk.xcodeproj from project.yml…"
  xcodegen generate >/dev/null
  xcodebuild -project JustTalk.xcodeproj -scheme JustTalkTests \
    -configuration Debug -derivedDataPath build \
    -destination 'platform=macOS' \
    CODE_SIGNING_REQUIRED=NO CODE_SIGNING_ALLOWED=NO test
  echo "✓ JustTalkTests passed."
}

case "$WHICH" in
  core) run_core ;;
  app)  run_app ;;
  all)  run_core; run_app ;;
  *)
    echo "✗ Unknown argument '$WHICH'. Use: all | core | app" >&2
    exit 1
    ;;
esac

echo "✓ All requested test suites passed."
