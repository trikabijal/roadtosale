#!/usr/bin/env bash
#
# voice-engine/scripts/prereqs.sh — shared prerequisite checks.
#
# Sourced by build.sh / test.sh / run.sh / deploy-local.sh so every entry point
# checks its required tools at the TOP, before any real work, and fails loudly
# with an actionable install hint instead of a random deep failure.
#
# This file defines functions only; it does no work when sourced. Callers invoke
# the specific checks they need:
#
#   require_core_tools        python3 (>=3.10), node (>=20), npm
#   require_sibling_catalog   ../vehicle-feature-catalog editable-install source
#   require_native_tools      Swift/Xcode + JDK (Java 17+), macOS-aware
#
# Each helper prints `ERROR: <tool> is required but not found. Install: <cmd>`
# and exits 1 on failure (require_native_tools is advisory off-macOS — see below).

# --- pretty printers (defined only if the caller hasn't already) -------------
if ! declare -F log >/dev/null 2>&1; then
  log()  { printf '\033[1;34m[prereq]\033[0m %s\n' "$*"; }
fi
if ! declare -F warn >/dev/null 2>&1; then
  warn() { printf '\033[1;33m[prereq][warn]\033[0m %s\n' "$*" >&2; }
fi

# Loud, consistent fatal error used by every prereq check.
prereq_fail() {
  printf '\033[1;31mERROR:\033[0m %s\n' "$*" >&2
  exit 1
}

# require_min_version <found> <required> -> 0 if found >= required (semver-ish:
# compares dotted numeric components left to right).
_version_ge() {
  # $1 >= $2 ?
  [ "$1" = "$2" ] && return 0
  local lower
  lower="$(printf '%s\n%s\n' "$1" "$2" | sort -t. -k1,1n -k2,2n -k3,3n | head -n1)"
  [ "$lower" = "$2" ]
}

# ---------------------------------------------------------------------------
# Core tools — required by the DEFAULT build/test/run path.
#   python3 >= 3.10, node >= 20, npm (ships with node).
# ---------------------------------------------------------------------------
require_core_tools() {
  command -v python3 >/dev/null 2>&1 \
    || prereq_fail "python3 is required but not found. Install: https://www.python.org/downloads/ (or 'brew install python@3.12')"
  local py_ver
  py_ver="$(python3 -c 'import sys; print("%d.%d" % sys.version_info[:2])')"
  _version_ge "$py_ver" "3.10" \
    || prereq_fail "python3 >= 3.10 is required but found $py_ver. Install: https://www.python.org/downloads/ (or 'brew install python@3.12')"

  command -v node >/dev/null 2>&1 \
    || prereq_fail "node is required but not found. Install: https://nodejs.org/ (>= 20, or 'brew install node')"
  local node_ver
  node_ver="$(node --version | sed 's/^v//')"
  _version_ge "$node_ver" "20.0.0" \
    || prereq_fail "node >= 20 is required but found v$node_ver. Install: https://nodejs.org/ (or 'brew install node')"

  command -v npm >/dev/null 2>&1 \
    || prereq_fail "npm is required but not found. Install: https://nodejs.org/ (npm ships with Node.js >= 20)"

  log "core tools OK: python3 $py_ver, node v$node_ver, npm $(npm --version)"
}

# ---------------------------------------------------------------------------
# Sibling vehicle-feature-catalog — the lab editable-installs it from
# ../vehicle-feature-catalog (it is NOT on PyPI). Must run from the full monorepo.
# ---------------------------------------------------------------------------
require_sibling_catalog() {
  local script_dir="${1:?require_sibling_catalog: pass the script dir}"
  local catalog_dir="$script_dir/../vehicle-feature-catalog"
  [ -d "$catalog_dir" ] \
    || prereq_fail "sibling module vehicle-feature-catalog is required but not found at $catalog_dir. Run from the full monorepo; expected ../vehicle-feature-catalog (the lab editable-installs it)."
  log "sibling catalog OK: $catalog_dir"
}

# ---------------------------------------------------------------------------
# Native tools — required ONLY when the native STT CLIs are requested
# (./build.sh --with-native, deploy-local.sh). Swift/Xcode + JDK (Java 17+).
#
# Native CLIs build only on macOS (Darwin). Off-macOS we DON'T fail — we print
# why and let the caller skip native gracefully. On macOS, missing toolchains
# are advisory (warn) so the per-CLI build scripts can report their own detail;
# we surface install hints up front.
# ---------------------------------------------------------------------------
require_native_tools() {
  local os
  os="$(uname -s)"
  if [ "$os" != "Darwin" ]; then
    warn "native STT CLIs build only on macOS (Darwin); detected $os. Skipping native — install hints (Swift/Xcode, JDK 17) apply on a Mac only."
    return 0
  fi

  if ! command -v swift >/dev/null 2>&1; then
    warn "swift not found — Apple/WhisperKit native CLIs will be skipped. Install: Xcode from the App Store, or 'xcode-select --install' for the command-line tools."
  fi
  if ! command -v java >/dev/null 2>&1; then
    warn "java not found — sherpa-onnx native CLI will be skipped. Install a JDK 17+: 'brew install --cask temurin' (or download from https://adoptium.net/)."
  fi
}
