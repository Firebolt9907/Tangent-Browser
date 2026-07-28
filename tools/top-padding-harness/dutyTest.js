// EXPOSURE-DUTY probe. run.js asks "is the band clear once things settle". latencyTest asks "how
// long after a mount". Neither can see a header that is inset, clobbered by the site's own inline
// styles, re-inset, clobbered again — flickering under the status bar for a large fraction of the
// time even though every settled snapshot looks clean.
//
// Samples the band continuously and reports the fraction of samples with real ink in it.
//
// Usage: node dutyTest.js <url> [topPx] [sampleMs]
const puppeteer = require('puppeteer-core');
const { buildViewportSpacerScript } = require('./extractScript');

const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const url = process.argv[2];
const topPx = parseInt(process.argv[3] || '48', 10);
const sampleMs = parseInt(process.argv[4] || '6000', 10);
if (!url) { console.error('usage: node dutyTest.js <url> [topPx] [sampleMs]'); process.exit(1); }
const BUDGET = 0.05; // at most 5% of samples may show band ink

const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';

// In-page sampler: rAF loop counting frames where readable ink paints in the band. Runs in the page
// so the sampling rate isn't limited by CDP round-trips.
const SAMPLER = (band) => `(() => {
  const BAND = ${band}, SPACER = 'tangent-top-scroll-spacer';
  window.__duty = { frames: 0, dirty: 0, worst: 0 };
  function inkInBand() {
    let n = 0;
    const all = document.querySelectorAll('header,nav,div,span,a,button,img,svg,p,h1,h2');
    for (let i = 0; i < all.length && i < 3000; i++) {
      const el = all[i];
      if (el.id === SPACER || el.closest('#'+SPACER)) continue;
      const r = el.getBoundingClientRect();
      if (!(r.top < BAND - 1 && r.bottom > 1)) continue;
      if (Math.min(BAND, r.bottom) - Math.max(0, r.top) < 6) continue;
      if (r.width < 6 || r.height < 6) continue;
      const cs = getComputedStyle(el);
      if (cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity) === 0) continue;
      const directText = Array.from(el.childNodes).some(k => k.nodeType === 3 && k.textContent.trim().length);
      const isMedia = ['img','svg'].includes(el.tagName.toLowerCase());
      if (!((directText && el.children.length === 0) || isMedia)) continue;
      n++;
    }
    return n;
  }
  function tick() {
    window.__duty.frames++;
    const n = inkInBand();
    if (n > 0) { window.__duty.dirty++; window.__duty.worst = Math.max(window.__duty.worst, n); }
    window.__dutyRaf = requestAnimationFrame(tick);
  }
  tick();
})()`;

(async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: ['--no-sandbox', '--hide-scrollbars'],
    defaultViewport: { width: 412, height: 915, isMobile: true, hasTouch: true, deviceScaleFactor: 2.625 },
  });
  try {
    const page = await browser.newPage();
    await page.setUserAgent(MOBILE_UA);
    await page.goto(url, { waitUntil: 'networkidle2', timeout: 45000 }).catch(e => console.error('nav warn:', e.message));
    await new Promise(r => setTimeout(r, 1200));
    await page.evaluate(buildViewportSpacerScript(topPx, false).replace(/^javascript:/, ''));
    await new Promise(r => setTimeout(r, 2000));

    await page.evaluate(SAMPLER(topPx));
    await new Promise(r => setTimeout(r, sampleMs));
    const d = await page.evaluate('(() => { cancelAnimationFrame(window.__dutyRaf); return window.__duty; })()');

    const ratio = d.frames ? d.dirty / d.frames : 0;
    console.log(`\n=== ${url}  topPx=${topPx}  window=${sampleMs}ms ===`);
    console.log(`frames sampled: ${d.frames}  frames with band ink: ${d.dirty}  worst-frame ink: ${d.worst}`);
    console.log(`exposure duty cycle: ${(ratio * 100).toFixed(1)}%`);
    console.log(`\nRESULT: ${ratio <= BUDGET ? `PASS ✅ band clear ${(100 - ratio * 100).toFixed(1)}% of frames` : `FAIL ❌ band content visible in ${(ratio * 100).toFixed(1)}% of frames (budget ${BUDGET * 100}%)`}`);
  } finally {
    await browser.close();
  }
})();
