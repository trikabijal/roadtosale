#!/usr/bin/env bash
# test-all.sh — run every sub-engine's test suite locally.
#
# Each module owns its own test.sh; this runs them all and prints a pass/fail
# summary. It runs every suite even if one fails, so you see the full picture,
# then exits non-zero if any module failed.
#
# Usage: ./test-all.sh
#
# Note: dictation tests need macOS + Xcode and will fail on other platforms.
# Run ./build-all.sh first (the test scripts assume deps are installed).
set -uo pipefail
cd "$(dirname "$0")"

MODULES=(vehicle-feature-catalog voice-engine road-to-sale-app dictation)

results=()
overall=0
for m in "${MODULES[@]}"; do
  echo ""
  echo "=================================================================="
  echo "  test: $m"
  echo "=================================================================="
  if [ -f "$m/test.sh" ]; then
    if (cd "$m" && bash ./test.sh); then
      results+=("  ok    $m")
    else
      results+=("  FAIL  $m")
      overall=1
    fi
  else
    results+=("  skip  $m (no test.sh)")
  fi
done

echo ""
echo "===================== test-all summary ====================="
printf '%s\n' "${results[@]}"
echo "============================================================"
exit $overall
