// BOTH-SPACERS probe. Everything else in this harness injects the top script alone, but that is
// not what ships: injectViewportSpacers() injects the top spacer AND the bottom spacer, so two
// independent MutationObservers watch the same document and each one's writes are the other's
// input. Nothing has ever tested them as a pair.
//
// Asks two questions the single-script probes cannot:
//   1. correctness — does the top inset still land with the bottom spacer also mutating <body>
//   2. churn — do the two observers drive each other in a loop. Each script re-applies on DOM
//      mutations; the bottom spacer appends/re-floats a div on <body>, which is exactly the kind of
//      childList mutation the top walk reacts to, and the top walk restyles nodes on every pass.
//      A feedback loop here costs battery on every page and would never show up as a wrong pixel.
//
// Usage: node pairTest.js <url> [topPx] [bottomPx]
const puppeteer = require('puppeteer-core');
const { buildViewportSpacerScript, buildBottomSpacerScript } = require('./extractScript');

const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const url = process.argv[2] || `file://${__dirname}/fixtures/headers.html?case=stacked`;
const topPx = parseInt(process.argv[3] || '48', 10);
const bottomPx = parseInt(process.argv[4] || '64', 10);
const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';

// A settled page should need only the occasional pass. More than this over the sample window means
// the two scripts are feeding each other rather than reacting to the page.
const WALK_BUDGET = 12;
const SAMPLE_MS = 6000;

(async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME, headless: 'new', args: ['--no-sandbox', '--hide-scrollbars'],
    defaultViewport: { width: 412, height: 915, isMobile: true, hasTouch: true, deviceScaleFactor: 1 },
  });
  try {
    const page = await browser.newPage();
    await page.setUserAgent(MOBILE_UA);
    await page.goto(url, { waitUntil: 'networkidle2', timeout: 45000 }).catch(e => console.error('nav warn:', e.message));
    await new Promise(r => setTimeout(r, 1000));

    // Inject in the shipping order.
    await page.evaluate(buildViewportSpacerScript(topPx, false).replace(/^javascript:/, ''));
    await page.evaluate(buildBottomSpacerScript(bottomPx).replace(/^javascript:/, ''));
    await new Promise(r => setTimeout(r, 2500)); // let both settle past their cooldowns

    // Count passes of each script over a quiet window, by watching the globals each one bumps.
    await page.evaluate(`(() => {
      window.__pair = { top: 0, bottom: 0 };
      let lastTop = window.__tangentLastHeaderInsetMs || 0;
      const bs = document.getElementById('tangent-bottom-scroll-spacer');
      // The bottom script has no timestamp global, so observe its own writes instead.
      if (bs) new MutationObserver(() => { window.__pair.bottom++; }).observe(bs, { attributes: true });
      window.__pairTick = setInterval(() => {
        const t = window.__tangentLastHeaderInsetMs || 0;
        if (t !== lastTop) { lastTop = t; window.__pair.top++; }
      }, 25);
    })()`);
    await new Promise(r => setTimeout(r, SAMPLE_MS));
    const counts = await page.evaluate('(() => { clearInterval(window.__pairTick); return window.__pair; })()');

    const state = await page.evaluate(`(() => {
      const sp = document.getElementById('tangent-top-scroll-spacer');
      const bs = document.getElementById('tangent-bottom-scroll-spacer');
      const host = document.body || document.documentElement;
      const de = document.documentElement;
      return {
        topH: sp && getComputedStyle(sp).display !== 'none' ? Math.round(sp.getBoundingClientRect().height) : 0,
        hostPad: Math.round(parseFloat(getComputedStyle(host).paddingTop) || 0),
        bottomH: bs ? Math.round(bs.getBoundingClientRect().height) : null,
        bottomIsLast: bs ? (host.lastElementChild === bs) : null,
        bottomIgnored: bs ? bs.dataset.tangentIgnoreSafeTop : null,
        spt: Math.round(parseFloat(getComputedStyle(de).scrollPaddingTop) || 0),
        spb: Math.round(parseFloat(getComputedStyle(de).scrollPaddingBottom) || 0),
      };
    })()`);

    console.log(`\n=== ${url}  topPx=${topPx} bottomPx=${bottomPx} ===`);
    console.log(`  top inset: spacer=${state.topH} hostPad=${state.hostPad}  scroll-padding-top=${state.spt}`);
    console.log(`  bottom spacer: h=${state.bottomH} last-child=${state.bottomIsLast} ignored-by-top-walk=${state.bottomIgnored}  scroll-padding-bottom=${state.spb}`);
    console.log(`  passes over ${SAMPLE_MS}ms — top walk: ${counts.top}, bottom re-apply: ${counts.bottom}`);

    const bad = [];
    const applied = Math.max(state.topH, state.hostPad);
    if (Math.abs(applied - topPx) > 2) bad.push(`top inset is ${applied}, expected ${topPx}`);
    if (state.bottomH === null) bad.push('bottom spacer missing');
    else if (Math.abs(state.bottomH - bottomPx) > 2) bad.push(`bottom spacer is ${state.bottomH}, expected ${bottomPx}`);
    if (state.bottomIsLast === false) bad.push('bottom spacer is no longer the last child');
    // AGENTS.md invariant #11: the top script must never set scroll-padding-bottom; that belongs to
    // the bottom script alone. If both are writing it they are fighting over one property.
    if (state.bottomIgnored !== 'true') bad.push('bottom spacer is not marked tangentIgnoreSafeTop, so the top walk will fight it');
    if (counts.top > WALK_BUDGET) bad.push(`top walk ran ${counts.top}x on a settled page (budget ${WALK_BUDGET}) — observer feedback`);
    if (counts.bottom > WALK_BUDGET) bad.push(`bottom re-applied ${counts.bottom}x on a settled page (budget ${WALK_BUDGET}) — observer feedback`);

    console.log(`\nRESULT: ${bad.length ? `FAIL ❌ ${bad.join('; ')}` : 'PASS ✅ both insets land and neither observer drives the other'}`);
  } finally {
    await browser.close();
  }
})();
