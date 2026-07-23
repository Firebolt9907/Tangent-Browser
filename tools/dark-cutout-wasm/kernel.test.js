// Deterministic correctness tests for the WASM cutout kernel — feeds synthetic RGBA buffers and
// asserts which pixels get keyed. Run: node kernel.test.js
const fs = require('fs');
const path = require('path');
const wasmBytes = fs.readFileSync(path.resolve(__dirname, 'target/wasm32-unknown-unknown/release/dark_cutout_wasm.wasm'));

const L_SEED = 210, L_FLOOR = 170, STEP = 5, MIN_A = 128, BAND = 70;
let pass = 0, fail = 0;
function ok(cond, msg) { if (cond) { pass++; } else { fail++; console.log('  FAIL:', msg); } }

(async () => {
  const { instance } = await WebAssembly.instantiate(wasmBytes, {});
  const ex = instance.exports;
  const CAP = ex.capacity();
  const pptr = ex.pixels_ptr();
  console.log('capacity =', CAP);

  function run(w, h, fillPixel) {
    const view = new Uint8Array(ex.memory.buffer, pptr, w * h * 4);
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
      const [r, g, b, a] = fillPixel(x, y);
      const o = (y * w + x) * 4; view[o] = r; view[o + 1] = g; view[o + 2] = b; view[o + 3] = a;
    }
    const filled = ex.cutout(w, h, L_SEED, L_FLOOR, STEP, MIN_A, BAND);
    return { filled, alphaAt: (x, y) => view[(y * w + x) * 4 + 3] };
  }

  // 1. White field with a dark square in the middle: white keyed, dark square preserved.
  {
    const w = 60, h = 60;
    const r = run(w, h, (x, y) => (x >= 24 && x < 36 && y >= 24 && y < 36) ? [20, 20, 20, 255] : [255, 255, 255, 255]);
    ok(r.alphaAt(2, 2) === 0, 'white border keyed');
    ok(r.alphaAt(30, 30) === 255, 'dark center square preserved');
    ok(r.filled === w * h - 12 * 12, `filled all-but-square (got ${r.filled})`);
    console.log(`1. product-on-white: filled=${r.filled}/${w * h}`);
  }

  // 2. Light-blue -> white smooth gradient with a hard-edged dark object: whole gradient keyed,
  //    object preserved (the apple hero case).
  {
    const w = 80, h = 80;
    const r = run(w, h, (x, y) => {
      if (x >= 30 && x < 50 && y >= 30 && y < 50) return [10, 40, 90, 255]; // dark blue object
      const t = y / h; const rr = Math.round(200 + 55 * t), gg = Math.round(225 + 30 * t), bb = 255;
      return [rr, gg, bb, 255];
    });
    ok(r.alphaAt(2, 2) === 0, 'gradient top keyed');
    ok(r.alphaAt(2, 78) === 0, 'gradient bottom keyed');
    ok(r.alphaAt(40, 40) === 255, 'dark object preserved through gradient');
    console.log(`2. gradient+object: filled=${r.filled}/${w * h}`);
  }

  // 3. Full-bleed mid-tone photo (colorful, no light border): nothing keyed -> host skips it.
  {
    const w = 64, h = 64;
    const r = run(w, h, (x, y) => [80 + (x % 40), 40 + (y % 30), 120 + ((x + y) % 30), 255]);
    ok(r.filled === 0, `photo untouched (filled=${r.filled})`);
    console.log(`3. full-bleed photo: filled=${r.filled}/${w * h}`);
  }

  // 4. Dark photo with a light border matte should key ONLY the matte, keep the dark photo.
  {
    const w = 64, h = 64;
    const r = run(w, h, (x, y) => {
      const border = x < 6 || x >= w - 6 || y < 6 || y >= h - 6;
      return border ? [250, 250, 250, 255] : [30, 30, 30, 255];
    });
    ok(r.alphaAt(1, 1) === 0, 'matte keyed');
    ok(r.alphaAt(32, 32) === 255, 'dark interior preserved');
    console.log(`4. matte border: filled=${r.filled}/${w * h}`);
  }

  // 5. Transparent-background cutout (people sticker): transparent border NOT a seed (min alpha),
  //    subject preserved; nothing keyed by us since page shows through transparency already.
  {
    const w = 64, h = 64;
    const r = run(w, h, (x, y) => {
      const inSubject = x >= 20 && x < 44 && y >= 20 && y < 44;
      return inSubject ? [200, 120, 90, 255] : [0, 0, 0, 0];
    });
    ok(r.filled === 0, `transparent-matte image untouched (filled=${r.filled})`);
    console.log(`5. transparent cutout: filled=${r.filled}/${w * h}`);
  }

  // 6. De-fringe peels the light halo ring the fill leaves short of the subject, keeps the dark edge.
  //    A light field (255) around a dark square (20) with a one-pixel LIGHT-GREY seam (200) that the
  //    fill can't cross (step 255->200 = 55 > STEP): the seam stays opaque after cutout, then
  //    defringe (l_edge below 200) keys it out, while the dark square (20 < l_edge) is preserved.
  {
    const w = 40, h = 40, lo = 12, hi = 28; // dark square [12,28)
    const view = new Uint8Array(ex.memory.buffer, pptr, w * h * 4);
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
      const inSq = x >= lo && x < hi && y >= lo && y < hi;
      const onSeam = !inSq && x >= lo - 1 && x < hi + 1 && y >= lo - 1 && y < hi + 1;
      const [r, g, b] = inSq ? [20, 20, 20] : onSeam ? [200, 200, 200] : [255, 255, 255];
      const o = (y * w + x) * 4; view[o] = r; view[o + 1] = g; view[o + 2] = b; view[o + 3] = 255;
    }
    const filled = ex.cutout(w, h, L_SEED, L_FLOOR, STEP, MIN_A, BAND);
    const a = (x, y) => view[(y * w + x) * 4 + 3];
    ok(a(lo - 1, 20) === 255, 'light seam survives cutout (halo present)'); // seam opaque pre-defringe
    ok(a(lo, 20) === 255, 'dark square opaque after cutout');
    ex.defringe(w, h, 150, 2);
    ok(a(lo - 1, 20) === 0, 'light seam keyed out by defringe');           // halo peeled
    ok(a(lo, 20) === 255, 'dark square preserved through defringe');       // subject kept
    ok(a(2, 2) === 0, 'background still keyed');
    console.log(`6. defringe: seam keyed, subject kept (filled=${filled})`);
  }

  // 7. Subject-aware defringe: a LIGHT product on a LIGHT background must NOT be eroded. White field
  //    (255) with a slightly-off-white product (238) the flood fill halts at (step 255->238 = 51 >
  //    STEP). After cutout the product is opaque; defringe must leave its edge intact because the
  //    pixel INWARD from the keyed edge is light (the product interior), not a dark subject.
  {
    const w = 40, h = 40, lo = 12, hi = 28;
    const view = new Uint8Array(ex.memory.buffer, pptr, w * h * 4);
    for (let y = 0; y < h; y++) for (let x = 0; x < w; x++) {
      const inProd = x >= lo && x < hi && y >= lo && y < hi;
      const v = inProd ? 238 : 255;
      const o = (y * w + x) * 4; view[o] = v; view[o + 1] = v; view[o + 2] = v; view[o + 3] = 255;
    }
    const filled = ex.cutout(w, h, L_SEED, L_FLOOR, STEP, MIN_A, BAND);
    const a = (x, y) => view[(y * w + x) * 4 + 3];
    ok(a(lo, 20) === 255 && a(hi - 1, 20) === 255, 'light product opaque after cutout');
    ex.defringe(w, h, 150, 2);
    ok(a(lo, 20) === 255, 'light product left edge NOT eroded');           // subject-aware: preserved
    ok(a(hi - 1, 20) === 255, 'light product right edge NOT eroded');
    ok(a(lo + 2, 20) === 255, 'light product interior intact');
    ok(a(1, 1) === 0, 'background still keyed');
    console.log(`7. defringe subject-aware: light product kept (filled=${filled})`);
  }

  console.log(`\n${pass} passed, ${fail} failed`);
  process.exit(fail ? 1 : 0);
})();
