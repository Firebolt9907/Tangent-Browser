#!/usr/bin/env bash
# Runs overflowTest.js against the offline APP-SHELL fixtures (fixtures/appshell.html) — the
# opposite hazard from fixtures.sh: a viewport-height-locked shell that the top inset pushes off
# the bottom of the screen (taking the bottom nav/player/composer with it). In a scroll-locked
# document that strip is unreachable forever. Usage: ./shells.sh [topPx]
set -euo pipefail
cd "$(dirname "$0")"
TOPPX="${1:-48}"
F="file://$(pwd)/fixtures/appshell.html"
CASES=(vhShell dvhShell pctShell fixedShell minVhShell lockedVhShell lockedBodyVhShell lockedPctShell lockedPaddedShell)
pass=0; fail=0; failed=()
for c in "${CASES[@]}"; do
  out="$(node overflowTest.js "$F?case=$c" "$TOPPX" 2>/dev/null | grep -E 'RESULT' || echo 'RESULT: ERROR')"
  if echo "$out" | grep -q 'PASS'; then pass=$((pass+1)); printf '  ✅ %-20s %s\n' "$c" "${out#RESULT: }";
  else fail=$((fail+1)); failed+=("$c"); printf '  ❌ %-20s %s\n' "$c" "${out#RESULT: }"; fi
done
echo "----"; echo "PASS=$pass FAIL=$fail (topPx=$TOPPX)"
[ "$fail" -eq 0 ] || { echo "Failing: ${failed[*]}"; exit 1; }
