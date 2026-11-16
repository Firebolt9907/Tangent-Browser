#!/usr/bin/env bash
# Offline photo-background fixtures (fixtures/photobg.html): the strip above a full-bleed photo
# must read as that photo, controls must keep their matched strip, and every case must still keep
# readable content out of the band. Usage: ./photobg.sh [topPx]
set -euo pipefail
cd "$(dirname "$0")"
TOPPX="${1:-48}"
F="file://$(pwd)/fixtures/photobg.html"
# Photo cases must match the strip to the photo (PASS from photoBandTest). Controls are judged on
# the MECHANISM instead: the photo there is not full-bleed, so the strip SHOULD differ from the
# band under it, and what matters is that the photo box was not padded (and, for narrowCard, that
# the strip took the page colour rather than the card's hidden fallback colour).
CASES=(cover hero absImg bodyBg fixedAttach fixedBackdrop pseudoBackdrop headerFirst tiledTexture:'spacer(rgb(145, 162, 168))' narrowCard:'spacer(rgb(244, 239, 230))' contentClip:'spacer(transparent)')
pass=0; fail=0; failed=()
for spec in "${CASES[@]}"; do
  c="${spec%%:*}"; want=""; [ "$c" != "$spec" ] && want="${spec#*:}"
  out="$(node photoBandTest.js "$F?case=$c" "$TOPPX" 2>/dev/null | grep -E '^(mechanism|RESULT)' | tr '\n' ' ' || echo 'RESULT: ERROR')"
  if [ -n "$want" ]; then
    if echo "$out" | grep -qF "mechanism: $want " && ! echo "$out" | grep -q 'content moved'; then ok=1; else ok=0; fi
  elif echo "$out" | grep -q 'PASS'; then ok=1; else ok=0; fi
  if [ "$ok" -eq 1 ]; then pass=$((pass+1)); printf '  ✅ %-12s %s\n' "$c" "$out";
  else fail=$((fail+1)); failed+=("$c"); printf '  ❌ %-12s %s\n' "$c" "$out"; fi
done
echo "----"; echo "PASS=$pass FAIL=$fail (topPx=$TOPPX)"
[ "$fail" -eq 0 ] || { echo "Failing: ${failed[*]}"; exit 1; }
