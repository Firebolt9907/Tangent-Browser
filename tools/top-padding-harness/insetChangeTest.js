// INSET-SIZE-CHANGE probe. Every other probe injects ONE topPx and asks whether that value is
// honoured. But topPx is not a constant on a phone: ContentHost re-injects when the SafariBar
// collapses or expands, and rotation / a different status-bar height changes it outright. So the
// script is routinely asked to go 48 -> 100 -> 48 within one page, on top of state it already
// applied.
//
// That is a different question from resizeTest.js, which changes the VIEWPORT and expects the inset
// to survive unchanged. Here the inset itself changes, and the hazards are the opposite ones:
// insets that COMPOUND (48 then 100 landing at 148) and values latched grow-only that never come
// back DOWN when the inset shrinks.
//
// Usage: node insetChangeTest.js [url] [--seq=48,100,48]
const puppeteer = require('puppeteer-core');
const { buildViewportSpacerScript } = require('./extractScript');

const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const args = process.argv.slice(2);
const url = args.find(a => !a.startsWith('--')) || `file://${__dirname}/fixtures/headers.html?case=fixedHeader`;
const seq = (args.find(a => a.startsWith('--seq=')) || '--seq=48,100,48').slice(6).split(',').map(Number);
const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';

const MEASURE = (topPx) => `(() => {
  const SPACER = 'tangent-top-scroll-spacer';
  const sp = document.getElementById(SPACER);
  const host = document.body || document.documentElement;
  // The topmost pinned bar: what the inset is supposed to be moving.
  let bar = null;
  document.querySelectorAll('header,nav,div').forEach(el => {
    const cs = getComputedStyle(el);
    if (cs.position !== 'fixed' && cs.position !== 'sticky') return;
    const r = el.getBoundingClientRect();
    if (r.width < innerWidth * 0.6 || r.height > 400 || r.top > ${topPx} + 60) return;
    if (!bar || r.top < bar.getBoundingClientRect().top) bar = el;
  });
  const spRect = sp ? sp.getBoundingClientRect() : null;
  return {
    spacerH: spRect && getComputedStyle(sp).display !== 'none' ? Math.round(spRect.height) : 0,
    hostPad: Math.round(parseFloat(getComputedStyle(host).paddingTop) || 0),
    shellPad: window.__tangentShellEl ? Math.round(parseFloat(getComputedStyle(window.__tangentShellEl).paddingTop) || 0) : null,
    barTop: bar ? Math.round(bar.getBoundingClientRect().top) : null,
    barPad: bar ? Math.round(parseFloat(getComputedStyle(bar).paddingTop) || 0) : null,
    spt: Math.round(parseFloat(getComputedStyle(document.documentElement).scrollPaddingTop) || 0),
    sptLatch: window.__tangentSptApplied || 0,
  };
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
    await new Promise(r => setTimeout(r, 900));

    console.log(`\n=== ${url} ===`);
    console.log(`sequence: ${seq.join(' -> ')}`);
    const rows = [];
    for (const topPx of seq) {
      await page.evaluate(buildViewportSpacerScript(topPx, false).replace(/^javascript:/, ''));
      await new Promise(r => setTimeout(r, 1400)); // past the walk's cooldown
      const m = await page.evaluate(MEASURE(topPx));
      rows.push({ topPx, ...m });
      console.log(`  topPx=${String(topPx).padStart(3)}  spacer=${m.spacerH}  hostPad=${m.hostPad}  shellPad=${m.shellPad}  barTop=${m.barTop}  barPad=${m.barPad}  scroll-padding-top=${m.spt} (latch ${m.sptLatch})`);
    }

    const bad = [];
    for (const r of rows) {
      // Exactly one mechanism should be carrying the inset, and it must equal topPx — not a sum of
      // this inset and the last one.
      const applied = Math.max(r.spacerH, r.hostPad, r.shellPad || 0, r.barPad || 0);
      if (Math.abs(applied - r.topPx) > 2) bad.push(`topPx=${r.topPx}: inset applied is ${applied}, expected ${r.topPx}`);
      // A bar pinned at the top must land AT the inset, never below a doubled one.
      if (r.barTop !== null && r.barTop > r.topPx + 2) bad.push(`topPx=${r.topPx}: pinned bar sits at ${r.barTop}, expected ~${r.topPx}`);
      // scroll-padding-top may exceed topPx (it also clears a site's own nav bar) but must never be
      // stuck at a LARGER inset's value after the inset shrinks.
      if (r.spt > r.topPx + 80) bad.push(`topPx=${r.topPx}: scroll-padding-top=${r.spt} looks latched from a bigger inset`);
    }

    console.log(`\nRESULT: ${bad.length ? `FAIL ❌ ${bad.join('; ')}` : 'PASS ✅ each inset lands exactly, nothing compounds or stays latched'}`);
  } finally {
    await browser.close();
  }
})();
