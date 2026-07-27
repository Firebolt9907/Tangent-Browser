// INSET-LATENCY probe. The other probes ask "is the band clear *eventually*". They snapshot after a
// generous settle, so they cannot see how LONG a newly mounted header sits under the status bar.
// That window is real on a phone: tap a link in an SPA, and the new route's header is unstyled-
// looking under the status bar until the next pass runs.
//
// The injected script's walk has an adaptive cooldown (500ms, relaxing to 2000ms once the chosen
// header set has been stable) — so a header mounted while the page is idle can wait up to a full
// cooldown. This probe idles the page until the cooldown has relaxed, mounts a header, and polls
// until it is inset, reporting the delay.
//
// Usage: node latencyTest.js [topPx] [idleMs] [--no-nav]
const puppeteer = require('puppeteer-core');
const { buildViewportSpacerScript } = require('./extractScript');

const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const topPx = parseInt(process.argv[2] || '48', 10);
const idleMs = parseInt(process.argv[3] || '8000', 10);
const noNav = process.argv.includes('--no-nav');
// ?case=shell puts the page on the app-shell inset path; --sticky mounts a sticky header instead of
// a fixed one. Both exercise the bypass through a different inset mechanism.
const shellCase = process.argv.includes('--shell');
const sticky = process.argv.includes('--sticky');
const url = `file://${__dirname}/fixtures/latehdr.html${shellCase ? '?case=shell' : ''}`;
const BUDGET_MS = 900;

(async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: 'new',
    args: ['--no-sandbox', '--hide-scrollbars'],
    defaultViewport: { width: 412, height: 915, isMobile: true, hasTouch: true, deviceScaleFactor: 2.625 },
  });
  try {
    const page = await browser.newPage();
    await page.goto(url, { waitUntil: 'load' });
    await page.evaluate(buildViewportSpacerScript(topPx, false).replace(/^javascript:/, ''));
    // Idle so the walk's cooldown relaxes to its steady-state value.
    await new Promise(r => setTimeout(r, idleMs));
    const cooldown = await page.evaluate('window.__tangentCooldownMs || 500');

    const fn = sticky ? '__mountSticky' : noNav ? '__mountRouteNoNav' : '__mountRoute';
    const mounted = await page.evaluate(`(() => { window.${fn}(); return performance.now(); })()`);
    let delay = -1;
    for (let i = 0; i < 120; i++) {
      const done = await page.evaluate(`(() => {
        const h = document.getElementById('lateHeader');
        if (!h) return null;
        const r = h.getBoundingClientRect();
        /* inset once the header's own content clears the band: either it was pushed down, or it was
           padded so its children sit below topPx */
        const kid = h.querySelector('span');
        const kr = kid ? kid.getBoundingClientRect() : r;
        return (r.top >= ${topPx} - 1 || kr.top >= ${topPx} - 1) ? performance.now() : null;
      })()`);
      if (done) { delay = Math.round(done - mounted); break; }
      await new Promise(r => setTimeout(r, 50));
    }

    console.log(`\n=== inset latency  topPx=${topPx}  idle=${idleMs}ms  nav=${!noNav}  ${shellCase ? 'app-shell' : 'normal'}${sticky ? ' sticky' : ''} ===`);
    console.log(`cooldown at mount time: ${cooldown}ms`);
    console.log(`header inset after: ${delay < 0 ? 'NEVER (>6s)' : delay + 'ms'}`);
    console.log(`\nRESULT: ${delay >= 0 && delay <= BUDGET_MS ? `PASS ✅ inset within ${BUDGET_MS}ms budget` : `FAIL ❌ ${delay < 0 ? 'never inset' : delay + 'ms > ' + BUDGET_MS + 'ms budget'}`}`);
  } finally {
    await browser.close();
  }
})();
