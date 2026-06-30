#!/usr/bin/env bash
#
# voice-engine/deploy-local.sh — full local "deploy" of the voice-engine module.
#
# There is NO server to deploy here. "Deploy local" means: get this machine into
# a state where the Python lab actually runs end-to-end, including the native STT
# binaries the lab spawns as subprocesses. Those native binaries ARE the
# deployable artifacts — the lab calls them, it doesn't ship a service.
#
# What this does:
#   1. Runs the full build INCLUDING native CLIs   (./build.sh --with-native)
#      - python lab venv + editable install
#      - TS skeleton compiled
#      - (macOS) Apple + WhisperKit + sherpa-onnx native binaries
#   2. Smoke-verifies the lab imports cleanly:
#      python -c "from voice_lab import VoiceEngineLab"
#   3. Reports which native strategy binaries are present on this machine.
#
# On non-macOS, native CLIs are skipped (they only build on Darwin); the lab +
# TS still set up so the `mock` strategy runs.
#
# Usage:
#   ./deploy-local.sh            Full local setup (build + native + verify).
#   ./deploy-local.sh -h|--help  Show this help.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

case "${1:-}" in
  -h|--help)
    sed -n '2,28p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    exit 0
    ;;
  "") ;;
  *) echo "error: unknown argument '$1' (try --help)" >&2; exit 2 ;;
esac

log()  { printf '\033[1;34m[deploy-local]\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[deploy-local][warn]\033[0m %s\n' "$*" >&2; }
fail() { printf '\033[1;31m[deploy-local][error]\033[0m %s\n' "$*" >&2; exit 1; }

# Shared, DRY prerequisite checks (fail loudly with install hints).
# shellcheck source=scripts/prereqs.sh
source "$SCRIPT_DIR/scripts/prereqs.sh"

# ---------------------------------------------------------------------------
# Prerequisite checks (fail loudly BEFORE invoking the build)
# ---------------------------------------------------------------------------
log "Checking prerequisites…"
require_core_tools                       # python3 (>=3.10), node (>=20), npm
require_sibling_catalog "$SCRIPT_DIR"    # ../vehicle-feature-catalog
require_native_tools                     # Swift/Xcode + JDK 17 (macOS); explains skip off-macOS

VENV_DIR="$SCRIPT_DIR/lab/.venv"

# ---------------------------------------------------------------------------
# 1. Full build, with native CLIs
# ---------------------------------------------------------------------------
log "Running full build with native STT CLIs…"
bash "$SCRIPT_DIR/build.sh" --with-native

# ---------------------------------------------------------------------------
# 2. Smoke-verify the lab imports
# ---------------------------------------------------------------------------
log "Verifying voice_lab import…"
[ -d "$VENV_DIR" ] || fail "lab/.venv missing after build — build failed?"
# shellcheck disable=SC1091
source "$VENV_DIR/bin/activate"
python -c "from voice_lab import VoiceEngineLab; VoiceEngineLab.load(); print('OK: VoiceEngineLab loads')" \
  || fail "voice_lab import/load failed."

# ---------------------------------------------------------------------------
# 3. Report native artifact presence
# ---------------------------------------------------------------------------
log "Checking native STT binaries (the deployable artifacts the lab spawns)…"

APPLE_BIN="$SCRIPT_DIR/native/apple/AppleSTT/AppleSTT.app/Contents/MacOS/AppleSTT"
WHISPER_BIN="$SCRIPT_DIR/native/apple/WhisperKitSTT/.build/release/WhisperKitSTT"
SHERPA_BIN="$SCRIPT_DIR/native/android/SherpaOnnxSTT/build/install/SherpaOnnxSTT/bin/SherpaOnnxSTT"

report_bin() {
  local label="$1" path="$2"
  if [ -x "$path" ]; then
    log "  [present] $label  ->  $path"
  else
    warn "[missing] $label  (expected $path)"
  fi
}

report_bin "Apple SpeechTranscriber (apple_speech_transcriber)" "$APPLE_BIN"
report_bin "WhisperKit (whisperkit)"                            "$WHISPER_BIN"
report_bin "sherpa-onnx (sherpa_onnx)"                          "$SHERPA_BIN"

deactivate

log "Local deploy complete."
log "  Run a comparison:  ./run.sh run --strategies mock"
log "  No server here — native binaries above are the deployable artifacts the lab calls."
