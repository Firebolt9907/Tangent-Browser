// Verifies the scroll-padding-top half of the injection: an in-page anchor jump (#hash link) must
// land its target BELOW the top inset, not under the status bar. This is the whole reason the
// injected <style> sets `html,body{scroll-padding-top:<topPx>}`. We inject, then trigger an anchor
// navigation (click a link or set location.hash) and assert the target's top >= topPx.
// Usage: node anchorTest.js <url> <targetSelector> [clickSelector] [topPx]
const puppeteer = require('puppeteer-core');
const { buildViewportSpacerScript } = require('./extractScript');
const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const url = process.argv[2];
const targetSel = process.argv[3];
const clickSel = process.argv[4] && !/^\d+$/.test(process.argv[4]) ? process.argv[4] : null;
const topPx = parseInt(process.argv[5] || (clickSel ? '48' : process.argv[4]) || '48', 10);
const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';
if (!url || !targetSel) { console.error('usage: node anchorTest.js <url> <targetSelector> [clickSelector] [topPx]'); process.exit(1); }

(async () => {
  const browser = await puppeteer.launch({ executablePath: CHROME, headless: 'new',
    args: ['--no-sandbox', '--hide-scrollbars'], defaultViewport: { width: 412, height: 915, isMobile: true, hasTouch: true, deviceScaleFactor: 2.625 } });
  try {
    const page = await browser.newPage();
    await page.setUserAgent(MOBILE_UA);
    await page.goto(url, { waitUntil: 'networkidle2', timeout: 45000 }).catch(() => {});
    await new Promise(r => setTimeout(r, 1200));
    await page.evaluate(buildViewportSpacerScript(topPx, false).replace(/^javascript:/, ''));
    await new Promise(r => setTimeout(r, 1000));
    const res = await page.evaluate((targetSel, clickSel, topPx) => {
      const spt = getComputedStyle(document.documentElement).scrollPaddingTop;
      if (clickSel) { const a = document.querySelector(clickSel); if (a) a.click(); }
      else { const t = document.querySelector(targetSel); if (t && t.id) location.hash = '#' + t.id; }
      return new Promise(r => setTimeout(() => {
        const t = document.querySelector(targetSel);
        if (!t) { r({ spt, top: null }); return; }
        const rect = t.getBoundingClientRect();
        // Occlusion check: the target's top edge must be VISIBLE, not hidden behind a pinned
        // (fixed/sticky) header that we inset below the status bar. Landing at top>=topPx is not
        // enough — if the site has its own e.g. 64px header now sitting at [topPx, topPx+64], a
        // target that lands at topPx is covered by it. Sample just inside the target's top edge;
        // if elementFromPoint returns a fixed/sticky element that is NOT the target/descendant,
        // the target is occluded.
        const cx = Math.min(window.innerWidth - 2, Math.max(2, rect.left + rect.width / 2));
        const cy = Math.max(1, rect.top + 3);
        let hit = document.elementFromPoint(cx, cy);
        let coveredBy = null;
        if (hit && hit !== t && !t.contains(hit) && !hit.contains(t)) {
          for (let n = hit; n; n = n.parentElement) {
            const p = getComputedStyle(n).position;
            if (p === 'fixed' || p === 'sticky') {
              coveredBy = (n.tagName.toLowerCase()) + (n.id ? '#' + n.id : '') + (n.className && typeof n.className === 'string' ? '.' + n.className.trim().split(/\s+/)[0] : '');
              break;
            }
          }
        }
        r({ spt, top: Math.round(rect.top), coveredBy });
      }, 700));
    }, targetSel, clickSel, topPx);
    console.log(`\n=== anchor jump: ${url}  topPx=${topPx} ===`);
    console.log(`scroll-padding-top: ${res.spt}`);
    console.log(`target ${targetSel} landed at top=${res.top}${res.coveredBy ? '  (COVERED by pinned ' + res.coveredBy + ')' : ''}`);
    const clearsBar = res.top != null && res.top >= topPx - 2;
    const ok = clearsBar && !res.coveredBy;
    console.log(`\nRESULT: ${res.top == null ? 'SKIP (target not found)' : !clearsBar ? 'FAIL ❌ target under the bar (top < topPx)' : res.coveredBy ? 'FAIL ❌ target hidden behind pinned ' + res.coveredBy : 'PASS ✅ target below the inset'}`);
  } finally { await browser.close(); }
})();
