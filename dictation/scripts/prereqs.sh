#!/usr/bin/env bash
#
# prereqs.sh — Shared prerequisite checks for the dictation scripts.
#
# Single source of truth for "is this machine able to build Just Talk?". Sourced by
# build.sh, test.sh, run.sh, and deploy-local.sh. Each check fails LOUDLY at the top
# of the calling script — before any real work — with a clear install hint, so a
# missing tool never turns into a confusing deep failure later.
#
# Usage (from a sibling script):
#   source "$(dirname "$0")/scripts/prereqs.sh"
#   require_macos
#   require_xcodebuild
#   require_xcodegen
#   # or, for the common case, all three:
#   require_build_prereqs
#
# Every failing check prints "ERROR: <tool> is required but not found. Install: <hint>"
# and exits 1.

# ---- Individual checks -----------------------------------------------------

require_macos() {
  if [[ "$(uname -s)" != "Darwin" ]]; then
    echo "ERROR: macOS is required but not found (uname is '$(uname -s)')." >&2
    echo "       dictation is a macOS app; requires macOS 14+ on Apple Silicon (recommended)." >&2
    exit 1
  fi
}

require_xcodebuild() {
  if ! command -v xcodebuild >/dev/null 2>&1; then
    echo "ERROR: xcodebuild is required but not found." >&2
    echo "       Install: install Xcode from the App Store, then" >&2
    echo "                sudo xcode-select -s /Applications/Xcode.app" >&2
    exit 1
  fi
}

require_xcodegen() {
  if ! command -v xcodegen >/dev/null 2>&1; then
    echo "ERROR: xcodegen is required but not found." >&2
    echo "       Install: brew install xcodegen" >&2
    exit 1
  fi
}

require_swift() {
  if ! command -v swift >/dev/null 2>&1; then
    echo "ERROR: swift is required but not found." >&2
    echo "       Install: install Xcode from the App Store, then" >&2
    echo "                sudo xcode-select -s /Applications/Xcode.app" >&2
    exit 1
  fi
}

# ---- Bundles ----------------------------------------------------------------

# require_build_prereqs — everything needed to generate the project and build with
# xcodebuild (build.sh, run.sh, deploy-local.sh).
require_build_prereqs() {
  require_macos
  require_xcodebuild
  require_xcodegen
}
