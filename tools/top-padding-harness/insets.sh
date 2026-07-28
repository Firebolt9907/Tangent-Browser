#!/usr/bin/env bash
# Runs insetChangeTest.js across the offline header fixtures. topPx is not a constant on a phone
# (SafariBar collapse/expand, rotation), so every fixture must survive the inset CHANGING under it —
# insets must not compound, and nothing may stay latched at a taller inset's value.
set -euo pipefail
cd "$(dirname "$0")"
SEQ="${1:---seq=48,100,48}"
F="file://$(pwd)/fixtures/headers.html"
CASES=(stacked transformed rtl pretransformed stickyAbs negativeTop willChange nestedScrollSticky shadowHeader revealedHeader stickySubnav stickyInTransformed dialogTopBar stickyInTranslate popoverHeader fixedInContainer siteHeaderPadImportant layeredHeader gridBodyHeader gridColumnsHeader contentsBodyHeader tableBodyHeader absoluteTopHeader siteHeaderScrollPad fixedNavNoScrollPad)
pass=0; fail=0; failed=()
for c in "${CASES[@]}"; do
  out="$(node insetChangeTest.js "$F?case=$c" "$SEQ" 2>/dev/null | grep -E 'RESULT' || echo 'RESULT: ERROR')"
  if echo "$out" | grep -q 'PASS'; then pass=$((pass+1)); printf '  ✅ %-24s\n' "$c";
  else fail=$((fail+1)); failed+=("$c"); printf '  ❌ %-24s %s\n' "$c" "${out#RESULT: }"; fi
done
echo "----"; echo "PASS=$pass FAIL=$fail ($SEQ)"
[ "$fail" -eq 0 ] || { echo "Failing: ${failed[*]}"; exit 1; }
