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
        r({ spt, top: t ? Math.round(t.getBoundingClientRect().top) : null });
      }, 700));
    }, targetSel, clickSel, topPx);
    console.log(`\n=== anchor jump: ${url}  topPx=${topPx} ===`);
    console.log(`scroll-padding-top: ${res.spt}`);
    console.log(`target ${targetSel} landed at top=${res.top}`);
    const ok = res.top != null && res.top >= topPx - 2;
    console.log(`\nRESULT: ${res.top == null ? 'SKIP (target not found)' : ok ? 'PASS ✅ target below the inset' : 'FAIL ❌ target under the bar (top < topPx)'}`);
  } finally { await browser.close(); }
})();
