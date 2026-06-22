#!/usr/bin/env bash
#
# prereqs.sh — shared prerequisite checks for the vehicle-feature-catalog
# shell entry points (build.sh / test.sh / run.sh / deploy-local.sh).
#
# Source this file (don't execute it):  source scripts/prereqs.sh
#
# Exposes:
#   require_tool <name> <install-hint>          fail loudly if a tool is missing
#   require_node_version <min-major>            fail loudly if node is too old
#   require_npm_version  <min-major>            fail loudly if npm is too old
#   require_python_version <min-major> <min-minor>   python3 present AND >= min
#
# Each failure prints to stderr in the form:
#   ERROR: <tool> is required but not found. Install: <command>
# (or, for too-old versions, a "...is too old..." variant) and then exit 1.
# Nothing fails deep in pip/npm because of a missing tool — we catch it here.

# Guard against double-sourcing.
if [ -n "${_VFC_PREREQS_SOURCED:-}" ]; then
  return 0 2>/dev/null || true
fi
_VFC_PREREQS_SOURCED=1

# require_tool <name> <install-hint>
require_tool() {
  local tool="$1" hint="$2"
  if ! command -v "$tool" >/dev/null 2>&1; then
    printf 'ERROR: %s is required but not found. Install: %s\n' "$tool" "$hint" >&2
    exit 1
  fi
}

# require_python_version <min-major> <min-minor>
# Confirms python3 exists AND is at least the requested version.
require_python_version() {
  local min_major="$1" min_minor="$2"
  require_tool python3 "brew install python@${min_major}.${min_minor}  (macOS) | sudo apt-get install python3 python3-venv python3-pip  (Linux)"
  local ver ok
  ver="$(python3 -c 'import sys; print("%d.%d.%d" % sys.version_info[:3])')"
  ok="$(python3 -c "import sys; print(1 if sys.version_info[:2] >= (${min_major}, ${min_minor}) else 0)")"
  if [ "$ok" != "1" ]; then
    printf 'ERROR: python3 is %s but >= %s.%s is required. Install: %s\n' \
      "$ver" "$min_major" "$min_minor" "brew install python@${min_major}.${min_minor}  (macOS) | sudo apt-get install python3.${min_minor} python3.${min_minor}-venv  (Linux)" >&2
    exit 1
  fi
  _VFC_PYTHON_VERSION="$ver"
}

# require_node_version <min-major>
# Confirms node exists AND its major version is at least min-major.
require_node_version() {
  local min_major="$1"
  require_tool node "brew install node  (macOS) | https://nodejs.org/en/download  (or nvm)"
  local raw major
  raw="$(node --version)"            # e.g. v25.8.1
  major="${raw#v}"; major="${major%%.*}"
  if [ -z "$major" ] || [ "$major" -lt "$min_major" ] 2>/dev/null; then
    printf 'ERROR: node is %s but >= %s is required. Install: %s\n' \
      "$raw" "$min_major" "brew install node  (macOS) | https://nodejs.org/en/download  (or nvm)" >&2
    exit 1
  fi
  _VFC_NODE_VERSION="$raw"
}

# require_npm_version <min-major>
# Confirms npm exists (ships with Node) AND its major version is at least min-major.
require_npm_version() {
  local min_major="$1"
  require_tool npm "ships with Node — reinstall Node: brew install node  (macOS) | https://nodejs.org/en/download"
  local raw major
  raw="$(npm --version)"             # e.g. 11.11.0
  major="${raw%%.*}"
  if [ -z "$major" ] || [ "$major" -lt "$min_major" ] 2>/dev/null; then
    printf 'ERROR: npm is %s but >= %s is required. Install: %s\n' \
      "$raw" "$min_major" "npm install -g npm@latest  (or reinstall Node)" >&2
    exit 1
  fi
  _VFC_NPM_VERSION="$raw"
}
