// End-to-end harness for the SHIPPING worker pipeline: element recolor (from WebDarkMode.kt) +
// the real worker.js + driver.js + compiled wasm, against a live site. Proves the worker path works
// under the page's real CSP (Puppeteer enforces it). Usage: node harness-worker.js [url] [out.png]
const fs = require('fs');
const path = require('path');
const puppeteer = require('puppeteer-core');
const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';
const WASM_B64 = fs.readFileSync(path.resolve(__dirname, 'wasm.b64'), 'utf8').trim();
const WORKER_SRC = fs.readFileSync(path.resolve(__dirname, 'worker.js'), 'utf8');
const WORKER_B64 = Buffer.from(WORKER_SRC, 'utf8').toString('base64');
// Prefer the flattened one-liner (the exact bytes DarkCutoutAssets.kt ships) when present.
const flat = path.resolve(__dirname, 'driver.flat.js');
const DRIVER = fs.existsSync(flat) ? fs.readFileSync(flat, 'utf8') : fs.readFileSync(path.resolve(__dirname, 'driver.js'), 'utf8');
const KT = path.resolve(__dirname, '../../app/src/main/java/com/firebolt9907/tangentbrowser/ui/content/WebDarkMode.kt');

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
  tpl = tpl.replace("${DYN_STYLE_ID}", 'tangent-dyn-style').replace("${DYN_CLASS}", 'tangent-dyn')
           .replace("${DYN_BASE_BG}", '#1c1c1e').replace("${DYN_SCAN_CAP}", '8000');
  return tpl.replace(/\n/g, ' ').replace(/^javascript:/, '');
}

const PARAMS = { minSize: 100, maxDim: 480, lSeed: 195, lFloor: 150, stepMax: 30, minAlpha: 128, minFrac: 0.02, maxFrac: 0.985 };

(async () => {
  const url = process.argv[2] || 'https://www.apple.com';
  const out = process.argv[3] || path.resolve(__dirname, 'out-worker.png');
  const browser = await puppeteer.launch({ executablePath: CHROME, headless: 'new',
    args: ['--no-sandbox', '--hide-scrollbars'],
    defaultViewport: { width: 412, height: 915, isMobile: true, hasTouch: true, deviceScaleFactor: 2 } });
  try {
    const page = await browser.newPage();
    await page.setUserAgent(MOBILE_UA);
    await page.setBypassCSP(false); // enforce the page CSP, like GeckoView does
    await page.emulateMediaFeatures([{ name: 'prefers-color-scheme', value: 'dark' }]);
    page.on('pageerror', e => console.log('  pageerror:', e.message));
    page.on('console', m => { if (m.type() === 'error') console.log('  console.error:', m.text()); });
    await page.goto(url, { waitUntil: 'networkidle2', timeout: 45000 }).catch(e => console.error('nav', e.message));
    await new Promise(r => setTimeout(r, 1500));
    await page.evaluate(extractDynamic());
    await new Promise(r => setTimeout(r, 600));
    // Inject the driver function, then call it with the real blobs — mirrors buildImageCutoutScript.
    await page.evaluate(DRIVER);
    await page.evaluate((w, wk, p) => lumCutSetup(w, wk, p), WASM_B64, WORKER_B64, PARAMS);
    await new Promise(r => setTimeout(r, 2500));
    await page.evaluate(() => window.__lumCutSweep && window.__lumCutSweep());
    await new Promise(r => setTimeout(r, 1200));
    const diag = await page.evaluate(() => ({ dead: window.__lumCut && window.__lumCut.dead, ready: window.__lumCut && window.__lumCut.ready }));
    console.log('worker diag:', JSON.stringify(diag));
    await page.screenshot({ path: out, fullPage: false });
    console.log('shot ->', out);
  } finally { await browser.close(); }
})();
