#!/usr/bin/env bash
cd "$(dirname "$0")"
TOPPX="${1:-48}"; WIN="${2:-3500}"
while IFS= read -r line; do
  url="${line%%#*}"; url="$(echo "$url" | xargs || true)"
  [ -z "$url" ] && continue
  out="$(node dutyTest.js "$url" "$TOPPX" "$WIN" 2>/dev/null | grep -E 'exposure duty cycle' || echo 'duty cycle: ERROR')"
  printf '%-50s %s\n' "$url" "$out"
done < sites.txt
