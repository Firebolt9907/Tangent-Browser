#!/usr/bin/env bash
# Runs the shipping viewport-spacer script against the deterministic, OFFLINE header fixtures in
# fixtures/headers.html — adversarial header patterns (stacked bars, transform/will-change
# containing blocks, RTL, sticky+absolute, nested-scroll sticky, negative top). No network, fast,
# stable. Each fixture puts real content in the [0,topPx) band; a PASS means the injection cleared
# it. Usage: ./fixtures.sh [topPx]
set -euo pipefail
cd "$(dirname "$0")"
TOPPX="${1:-48}"
F="file://$(pwd)/fixtures/headers.html"
CASES=(stacked transformed rtl pretransformed stickyAbs negativeTop willChange nestedScrollSticky shadowHeader revealedHeader stickySubnav stickyInTransformed dialogTopBar stickyInTranslate popoverHeader)
pass=0; fail=0; failed=()
for c in "${CASES[@]}"; do
  out="$(node run.js "$F?case=$c" "$TOPPX" 2>/dev/null | grep -E 'RESULT' || echo 'RESULT: ERROR')"
  if echo "$out" | grep -q 'PASS'; then pass=$((pass+1)); printf '  ✅ %-20s %s\n' "$c" "${out#RESULT: }";
  else fail=$((fail+1)); failed+=("$c"); printf '  ❌ %-20s %s\n' "$c" "${out#RESULT: }"; fi
done
echo "----"; echo "PASS=$pass FAIL=$fail (topPx=$TOPPX)"
[ "$fail" -eq 0 ] || { echo "Failing: ${failed[*]}"; exit 1; }
