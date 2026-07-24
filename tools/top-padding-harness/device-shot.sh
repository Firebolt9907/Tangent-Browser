#!/usr/bin/env bash
# On-device visual check: launch a URL in Tangent (it opens as a Custom Tab, which uses the SAME
# ContentHost injection path as the main browser) on a connected device and pull a screenshot, so
# the top-padding can be eyeballed on REAL GeckoView — the one thing the Chromium harness can't do.
#
# Requires: a connected, UNLOCKED device (a secure lock screen blocks the screenshot; unlock first).
# Usage: ./device-shot.sh <url> [output.png]
set -euo pipefail
URL="${1:?usage: ./device-shot.sh <url> [out.png]}"
OUT="${2:-device-shot.png}"
PKG="com.firebolt9907.tangentbrowser"
# Pick the first fully-qualified adb serial (handles the multi-transport mDNS duplicates).
S="$(adb devices | awk -F'\t' '/\tdevice$/{print $1}' | grep '_tcp$' | head -1)"
[ -n "$S" ] || S="$(adb devices | awk -F'\t' '/\tdevice$/{print $1}' | head -1)"
[ -n "$S" ] || { echo "no adb device"; exit 1; }
echo "device: $S"

if adb -s "$S" shell dumpsys trust 2>/dev/null | grep -q 'deviceLocked=1'; then
  echo "WARNING: device is locked — unlock it, then re-run (the screenshot will show the lock screen otherwise)."
fi

adb -s "$S" shell am start -a android.intent.action.VIEW -d "$URL" "$PKG" >/dev/null
sleep 8   # let Gecko load + FCP-inject + settle
adb -s "$S" exec-out screencap -p > "$OUT"
echo "saved $OUT ($(file -b "$OUT" 2>/dev/null || echo '?'))"
