#!/usr/bin/env bash
#
# reset-permissions.sh — put Just Talk back to a fresh-install state so you can test the full
# onboarding flow like a brand-new user. Revokes the app's TCC permission grants + clears the
# "onboarding done" flag, then relaunches. Safe and reversible — you just re-grant in the wizard.
#
# Why this is needed: macOS keys permissions to the BUNDLE ID (com.trika.justtalk.mac), not the
# app file — so deleting/reinstalling keeps the grants. Only tccutil clears them.
#
# Usage:
#   ./scripts/reset-permissions.sh                       # resets + relaunches ~/Desktop/Just Talk.app
#   ./scripts/reset-permissions.sh "/Applications/Just Talk.app"   # point at a specific install
#
set -euo pipefail

BID="com.trika.justtalk.mac"
APP="${1:-$HOME/Desktop/Just Talk.app}"

echo "Resetting Just Talk to a fresh-install state ($BID)…"
pkill -x "Just Talk" 2>/dev/null || true
sleep 1

for svc in Microphone Accessibility ListenEvent PostEvent SpeechRecognition; do
  if tccutil reset "$svc" "$BID" >/dev/null 2>&1; then
    echo "  ✓ revoked $svc"
  fi
done

if defaults delete "$BID" hasCompletedOnboarding >/dev/null 2>&1; then
  echo "  ✓ cleared onboarding flag"
fi

echo "Fresh state ready."
if [[ -d "$APP" ]]; then
  open "$APP"
  echo "Launched: $APP — the wizard should open as a new user."
else
  echo "App not found at: $APP"
  echo "Pass the path as the first argument, e.g.:"
  echo "  $0 \"/Applications/Just Talk.app\""
fi
