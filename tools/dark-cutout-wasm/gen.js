// Generates DarkCutoutAssets.kt from the compiled wasm + worker.js + driver.js.
// WASM + worker are base64 (opaque blobs); the driver is flattened to a block-comment-only one-liner
// (page-context JS, injected as part of a javascript: URL — so no // line comments, newlines->space).
//
// `node gen.js --check` regenerates in memory and diffs against the committed DarkCutoutAssets.kt
// instead of writing it — the drift guard (see build.sh --check). Output is a pure function of the
// sources in this directory, so a clean rebuild reproduces the committed file byte for byte.
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const CHECK = process.argv.includes('--check');

// Honour CARGO_TARGET_DIR so an audit can build into a pristine scratch dir and be sure no stale
// artifact is in play (build.sh --check does exactly this).
const targetDir = process.env.CARGO_TARGET_DIR || path.resolve(__dirname, 'target');
const wasm = fs.readFileSync(path.resolve(targetDir, 'wasm32-unknown-unknown/release/dark_cutout_wasm.wasm'));
const WASM_B64 = wasm.toString('base64');
const WORKER_B64 = Buffer.from(fs.readFileSync(path.resolve(__dirname, 'worker.js'), 'utf8'), 'utf8').toString('base64');

let driver = fs.readFileSync(path.resolve(__dirname, 'driver.js'), 'utf8');
const lineComment = driver.split('\n').find(l => /(^|[^:])\/\//.test(l) && !/https?:\/\//.test(l) && !/text\/javascript/.test(l));
if (lineComment) { console.error('driver.js has a // line comment (not flatten-safe):\n  ' + lineComment.trim()); process.exit(1); }
const DRIVER_FLAT = driver.replace(/\r?\n/g, ' ').replace(/\s{2,}/g, ' ').trim();

/*
 * Walk the wasm section table so the provenance header states what the bytes actually contain
 * rather than what we believe they contain. Two facts carry the audit:
 *   - the import section (id 2) is absent, so the module has no host functions to call and can
 *     touch nothing but its own linear memory — it is a pure compute kernel by construction;
 *   - the export list is exactly the kernel API the worker drives.
 * DarkCutoutAssetsTest re-derives both from the shipped base64, so this stays honest.
 */
function inspectWasm(buf) {
  if (buf.readUInt32BE(0) !== 0x0061736d) throw new Error('not a wasm module (bad magic)');
  const sections = [];
  let exports = [];
  let i = 8;
  while (i < buf.length) {
    const id = buf[i++];
    let len = 0, shift = 0, b;
    do { b = buf[i++]; len |= (b & 0x7f) << shift; shift += 7; } while (b & 0x80);
    sections.push(id);
    if (id === 7) {
      let p = i;
      let count = 0; shift = 0;
      do { b = buf[p++]; count |= (b & 0x7f) << shift; shift += 7; } while (b & 0x80);
      for (let n = 0; n < count; n++) {
        let nameLen = 0; shift = 0;
        do { b = buf[p++]; nameLen |= (b & 0x7f) << shift; shift += 7; } while (b & 0x80);
        exports.push(buf.slice(p, p + nameLen).toString('utf8'));
        p += nameLen + 1;
        do { b = buf[p++]; } while (b & 0x80);
      }
    }
    i += len;
  }
  return { sections, exports, hasImports: sections.includes(2) };
}

const info = inspectWasm(wasm);
const WASM_SHA256 = crypto.createHash('sha256').update(wasm).digest('hex');
if (info.hasImports) {
  console.error('REFUSING: the wasm kernel gained an import section — it can now call host functions.');
  console.error('That breaks the "pure compute, no I/O" property the audit notes rely on. Review src/lib.rs.');
  process.exit(1);
}

const kt = `package com.firebolt9907.tangentbrowser.ui.content

/*
 * GENERATED — do not edit by hand. Source: tools/dark-cutout-wasm/{src/lib.rs,worker.js,driver.js}.
 * Regenerate with tools/dark-cutout-wasm/build.sh after changing any of those.
 *
 * Assets for the web-dark-mode image cutout ([WebDarkMode.buildImageCutoutScript]):
 *  - [WASM_B64]   base64 of the Rust->WASM light-background keyer kernel (no_std, ~2 KB).
 *  - [WORKER_B64] base64 of the Web Worker that runs the kernel off the main thread.
 *  - [DRIVER_JS]  the page-context driver, flattened to one line (block comments only).
 *
 * These are base64 because the transport is a text channel end to end: the driver ships as a
 * \`javascript:\` URL, so the kernel has to survive as a JS string literal (WebDarkMode.kt), and the
 * page cannot reach the APK's assets to load a binary itself.
 *
 * AUDITING [WASM_B64] — it is the one blob here you cannot read (${wasm.length} bytes, sha256
 * ${WASM_SHA256}). It is compiled from the Rust in
 * tools/dark-cutout-wasm/src/lib.rs, a no_std crate with ZERO dependencies, and the build is
 * byte-reproducible. Three ways to check it, cheapest first:
 *   1. ./gradlew :app:test --tests "*DarkCutoutAssetsTest"   (no toolchain needed)
 *      Re-derives the sha256, proves the module declares NO imports — so it can call no host
 *      function and touch nothing but its own linear memory — and pins the export list below.
 *   2. Read the bytes:  cd tools/dark-cutout-wasm && ./extract-wasm.sh && wasm2wat dark_cutout.wasm
 *   3. Rebuild from source:  cd tools/dark-cutout-wasm && ./build.sh --check
 *      Recompiles src/lib.rs into a scratch target dir and fails if the result differs from what
 *      is committed here.
 *
 * Wasm sections present: ${info.sections.join(', ')} (no section 2 = no imports).
 * Exports: ${info.exports.join(', ')}.
 */
internal object DarkCutoutAssets {
    /** sha256 of the decoded [WASM_B64] bytes; see the audit note above. */
    const val WASM_SHA256: String = "${WASM_SHA256}"

    const val WASM_B64: String =
        "${WASM_B64}"

    const val WORKER_B64: String =
        "${WORKER_B64}"

    const val DRIVER_JS: String =
        ${JSON.stringify(DRIVER_FLAT)}
}
`;

const outKt = path.resolve(__dirname, '../../app/src/main/java/com/firebolt9907/tangentbrowser/ui/content/DarkCutoutAssets.kt');

if (CHECK) {
  const committed = fs.existsSync(outKt) ? fs.readFileSync(outKt, 'utf8') : '';
  if (committed === kt) {
    console.log('DarkCutoutAssets.kt is in sync with src/lib.rs, worker.js and driver.js.');
    console.log('  wasm sha256 =', WASM_SHA256, `(${wasm.length} bytes, no imports)`);
    process.exit(0);
  }
  console.error('DRIFT: DarkCutoutAssets.kt does not match a rebuild of the sources in this directory.');
  console.error('  rebuilt wasm sha256 =', WASM_SHA256, `(${wasm.length} bytes)`);
  const m = committed.match(/const val WASM_SHA256: String = "([0-9a-f]{64})"/);
  console.error('  committed wasm sha256 =', m ? m[1] : '(absent — regenerate with ./build.sh)');
  console.error('Run ./build.sh to regenerate, and commit the result with the sources.');
  process.exit(1);
}

fs.writeFileSync(outKt, kt);
console.log('wrote', outKt);
console.log('  wasm sha256 =', WASM_SHA256, `(${wasm.length} bytes, no imports)`);
console.log('  exports =', info.exports.join(', '));
console.log('  WASM_B64 len =', WASM_B64.length, ' WORKER_B64 len =', WORKER_B64.length, ' DRIVER_JS len =', DRIVER_FLAT.length);
console.log('  NOTE: if the sha256 changed, update EXPECTED_WASM_SHA256 in DarkCutoutAssetsTest.kt.');

// Also emit the flattened driver for the harness so it tests the exact shipping bytes.
fs.writeFileSync(path.resolve(__dirname, 'driver.flat.js'), DRIVER_FLAT);
