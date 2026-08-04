#!/usr/bin/env bash
# Decodes the WASM_B64 blob committed in DarkCutoutAssets.kt back into a real .wasm file, so it can
# be inspected with standard tooling and no Rust toolchain:
#
#   ./extract-wasm.sh && wasm2wat dark_cutout.wasm | less     # read the whole module as text
#   ./extract-wasm.sh && wasm-objdump -x dark_cutout.wasm     # sections, imports, exports
#
# The kernel is ~5 KB and has no imports, so the .wat is short enough to read end to end.
# This is deliberately a plain shell script (sed + base64) rather than another generator: the point
# is that you can read this file in ten seconds and trust what it hands you.
set -euo pipefail
cd "$(dirname "$0")"

KT=../../app/src/main/java/com/firebolt9907/tangentbrowser/ui/content/DarkCutoutAssets.kt
OUT=dark_cutout.wasm

# The blob is the quoted string on the line following `const val WASM_B64: String =`.
sed -n '/const val WASM_B64: String =/{n;s/^[[:space:]]*"//;s/"$//;p;}' "$KT" \
  | { base64 -d 2>/dev/null || base64 -D; } > "$OUT"

echo "wrote $OUT ($(wc -c < "$OUT" | tr -d ' ') bytes)"
echo "  sha256 decoded: $(shasum -a 256 "$OUT" | cut -d' ' -f1)"
echo "  sha256 claimed: $(sed -n 's/.*const val WASM_SHA256: String = "\([0-9a-f]*\)".*/\1/p' "$KT")"
echo "(the two must match; ./build.sh --check proves the bytes come from src/lib.rs)"
