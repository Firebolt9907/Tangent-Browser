#!/usr/bin/env bash
# Runs the scroll-padding-top (anchor-jump) half of the injection against the OFFLINE anchor
# fixtures in fixtures/headers.html. This is the path iter-22's real bug lived in (a site's own
# :root{scroll-padding-top:0!important} beat our stylesheet rule, dropping #anchor jumps under the
# status bar). run.js/fixtures.sh only check the band, never anchor landing — this fills that gap.
# A case PASSES when the anchor target lands at top >= topPx-2 (below the inset). Usage: ./anchors.sh [topPx]
set -euo pipefail
cd "$(dirname "$0")"
TOPPX="${1:-48}"
F="file://$(pwd)/fixtures/headers.html"
# case=target-selector  (all anchor fixtures expose an #target and are driven with no clickSelector)
CASES=(anchor siteScrollPad customScrollContainer siteHeaderScrollPad fixedNavNoScrollPad)
pass=0; fail=0; failed=()
for c in "${CASES[@]}"; do
  out="$(node anchorTest.js "$F?case=$c" '#target' "$TOPPX" 2>/dev/null | grep -E 'RESULT' || echo 'RESULT: ERROR')"
  if echo "$out" | grep -q 'PASS'; then pass=$((pass+1)); printf '  ✅ %-22s %s\n' "$c" "${out#RESULT: }";
  else fail=$((fail+1)); failed+=("$c"); printf '  ❌ %-22s %s\n' "$c" "${out#RESULT: }"; fi
done
echo "----"; echo "PASS=$pass FAIL=$fail (topPx=$TOPPX)"
[ "$fail" -eq 0 ] || { echo "Failing: ${failed[*]}"; exit 1; }
