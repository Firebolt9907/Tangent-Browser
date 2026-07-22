#!/usr/bin/env bash
# Rebuilds the WASM cutout kernel and regenerates DarkCutoutAssets.kt (wasm + worker + driver blobs).
# Run after changing src/lib.rs, worker.js, or driver.js.
set -euo pipefail
cd "$(dirname "$0")"

echo "==> cargo build (wasm32-unknown-unknown, release)"
cargo build --release --target wasm32-unknown-unknown

WASM=target/wasm32-unknown-unknown/release/dark_cutout_wasm.wasm
echo "==> wasm size: $(wc -c < "$WASM") bytes"
base64 -i "$WASM" | tr -d '\n' > wasm.b64

echo "==> kernel unit tests"
node kernel.test.js

echo "==> generating DarkCutoutAssets.kt"
node gen.js

echo "==> done"
