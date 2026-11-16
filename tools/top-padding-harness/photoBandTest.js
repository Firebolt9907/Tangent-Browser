// PHOTO-BAND probe. bandFill.js only judges strips above a FLAT bar and reports N/A when the
// surface under the strip is a photo — which is exactly where the strip used to look worst: the
// photo's pixels are cross-origin, so the strip took the element's fallback background-color, a
// flat shade that can be nothing like the photo (drxbox.com: #44b244 above dark textured green).
//
// This compares the strip's PAINTED pixels with the same-height band directly below it, as
// per-column means (a photo varies, but two adjacent bands of the same photo average alike; a flat
// fallback colour above a dark photo does not), and checks content moved by the same distance the
// in-flow spacer would have moved it, so the fix cannot "win" by changing the layout.
//
// Usage: node photoBandTest.js <url> [topPx]
//        ./photobg.sh        (offline fixtures/photobg.html cases)
const puppeteer = require('puppeteer-core');
const { PNG } = require('pngjs');
const { buildViewportSpacerScript } = require('./extractScript');

const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const url = process.argv[2];
const topPx = parseInt(process.argv[3] || '48', 10);
if (!url) { console.error('usage: node photoBandTest.js <url> [topPx]'); process.exit(1); }
const flowTopPx = Math.round(topPx * 0.6); // mirrors flowTopPx in PageBottomSpacer.kt
const TOL = 24; // max per-channel difference of band means that still reads as one surface
const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';

// The first readable text block near the top, used to measure how far content moved.
const probeTop = `(() => {
  const el = document.querySelector('.probe') || [...document.querySelectorAll('h1,h2,h3,p,a,button')]
    .find(e => { const r = e.getBoundingClientRect(); return r.width > 20 && r.height > 6 && r.top >= 0 && r.top < 400 && e.textContent.trim(); });
  return el ? el.getBoundingClientRect().top + window.scrollY : null;
})()`;

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
    const before = await page.evaluate(probeTop);
    await page.evaluate(buildViewportSpacerScript(topPx, false).replace(/^javascript:/, ''));
    await new Promise(r => setTimeout(r, 2000));
    const after = await page.evaluate(probeTop);
    const mech = await page.evaluate(() => {
      const sp = document.getElementById('tangent-top-scroll-spacer');
      if (window.__tangentShellEl) return 'shell';
      if (window.__tangentBleedEl) return 'bleed:' + window.__tangentBleedEl.tagName.toLowerCase();
      if (document.body && document.body.dataset.tangentHostPadSaved === 'true') return 'hostpad';
      if (sp && getComputedStyle(sp).display !== 'none') return 'spacer(' + (sp.style.backgroundColor || 'none') + ')';
      return 'none';
    });

    const png = PNG.sync.read(Buffer.from(await page.screenshot()));
    const mean = (y0, y1, x0, x1) => {
      const s = [0, 0, 0]; let n = 0;
      for (let y = y0; y < y1; y++) for (let x = x0; x < x1; x++) { const i = (png.width * y + x) << 2; s[0] += png.data[i]; s[1] += png.data[i + 1]; s[2] += png.data[i + 2]; n++; }
      return s.map(v => Math.round(v / n));
    };
    const hex = (p) => '#' + p.map(v => v.toString(16).padStart(2, '0')).join('');
    // Compare in 4 vertical slices so a centred logo or a side gutter cannot average itself away.
    const cols = 4, w = png.width / cols, rows = [];
    for (let i = 0; i < cols; i++) {
      const x0 = Math.floor(i * w), x1 = Math.floor((i + 1) * w);
      const strip = mean(0, flowTopPx, x0, x1), below = mean(flowTopPx, flowTopPx * 2, x0, x1);
      rows.push({ strip, below, d: Math.max(...strip.map((v, k) => Math.abs(v - below[k]))) });
    }
    const worst = Math.max(...rows.map(r => r.d));
    const shift = before != null && after != null ? Math.round(after - before) : null;

    console.log(`\n=== ${url}  topPx=${topPx} flowTopPx=${flowTopPx} ===`);
    console.log(`mechanism: ${mech}   content shift: ${shift}px`);
    rows.forEach((r, i) => console.log(`  slice ${i}  strip ${hex(r.strip)}  below ${hex(r.below)}  Δ=${r.d}`));
    const shiftOk = shift == null || Math.abs(shift - flowTopPx) <= 3 || shift >= flowTopPx;
    let verdict;
    if (!shiftOk) verdict = `FAIL ❌ content moved ${shift}px, expected ~${flowTopPx}px`;
    else if (worst <= TOL) verdict = `PASS ✅ strip reads as the surface below it (worst Δ=${worst})`;
    else verdict = `FAIL ❌ strip does not match the surface below it (worst Δ=${worst})`;
    console.log(`\nRESULT: ${verdict}`);
  } finally {
    await browser.close();
  }
})();
