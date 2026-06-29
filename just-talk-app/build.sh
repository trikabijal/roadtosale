#!/usr/bin/env bash
set -euo pipefail

echo "[just-talk] installing dependencies..."
npm install

echo "[just-talk] running expo prebuild..."
npx expo prebuild --clean

echo "[just-talk] done — open ios/JustTalk.xcworkspace in Xcode to build to a device"
