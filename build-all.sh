#!/usr/bin/env bash
# build-all.sh — build every sub-engine locally, in dependency order.
#
# Each module owns its own build.sh; this just runs them in turn and prints a
# pass/fail summary. It does NOT stop at the first failure, so one module's
# missing toolchain doesn't hide the others.
#
# Usage: ./build-all.sh
#
# Note: dictation needs macOS + Xcode and will fail on other platforms (expected).
set -uo pipefail
cd "$(dirname "$0")"

# vehicle-feature-catalog first: voice-engine editable-installs it as a sibling.
MODULES=(vehicle-feature-catalog voice-engine road-to-sale-app dictation)

results=()
overall=0
for m in "${MODULES[@]}"; do
  echo ""
  echo "=================================================================="
  echo "  build: $m"
  echo "=================================================================="
  if [ -f "$m/build.sh" ]; then
    if (cd "$m" && bash ./build.sh); then
      results+=("  ok    $m")
    else
      results+=("  FAIL  $m")
      overall=1
    fi
  else
    results+=("  skip  $m (no build.sh)")
  fi
done

echo ""
echo "===================== build-all summary ====================="
printf '%s\n' "${results[@]}"
echo "============================================================="
exit $overall
