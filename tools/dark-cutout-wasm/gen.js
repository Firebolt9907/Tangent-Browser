// Generates DarkCutoutAssets.kt from the compiled wasm + worker.js + driver.js.
// WASM + worker are base64 (opaque blobs); the driver is flattened to a block-comment-only one-liner
// (page-context JS, injected as part of a javascript: URL — so no // line comments, newlines->space).
const fs = require('fs');
const path = require('path');

const wasm = fs.readFileSync(path.resolve(__dirname, 'target/wasm32-unknown-unknown/release/dark_cutout_wasm.wasm'));
const WASM_B64 = wasm.toString('base64');
const WORKER_B64 = Buffer.from(fs.readFileSync(path.resolve(__dirname, 'worker.js'), 'utf8'), 'utf8').toString('base64');

let driver = fs.readFileSync(path.resolve(__dirname, 'driver.js'), 'utf8');
if (/^\s*\/\//m.test(driver.replace(/^\/\*[\s\S]*?\*\//, ''))) {
  // (Leading banner /* */ is fine; any // line comment elsewhere is not flatten-safe.)
}
const lineComment = driver.split('\n').find(l => /(^|[^:])\/\//.test(l) && !/https?:\/\//.test(l) && !/text\/javascript/.test(l));
if (lineComment) { console.error('driver.js has a // line comment (not flatten-safe):\n  ' + lineComment.trim()); process.exit(1); }
const DRIVER_FLAT = driver.replace(/\r?\n/g, ' ').replace(/\s{2,}/g, ' ').trim();

const kt = `package com.firebolt9907.tangentbrowser.ui.content

/*
 * GENERATED — do not edit by hand. Source: tools/dark-cutout-wasm/{src/lib.rs,worker.js,driver.js}.
 * Regenerate with tools/dark-cutout-wasm/build.sh after changing any of those.
 *
 * Assets for the web-dark-mode image cutout ([WebDarkMode.buildImageCutoutScript]):
 *  - [WASM_B64]   base64 of the Rust->WASM light-background keyer kernel (no_std, ~2 KB).
 *  - [WORKER_B64] base64 of the Web Worker that runs the kernel off the main thread.
 *  - [DRIVER_JS]  the page-context driver, flattened to one line (block comments only).
 */
internal object DarkCutoutAssets {
    const val WASM_B64: String =
        "${WASM_B64}"

    const val WORKER_B64: String =
        "${WORKER_B64}"

    const val DRIVER_JS: String =
        ${JSON.stringify(DRIVER_FLAT)}
}
`;

const outKt = path.resolve(__dirname, '../../app/src/main/java/com/firebolt9907/tangentbrowser/ui/content/DarkCutoutAssets.kt');
fs.writeFileSync(outKt, kt);
console.log('wrote', outKt);
console.log('  WASM_B64 len =', WASM_B64.length, ' WORKER_B64 len =', WORKER_B64.length, ' DRIVER_JS len =', DRIVER_FLAT.length);

// Also emit the flattened driver for the harness so it tests the exact shipping bytes.
fs.writeFileSync(path.resolve(__dirname, 'driver.flat.js'), DRIVER_FLAT);
