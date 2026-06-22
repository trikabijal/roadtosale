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

# ---- Prerequisite checks (shared, fail loudly before any real work) --------
# `all` and `core` need swift; `app` needs xcodebuild + xcodegen. macOS is always
# required, and `all` needs every tool, so check the full set up front for `all`.
source "$(dirname "$0")/scripts/prereqs.sh"
require_macos

WHICH="${1:-all}"

case "$WHICH" in
  core) require_swift ;;
  app)  require_xcodebuild; require_xcodegen ;;
  all)  require_swift; require_xcodebuild; require_xcodegen ;;
esac

run_core() {
  echo "▶ Running DictationCore tests (swift test)…"
  ( cd Shared && swift test )
  echo "✓ DictationCore tests passed."
}

run_app() {
  echo "▶ Running JustTalkTests (xcodebuild test)…"
  echo "▶ Generating JustTalk.xcodeproj from project.yml…"
  xcodegen generate >/dev/null
  # JustTalkTests is a test target of the JustTalk scheme (see project.yml
  # `scheme.testTargets`); there is no standalone JustTalkTests scheme.
  xcodebuild -project JustTalk.xcodeproj -scheme JustTalk \
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
