// BAND-FILL probe. Every other probe asks whether the band is CLEAR (no content in it). This one
// asks whether it looks RIGHT: a site with a solid-coloured navbar should have that colour running
// all the way up to the top bezel, not a transparent strip above it showing the page background.
//
// Structurally different from stripColor.js, which reads the spacer div's *declared* CSS: that div
// is `display:none` on the host-padding and app-shell paths, and irrelevant when a fixed header
// covers the band itself, so it can only see one of the four inset mechanisms. This probe
// screenshots the viewport and compares actual PAINTED PIXELS — mechanism-agnostic, and the same
// thing the user's eye is doing.
//
// Usage: node bandFill.js <url> [topPx]
const puppeteer = require('puppeteer-core');
const { PNG } = require('pngjs');
const { buildViewportSpacerScript } = require('./extractScript');

const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const url = process.argv[2];
const topPx = parseInt(process.argv[3] || '48', 10);
if (!url) { console.error('usage: node bandFill.js <url> [topPx]'); process.exit(1); }

const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';
const TOL = 12;       // per-channel tolerance; below this the strip reads as the same colour
const SAMPLE_XS = [0.15, 0.35, 0.5, 0.65, 0.85];

const dist = (a, b) => Math.max(Math.abs(a[0] - b[0]), Math.abs(a[1] - b[1]), Math.abs(a[2] - b[2]));
const hex = (p) => '#' + p.slice(0, 3).map(v => v.toString(16).padStart(2, '0')).join('');

(async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME, headless: 'new', args: ['--no-sandbox', '--hide-scrollbars'],
    defaultViewport: { width: 412, height: 915, isMobile: true, hasTouch: true, deviceScaleFactor: 1 },
  });
  try {
    const page = await browser.newPage();
    await page.setUserAgent(MOBILE_UA);
    await page.goto(url, { waitUntil: 'networkidle2', timeout: 45000 }).catch(e => console.error('nav warn:', e.message));
    await new Promise(r => setTimeout(r, 1200));
    await page.evaluate(buildViewportSpacerScript(topPx, false).replace(/^javascript:/, ''));
    await new Promise(r => setTimeout(r, 2000));

    // Which mechanism ran — reported so a failure points at the right code path.
    const mech = await page.evaluate((id) => {
      const sp = document.getElementById(id);
      const host = document.body || document.documentElement;
      if (window.__tangentShellEl) return 'shell';
      if (host && host.dataset.tangentHostPadSaved === 'true') return 'hostpad';
      if (sp && getComputedStyle(sp).display !== 'none') return 'spacer';
      return 'none';
    }, 'tangent-top-scroll-spacer');
    const insetCount = await page.evaluate('(window.__tangentInsetedEls||[]).length');

    // Screenshot the plain viewport — NEVER pass `clip`. A clipped screenshot goes through
    // "capture beyond viewport", which re-renders the page and drops the inline styles we injected,
    // so the strip comes back unpainted and every site with a coloured bar reads as a FAIL.
    // (Caught on wikipedia: a spacer forced to red still screenshotted white under `clip`.)
    // puppeteer returns a Uint8Array on newer versions; pngjs wants a Buffer.
    const png = PNG.sync.read(Buffer.from(await page.screenshot()));
    const at = (x, y) => { const i = (png.width * y + x) << 2; return [png.data[i], png.data[i + 1], png.data[i + 2]]; };

    const rows = [];
    for (const fx of SAMPLE_XS) {
      const x = Math.floor(png.width * fx);
      const band = at(x, Math.max(1, Math.floor(topPx / 2)));   // middle of the reserved strip
      const below = at(x, topPx + 6);                            // the navbar just under it
      rows.push({ x, band, below, d: dist(band, below) });
    }

    // A gradient/photo header legitimately changes colour vertically, so only call it a mismatch
    // when the strip is uniform AND differs from a uniform bar below it — the flat-colour navbar
    // case the user actually notices.
    const bandUniform = rows.every(r => dist(r.band, rows[0].band) <= TOL);
    const belowUniform = rows.every(r => dist(r.below, rows[0].below) <= TOL);
    const worst = Math.max(...rows.map(r => r.d));

    console.log(`\n=== ${url}  topPx=${topPx} ===`);
    console.log(`mechanism: ${mech}   headers inset: ${insetCount}`);
    for (const r of rows) console.log(`  x=${String(r.x).padStart(3)}  band ${hex(r.band)}  below ${hex(r.below)}  Δ=${r.d}`);
    console.log(`band uniform: ${bandUniform}   bar uniform: ${belowUniform}   worst Δ: ${worst}`);

    let verdict;
    if (!belowUniform) verdict = `N/A ⚠️  the bar under the strip is not a flat colour (gradient/photo/logo) — nothing to match`;
    else if (worst <= TOL) verdict = `PASS ✅ the bar's colour runs to the top bezel (Δ=${worst})`;
    else if (!bandUniform) verdict = `N/A ⚠️  strip is not a flat colour either — inspect manually`;
    else verdict = `FAIL ❌ transparent/mismatched strip above a flat ${hex(rows[0].below)} bar (band ${hex(rows[0].band)}, Δ=${worst})`;
    console.log(`\nRESULT: ${verdict}`);
  } finally {
    await browser.close();
  }
})();
