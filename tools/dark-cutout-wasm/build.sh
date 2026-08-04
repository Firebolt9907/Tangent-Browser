#!/usr/bin/env bash
# Rebuilds the WASM cutout kernel and regenerates DarkCutoutAssets.kt (wasm + worker + driver blobs).
# Run after changing src/lib.rs, worker.js, or driver.js.
#
#   ./build.sh          build + test + regenerate DarkCutoutAssets.kt
#   ./build.sh --check  build into a scratch dir and FAIL if DarkCutoutAssets.kt would change
#
# --check is the audit path: it proves the base64 blob committed in DarkCutoutAssets.kt is what
# src/lib.rs compiles to. The build is byte-reproducible (no_std, zero dependencies), so a clean
# rebuild of unchanged sources reproduces the committed file exactly.
set -euo pipefail
cd "$(dirname "$0")"

CHECK=0
[[ "${1:-}" == "--check" ]] && CHECK=1

if [[ $CHECK == 1 ]]; then
  # Pristine target dir: nothing stale can be reused, so the comparison is against a from-source build.
  CARGO_TARGET_DIR="$(mktemp -d)"
  export CARGO_TARGET_DIR
  trap 'rm -rf "$CARGO_TARGET_DIR"' EXIT
  echo "==> cargo build into scratch dir $CARGO_TARGET_DIR"
else
  echo "==> cargo build (wasm32-unknown-unknown, release)"
fi

cargo build --release --target wasm32-unknown-unknown

WASM="${CARGO_TARGET_DIR:-target}/wasm32-unknown-unknown/release/dark_cutout_wasm.wasm"
echo "==> wasm size: $(wc -c < "$WASM") bytes  sha256: $(shasum -a 256 "$WASM" | cut -d' ' -f1)"

if [[ $CHECK == 1 ]]; then
  echo "==> checking DarkCutoutAssets.kt against these sources"
  node gen.js --check
  echo "==> ok"
  exit 0
fi

base64 -i "$WASM" | tr -d '\n' > wasm.b64

echo "==> kernel unit tests"
node kernel.test.js

echo "==> generating DarkCutoutAssets.kt"
node gen.js

echo "==> done"
