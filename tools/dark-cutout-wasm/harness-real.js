// Validates the REAL shipping cutout against a live site — the exact flattened driver
// (driver.flat.js, the bytes embedded in DarkCutoutAssets.DRIVER_JS), the real worker.js, and the
// compiled wasm — instead of the quality-first inline reimplementation in harness.js. This is the
// only harness that exercises the actual code path users run, including:
//   - the Web Worker path (default), and
//   - the main-thread CSP fallback (lumCutSetupMain) when `--force-main` disables `new Worker`.
// Usage: node harness-real.js [url] [out.png] [--force-main]
const fs = require('fs');
const path = require('path');
const puppeteer = require('puppeteer-core');
const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';
const KT = path.resolve(__dirname, '../../app/src/main/java/com/firebolt9907/luminabrowser/ui/content/WebDarkMode.kt');

// The real shipping assets. driver.flat.js + a freshly base64'd worker.js + the compiled wasm — so a
// stale build fails loudly rather than testing old bytes. Run build.sh first.
const DRIVER = fs.readFileSync(path.resolve(__dirname, 'driver.flat.js'), 'utf8');
const WASM_B64 = fs.readFileSync(path.resolve(__dirname, 'wasm.b64'), 'utf8').trim();
const WORKER_B64 = Buffer.from(fs.readFileSync(path.resolve(__dirname, 'worker.js'), 'utf8'), 'utf8').toString('base64');

// The element-bg dark recolor (buildDynamicScript), so the page background is dark and the cutout's
// transparency actually reveals the dark page — same extraction harness.js uses.
function extractDynamic() {
  const src = fs.readFileSync(KT, 'utf8');
  const start = src.indexOf('internal fun buildDynamicScript');
  const q1 = src.indexOf('"""', start), q2 = src.indexOf('"""', q1 + 3);
  let tpl = src.slice(q1 + 3, q2);
  const lines = tpl.split('\n');
  while (lines.length && lines[0].trim() === '') lines.shift();
  while (lines.length && lines[lines.length - 1].trim() === '') lines.pop();
  const indent = Math.min(...lines.filter(l => l.trim()).map(l => l.match(/^\s*/)[0].length));
  tpl = lines.map(l => l.slice(indent)).join('\n');
  tpl = tpl.replace("${DYN_STYLE_ID}", 'lumina-dyn-style').replace("${DYN_CLASS}", 'lumina-dyn')
           .replace("${DYN_BASE_BG}", '#1c1c1e').replace("${DYN_SCAN_CAP}", '8000');
  return tpl.replace(/\n/g, ' ').replace(/^javascript:/, '');
}

// Parse the CUT_* tuning constants straight out of WebDarkMode.kt so the harness can never drift from
// the shipping params (a hand-copied list is exactly how the two get out of sync).
function shippingParams() {
  const src = fs.readFileSync(KT, 'utf8');
  const num = name => { const m = src.match(new RegExp('const val ' + name + '\\s*=\\s*"?([0-9.]+)"?')); if (!m) throw new Error('param not found: ' + name); return parseFloat(m[1]); };
  return {
    minSize: num('CUT_MIN_SIZE'), maxDim: num('CUT_MAX_DIM'), maxDimMain: num('CUT_MAX_DIM_MAIN'),
    lSeed: num('CUT_L_SEED'), lFloor: num('CUT_L_FLOOR'), stepMax: num('CUT_STEP_MAX'),
    minAlpha: num('CUT_MIN_ALPHA'), colorBand: num('CUT_COLOR_BAND'),
    lEdge: num('CUT_L_EDGE'), defringeIters: num('CUT_DEFRINGE_ITERS'),
    minFrac: num('CUT_MIN_FRAC'), maxFrac: num('CUT_MAX_FRAC'),
    mlReady: false, bridge: '',   // force the WASM path (no native ML in a plain browser)
  };
}

(async () => {
  const url = process.argv[2] && !process.argv[2].startsWith('--') ? process.argv[2] : 'https://www.apple.com';
  const out = process.argv[3] && !process.argv[3].startsWith('--') ? process.argv[3] : path.resolve(__dirname, 'out-real.png');
  const forceMain = process.argv.includes('--force-main');
  const params = shippingParams();

  const browser = await puppeteer.launch({ executablePath: CHROME, headless: 'new',
    args: ['--no-sandbox', '--hide-scrollbars'],
    defaultViewport: { width: 412, height: 915, isMobile: true, hasTouch: true, deviceScaleFactor: 2 } });
  try {
    const page = await browser.newPage();
    await page.setUserAgent(MOBILE_UA);
    await page.emulateMediaFeatures([{ name: 'prefers-color-scheme', value: 'dark' }]);
    // Simulate a CSP that blocks Worker/blob URLs: kill `new Worker` before any page script runs, so
    // lumCutSetup's catch takes the real main-thread fallback (lumCutSetupMain).
    if (forceMain) {
      await page.evaluateOnNewDocument(() => {
        try { Object.defineProperty(window, 'Worker', { configurable: true, get() { throw new Error('blocked by test CSP'); } }); } catch (e) {}
      });
    }
    await page.goto(url, { waitUntil: 'networkidle2', timeout: 45000 }).catch(e => console.error('nav', e.message));
    await new Promise(r => setTimeout(r, 1500));
    await page.evaluate(async () => { for (let y = 0; y < 10; y++) { window.scrollBy(0, window.innerHeight); await new Promise(r => setTimeout(r, 300)); } window.scrollTo(0, 0); });
    await new Promise(r => setTimeout(r, 1200));
    await page.evaluate(extractDynamic());
    await new Promise(r => setTimeout(r, 800));
    // Inject the REAL driver exactly as buildImageCutoutScript does: declare its functions in a scope,
    // then call lumCutSetup(WASM_B64, WORKER_B64, PARAMS).
    await page.evaluate((driver, wasm, worker, p) => {
      new Function('WASM_B64', 'WORKER_B64', 'PARAMS', driver + '\n;lumCutSetup(WASM_B64, WORKER_B64, PARAMS);')(wasm, worker, p);
    }, DRIVER, WASM_B64, WORKER_B64, params);
    await new Promise(r => setTimeout(r, 2500));
    await page.evaluate(() => window.__lumCutSweep && window.__lumCutSweep());
    await new Promise(r => setTimeout(r, 1500));
    const info = await page.evaluate(() => {
      const st = window.__lumCut || {};
      const masked = Array.prototype.filter.call(document.getElementsByTagName('img'), i => i.style && i.style.maskImage).length;
      return { path: st.ml ? 'ml' : st.main ? 'main' : st.worker !== undefined ? 'worker' : 'none', masked };
    });
    console.log('REAL driver path:', info.path, forceMain ? '(forced main)' : '', '| images masked:', info.masked, '| params:', JSON.stringify(params));
    if (forceMain && info.path !== 'main') console.error('WARN: expected main-thread fallback but ran path=' + info.path);

    if (process.argv.includes('--swap')) {
      // Simulate a carousel swapping a processed <img>'s src to a different image, then verify the
      // REAL driver re-runs its pipeline for the new source (the observer's attribute path).
      const swap = await page.evaluate(async () => {
        var imgs = Array.prototype.slice.call(document.getElementsByTagName('img'));
        var target = imgs.find(function (i) { return i.__lumCut && i.__lumCutSrc; });
        if (!target) return { ok: false, reason: 'no processed image found' };
        var other = imgs.find(function (i) { return (i.currentSrc || i.src) && (i.currentSrc || i.src) !== target.__lumCutSrc; });
        if (!other) return { ok: false, reason: 'no distinct second source found' };
        var before = target.__lumCutSrc;
        var newSrc = other.currentSrc || other.src;
        // Neutralize <picture>/srcset so the swap sticks, then swap the src.
        if (target.parentElement && target.parentElement.tagName === 'PICTURE') {
          Array.prototype.forEach.call(target.parentElement.querySelectorAll('source'), function (s) { s.remove(); });
        }
        target.removeAttribute('srcset');
        target.src = newSrc;
        await new Promise(function (r) { setTimeout(r, 2500); });
        return { ok: true, before: before, newSrc: newSrc, after: target.__lumCutSrc, reprocessed: target.__lumCutSrc === newSrc };
      });
      if (!swap.ok) console.log('swap test skipped:', swap.reason);
      else console.log('swap test:', swap.reprocessed ? 'PASS' : 'FAIL',
        '- driver re-processed on src change:', swap.reprocessed, '(', swap.before, '->', swap.after, ')');
    }
    await page.screenshot({ path: out, fullPage: false });
    console.log('shot ->', out);
  } finally { await browser.close(); }
})();
