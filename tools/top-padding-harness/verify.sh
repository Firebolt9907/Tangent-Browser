#!/usr/bin/env bash
# Runs the top-padding injection harness across every URL in sites.txt and prints a PASS/FAIL
# summary. Requires a local Chrome (override with CHROME_PATH) and `npm install` in this dir.
#   ./verify.sh            # default topPx=48
#   ./verify.sh 60         # custom topPx
set -euo pipefail
cd "$(dirname "$0")"
TOPPX="${1:-48}"
pass=0; fail=0; failed=()
while IFS= read -r line; do
  url="${line%%#*}"; url="$(echo "$url" | xargs || true)"
  [ -z "$url" ] && continue
  out="$(node run.js "$url" "$TOPPX" 2>/dev/null | grep -E 'RESULT' || echo 'RESULT: ERROR (no output)')"
  if echo "$out" | grep -q 'PASS'; then
    pass=$((pass+1)); printf '  ✅ %-45s %s\n' "$url" "${out#RESULT: }"
  else
    fail=$((fail+1)); failed+=("$url"); printf '  ❌ %-45s %s\n' "$url" "${out#RESULT: }"
  fi
done < sites.txt
echo "----"
echo "PASS=$pass FAIL=$fail (topPx=$TOPPX)"
[ "$fail" -eq 0 ] || { echo "Failing: ${failed[*]}"; exit 1; }
