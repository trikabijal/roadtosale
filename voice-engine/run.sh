#!/usr/bin/env bash
#
# voice-engine/run.sh — run the Python comparison lab CLI locally.
#
# The lab (`voice_lab`) is the live, runnable core of this module: it transcribes
# audio through one or more STT strategies and compares cue-match results.
#
# Usage:
#   ./run.sh                       Print the lab's available strategies + CLI help.
#   ./run.sh <voice-lab args…>     Forward args straight to the voice-lab CLI.
#
# Examples:
#   ./run.sh                                   # show strategies + usage
#   ./run.sh run --strategies mock             # run a comparison matrix (mock strategy)
#   ./run.sh run --strategies apple_speech_transcriber,whisperkit   # needs native CLIs
#   ./run.sh --help                            # full voice-lab CLI help
#
# Native strategies (apple_*, whisperkit, sherpa_onnx) require their native
# binaries — build them with `./build.sh --with-native`. The `mock` strategy
# runs anywhere with no native deps.
#
# Requires ./build.sh to have created lab/.venv.

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VENV_DIR="$SCRIPT_DIR/lab/.venv"

log()  { printf '\033[1;34m[run]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[run][error]\033[0m %s\n' "$*" >&2; exit 1; }

[ -d "$VENV_DIR" ] || fail "lab/.venv not found. Run ./build.sh first."

# shellcheck disable=SC1091
source "$VENV_DIR/bin/activate"

# voice-lab CLI must run with the lab as cwd (it resolves sources/, data/, etc.
# relative to the lab root).
cd "$SCRIPT_DIR/lab"

if [ "$#" -eq 0 ]; then
  log "Available strategies:"
  python -c 'from voice_lab import VoiceEngineLab; print("  " + "\n  ".join(VoiceEngineLab.load().list_strategies()))'
  echo
  log "voice-lab CLI usage (pass any of these to ./run.sh):"
  voice-lab --help
  echo
  log "Quick start (no native binaries needed):"
  log "  ./run.sh run --strategies mock"
  exit 0
fi

exec voice-lab "$@"
