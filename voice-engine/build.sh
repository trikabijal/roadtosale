#!/usr/bin/env bash
#
# voice-engine/build.sh — one-command local build for the voice-engine module.
#
# What this builds:
#   1. Python comparison lab (lab/)  — the real, runnable core.
#      Creates lab/.venv, installs the `voice_lab` package editable, plus deps.
#   2. TS reference skeleton (src/) — typechecked + compiled to dist/.
#   3. (optional) Native STT CLIs   — Apple Swift + Android JVM binaries the lab
#      spawns. HEAVY + platform-specific, so OFF by default. Enable with a flag.
#
# Usage:
#   ./build.sh                 Build python lab + TS skeleton (default, fast).
#   ./build.sh --with-native   Also build the native STT CLIs (or: ./build.sh native).
#   ./build.sh -h | --help     Show this help.
#
# Native builds are orchestrated by calling the existing per-CLI build scripts
# under native/ — this script never duplicates their logic. Missing native
# toolchains are warned about, not fatal (so a non-mac fresh clone still builds
# the lab + TS).
#
# Idempotent and fresh-clone-safe. No personal paths. See docs/build.md.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

VENV_DIR="$SCRIPT_DIR/lab/.venv"
WITH_NATIVE=0

# ---------------------------------------------------------------------------
# Arg parsing
# ---------------------------------------------------------------------------
for arg in "$@"; do
  case "$arg" in
    --with-native|native) WITH_NATIVE=1 ;;
    -h|--help)
      sed -n '2,21p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *)
      echo "error: unknown argument '$arg' (try --with-native or --help)" >&2
      exit 2
      ;;
  esac
done

log()  { printf '\033[1;34m[build]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[build][warn]\033[0m %s\n' "$*" >&2; }
fail() { printf '\033[1;31m[build][error]\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# Prerequisite checks (core: python3, node, npm)
# ---------------------------------------------------------------------------
log "Checking core prerequisites…"

command -v python3 >/dev/null 2>&1 || fail "python3 not found on PATH. Install Python >= 3.10."
PY_VER="$(python3 -c 'import sys; print("%d.%d" % sys.version_info[:2])')"
PY_OK="$(python3 -c 'import sys; print(1 if sys.version_info[:2] >= (3,10) else 0)')"
[ "$PY_OK" = "1" ] || fail "Python $PY_VER found, but voice_lab requires >= 3.10."
log "  python3 $PY_VER  ($(command -v python3))"

command -v node >/dev/null 2>&1 || fail "node not found on PATH. Install Node.js >= 18."
command -v npm  >/dev/null 2>&1 || fail "npm not found on PATH. Install Node.js >= 18 (ships with npm)."
log "  node $(node --version)  npm $(npm --version)"

# ---------------------------------------------------------------------------
# 1. Python lab — venv + editable install
# ---------------------------------------------------------------------------
log "Setting up Python lab (lab/)…"

if [ ! -d "$VENV_DIR" ]; then
  log "  creating venv at lab/.venv"
  python3 -m venv "$VENV_DIR"
else
  log "  reusing existing venv at lab/.venv"
fi

# shellcheck disable=SC1091
source "$VENV_DIR/bin/activate"

python -m pip install --upgrade pip --quiet

# The lab depends on the sibling `vehicle-feature-catalog` package. Install it
# editable first so the lab's dependency resolves locally (it is not on PyPI).
CATALOG_DIR="$SCRIPT_DIR/../vehicle-feature-catalog"
if [ -d "$CATALOG_DIR" ]; then
  log "  installing vehicle-feature-catalog (editable) from sibling module"
  python -m pip install -e "$CATALOG_DIR" --quiet
else
  warn "vehicle-feature-catalog not found at $CATALOG_DIR — lab install may fail."
fi

log "  installing voice_lab (editable, with dev extras)"
python -m pip install -e "$SCRIPT_DIR/lab[dev]" --quiet

deactivate

# ---------------------------------------------------------------------------
# 2. TS reference skeleton — install + typecheck + build
# ---------------------------------------------------------------------------
log "Building TS reference skeleton (src/)…"
npm install --silent
npm run typecheck --silent
npm run build --silent
log "  TS compiled to dist/"

# ---------------------------------------------------------------------------
# 3. Native STT CLIs (optional, gated behind --with-native)
# ---------------------------------------------------------------------------
if [ "$WITH_NATIVE" -eq 1 ]; then
  log "Building native STT CLIs (--with-native)…"
  OS="$(uname -s)"

  if [ "$OS" = "Darwin" ]; then
    if command -v swift >/dev/null 2>&1; then
      log "  Apple SpeechTranscriber CLI (native/apple/build.sh)…"
      ( bash "$SCRIPT_DIR/native/apple/build.sh" ) \
        || warn "AppleSTT build failed — see output above."
      log "  WhisperKit CLI (native/apple/whisperkit_build.sh)…"
      ( bash "$SCRIPT_DIR/native/apple/whisperkit_build.sh" ) \
        || warn "WhisperKitSTT build failed — see output above."
    else
      warn "swift not on PATH — skipping Apple native CLIs. Install Xcode / Swift toolchain."
    fi

    # Android sherpa-onnx CLI uses the bundled Gradle wrapper (./gradlew) and
    # needs a JDK (Java 17+). The build script downloads native libs for macOS.
    if command -v java >/dev/null 2>&1; then
      log "  sherpa-onnx CLI (native/android/sherpa_onnx_build.sh)…"
      ( bash "$SCRIPT_DIR/native/android/sherpa_onnx_build.sh" ) \
        || warn "SherpaOnnxSTT build failed — see output above."
    else
      warn "java not on PATH — skipping sherpa-onnx CLI. Install a JDK (Java 17+)."
    fi
  else
    warn "Native STT CLIs build only on macOS (Darwin). Detected: $OS. Skipping native."
    warn "  The Python lab + TS skeleton built fine; native strategies just won't be runnable here."
  fi
else
  log "Skipping native STT CLIs (default). Use './build.sh --with-native' to build them."
fi

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------
log "Build complete."
log "  Python lab venv : lab/.venv  (activate: source lab/.venv/bin/activate)"
log "  TS skeleton      : dist/"
[ "$WITH_NATIVE" -eq 1 ] && log "  Native CLIs     : native/*/.../.build (see docs/build.md)"
log "Next: ./test.sh  (run all tests)  |  ./run.sh  (run the lab CLI)"
