#!/usr/bin/env bash
#
# run.sh — Run the module's primary CLIs locally.
#
# This module ships two operator CLIs:
#   * validate       — validate the catalog YAML under data/   (scripts/validate.py)
#   * derive-vocab    — regenerate per-make STT vocab JSON      (scripts/derive_vocab.py)
#
# Usage:
#   ./run.sh validate                  # validate the bundled data/ catalog
#   ./run.sh validate --data-dir PATH  # validate a different data dir
#   ./run.sh derive-vocab              # derive vocab for every make
#   ./run.sh derive-vocab honda toyota # derive vocab for specific make(s)
#
# With no arguments, prints this usage. See docs/build.md.
#
set -euo pipefail
cd "$(dirname "$0")"

err()   { printf '\033[1;31m  ✗\033[0m %s\n' "$*" >&2; }

# --- prerequisite checks ----------------------------------------------------
# The CLIs run on python3 (the venv's, or system as a fallback below).
# shellcheck source=scripts/prereqs.sh disable=SC1091
source "$(dirname "$0")/scripts/prereqs.sh"
require_python_version 3 11

usage() {
  cat <<'EOF'
Usage: ./run.sh <command> [args...]

Commands:
  validate [--data-dir PATH]   Validate the catalog. Defaults to ./data.
  derive-vocab [make ...]      Regenerate voice-engine derived vocab JSON.
                               No makes = all makes found under data/makes.

Examples:
  ./run.sh validate
  ./run.sh validate --data-dir ./data
  ./run.sh derive-vocab
  ./run.sh derive-vocab honda
EOF
}

# Prefer the local venv's python if it exists; otherwise fall back to python3
# (validate.py / derive_vocab.py only need PyYAML, which the venv provides).
if [ -x .venv/bin/python ]; then
  PY=.venv/bin/python
else
  PY=python3
  err "Note: ./.venv not found, using system python3. Run ./build.sh for the full setup."
fi

if [ "$#" -eq 0 ]; then
  usage
  exit 0
fi

cmd="$1"
shift

case "$cmd" in
  validate)
    # Default to the bundled ./data dir if the caller didn't pass --data-dir.
    if printf '%s\n' "$@" | grep -q -- '--data-dir'; then
      exec "$PY" scripts/validate.py "$@"
    else
      exec "$PY" scripts/validate.py --data-dir ./data "$@"
    fi
    ;;
  derive-vocab|derive_vocab)
    exec "$PY" scripts/derive_vocab.py "$@"
    ;;
  -h|--help|help)
    usage
    ;;
  *)
    err "Unknown command: ${cmd}"
    echo
    usage
    exit 2
    ;;
esac
