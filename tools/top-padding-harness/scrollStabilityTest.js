// SCROLL-STABILITY probe. Every other probe injects at scroll-top. But ContentHost injects on FCP
// *and* again on load-complete, and the observer re-runs a pass whenever the DOM changes — all of
// which can happen while the user is already scrolled down and reading.
//
// Adding a 48px in-flow spacer (or padding the body/shell) above the current scroll position moves
// every element down by topPx. If the browser's scroll anchoring doesn't compensate — and app-ish
// pages routinely set `overflow-anchor:none` — the page visibly JUMPS under the reader's finger.
// Worse, the walk restores and re-applies insets on every pass, so a jump could repeat.
//
// Metric: pick a reference element visible mid-viewport, record its viewport-relative y, then
// (a) inject while scrolled and (b) force several more passes. The reference must not move.
//
// Usage: node scrollStabilityTest.js <url> [topPx] [scrollY]
const puppeteer = require('puppeteer-core');
const { buildViewportSpacerScript } = require('./extractScript');

const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const url = process.argv[2];
const topPx = parseInt(process.argv[3] || '48', 10);
const scrollY = parseInt(process.argv[4] || '1200', 10);
if (!url) { console.error('usage: node scrollStabilityTest.js <url> [topPx] [scrollY]'); process.exit(1); }
const TOLERANCE = 4;

const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';

// Mark a stable text-bearing element near the middle of the viewport and report where it sits.
const MARK = `(() => {
  const mid = window.innerHeight / 2;
  let best = null, bestD = 1e9;
  document.querySelectorAll('*').forEach(el => {
    if (el.id === 'tangent-top-scroll-spacer' || el.closest('#tangent-top-scroll-spacer')) return;
    const tag = el.tagName.toLowerCase();
    if (['html','body','head','script','style','link','meta','noscript'].includes(tag)) return;
    if (el.children.length) return;
    const t = (el.textContent || '').trim();
    if (t.length < 8) return;
    const cs = getComputedStyle(el);
    if (cs.display === 'none' || cs.visibility === 'hidden') return;
    const r = el.getBoundingClientRect();
    if (r.width < 40 || r.height < 8 || r.top < 0 || r.bottom > window.innerHeight) return;
    /* Only ordinary document-flow content can measure a scroll jump. Content inside a fixed/sticky
       container (the guardian's full-screen fixed overlay) is SUPPOSED to move down by topPx when we
       inset that container — counting it reads a correct inset as a jump. */
    let fixedAncestor = false;
    for (let n = el; n && n !== document.documentElement; n = n.parentElement) {
      const p = getComputedStyle(n).position;
      if (p === 'fixed' || p === 'sticky') { fixedAncestor = true; break; }
    }
    if (fixedAncestor) return;
    /* ...and it has to be the thing actually painted there, not a hidden node under an overlay. */
    const sx = Math.min(Math.max(Math.round(r.left + r.width/2), 2), window.innerWidth - 2);
    const sy = Math.min(Math.max(Math.round((r.top + r.bottom)/2), 2), window.innerHeight - 2);
    let hit = document.elementFromPoint(sx, sy);
    /* Look through an INVISIBLE overlay (transparent full-screen scrim / drag layer): it hit-tests
       first but paints nothing, so the flow content beneath it is still what the reader sees. */
    let guard = 0;
    while (hit && guard++ < 4 && hit !== el && !el.contains(hit)) {
      const hc = getComputedStyle(hit);
      const hr = hit.getBoundingClientRect();
      const invisible = (hc.backgroundColor === 'rgba(0, 0, 0, 0)' || hc.backgroundColor === 'transparent') && hc.backgroundImage === 'none';
      const covering = hr.height >= window.innerHeight * 0.8 && hr.width >= window.innerWidth * 0.8;
      if (!(invisible && covering)) break;
      const prev = hit.style.pointerEvents;
      hit.style.pointerEvents = 'none';
      const next = document.elementFromPoint(sx, sy);
      hit.style.pointerEvents = prev;
      if (!next || next === hit) break;
      hit = next;
    }
    if (!hit || !(hit === el || el.contains(hit))) return;
    const d = Math.abs((r.top + r.bottom) / 2 - mid);
    if (d < bestD) { bestD = d; best = el; }
  });
  if (!best) return null;
  window.__ref = best;
  return { y: Math.round(best.getBoundingClientRect().top), text: (best.textContent || '').trim().slice(0, 30) };
})()`;
const READ = `(() => {
  if (!window.__ref || !window.__ref.isConnected) return null;
  const r = window.__ref.getBoundingClientRect();
  return { y: Math.round(r.top), scrollY: Math.round(window.scrollY), anchor: getComputedStyle(document.body).overflowAnchor };
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
    await new Promise(r => setTimeout(r, 1500));

    // Scroll with real wheel events so scroll-driven UI settles the way it would for a user.
    await page.mouse.move(206, 500);
    await page.mouse.wheel({ deltaY: scrollY });
    await new Promise(r => setTimeout(r, 1200));

    // At scroll-top a +topPx shift IS the inset doing its job, not a jump — only a page that is
    // actually scrolled can exhibit the defect. (medium.com's landing page never scrolls here.)
    // Needs MORE than topPx of scroll above: with only a few pixels of scroll (imdb's bot-check wall
    // stops at scrollY=8) there is nothing for the browser — or for us — to compensate with, and the
    // content moving down by topPx is simply the inset doing its job.
    const shallow = await page.evaluate(`Math.round(window.scrollY) < ${topPx} + 20`);
    if (shallow) { console.log(`\n=== ${url} ===\nRESULT: N/A ⚠️  page barely scrolled (nothing to hold steady)`); return; }
    const mark = await page.evaluate(MARK);
    if (!mark) { console.log(`\n=== ${url} ===\nRESULT: N/A ⚠️  no stable reference element mid-viewport`); return; }
    const before = await page.evaluate(READ);

    await page.evaluate(buildViewportSpacerScript(topPx, false).replace(/^javascript:/, ''));
    await new Promise(r => setTimeout(r, 1500));
    const afterInject = await page.evaluate(READ);

    // Force several more passes the way a live page would (DOM churn), then re-read.
    for (let i = 0; i < 4; i++) {
      await page.evaluate(`document.body.appendChild(document.createElement('span'))`);
      await new Promise(r => setTimeout(r, 700));
    }
    const afterPasses = await page.evaluate(READ);

    console.log(`\n=== ${url}  topPx=${topPx}  scrollY=${scrollY} ===`);
    console.log(`reference: "${mark.text}"  overflow-anchor: ${before && before.anchor}`);
    const fmt = (n, s) => console.log(`${n.padEnd(13)} ref.y=${s ? s.y : 'gone'} scrollY=${s ? s.scrollY : '-'}`);
    fmt('before', before); fmt('after inject', afterInject); fmt('after 4 passes', afterPasses);
    if (!afterInject || !afterPasses) { console.log(`\nRESULT: N/A ⚠️  reference element was replaced (page re-rendered)`); return; }
    const dInject = afterInject.y - before.y;
    const dPasses = afterPasses.y - afterInject.y;
    console.log(`drift: inject ${dInject >= 0 ? '+' : ''}${dInject}px, later passes ${dPasses >= 0 ? '+' : ''}${dPasses}px`);
    const ok = Math.abs(dInject) <= TOLERANCE && Math.abs(dPasses) <= TOLERANCE;
    console.log(`\nRESULT: ${ok ? 'PASS ✅ content stayed put while scrolled'
      : `FAIL ❌ page jumped ${Math.abs(dInject) > TOLERANCE ? `${dInject}px on inject` : ''}${Math.abs(dInject) > TOLERANCE && Math.abs(dPasses) > TOLERANCE ? ' and ' : ''}${Math.abs(dPasses) > TOLERANCE ? `${dPasses}px on a later pass` : ''}`}`);
  } finally {
    await browser.close();
  }
})();
