// LIVE inset-latency probe: latencyTest.js measures the fix on a synthetic fixture; this one
// measures the same thing on a real site's own client-side route change. Loads, injects, idles until
// the walk's adaptive cooldown has relaxed, clicks a same-origin link, then polls the band until it
// is clear and reports how long the new route's header sat under the status bar.
//
// Reports N/A for a full page navigation (that is ContentHost's re-inject path, not the self-heal
// one) and for routes that never put anything in the band.
//
// Usage: node liveLatency.js <url> [topPx] [idleMs]
const puppeteer = require('puppeteer-core');
const { buildViewportSpacerScript } = require('./extractScript');

const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const url = process.argv[2];
const topPx = parseInt(process.argv[3] || '48', 10);
const idleMs = parseInt(process.argv[4] || '8000', 10);
if (!url) { console.error('usage: node liveLatency.js <url> [topPx] [idleMs]'); process.exit(1); }
const BUDGET_MS = 900;

const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';

// Minimal band-ink test: readable text / media leaves painting inside [0, topPx). Deliberately
// simpler than run.js's — we only need a stable yes/no to time against, sampled many times.
const INK = `(() => {
  const band = ${topPx}, SPACER = 'tangent-top-scroll-spacer';
  let n = 0;
  document.querySelectorAll('*').forEach(el => {
    if (el.id === SPACER || el.closest('#'+SPACER)) return;
    const tag = el.tagName.toLowerCase();
    if (['html','body','head','script','style','link','meta','noscript'].includes(tag)) return;
    const cs = getComputedStyle(el);
    if (cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity) === 0) return;
    const r = el.getBoundingClientRect();
    if (r.width < 6 || r.height < 6) return;
    if (!(r.top < band - 1 && r.bottom > 1)) return;
    if (Math.min(band, r.bottom) - Math.max(0, r.top) < 6) return;
    const directText = Array.from(el.childNodes).some(k => k.nodeType === 3 && k.textContent.trim().length);
    const isMedia = ['img','svg','canvas','video','picture'].includes(tag);
    if (!((directText && el.children.length === 0) || isMedia)) return;
    const sx = Math.min(Math.max(Math.round(r.left + r.width/2), 2), window.innerWidth - 2);
    const sy = Math.min(Math.max(Math.round((Math.max(0, r.top) + Math.min(band, r.bottom))/2), 2), band - 1);
    let hit = document.elementFromPoint(sx, sy);
    while (hit && hit.shadowRoot) { const i = hit.shadowRoot.elementFromPoint(sx, sy); if (!i || i === hit) break; hit = i; }
    if (!hit || !(hit === el || el.contains(hit))) return;
    n++;
  });
  return n;
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
    await page.evaluate(buildViewportSpacerScript(topPx, false).replace(/^javascript:/, ''));
    await new Promise(r => setTimeout(r, idleMs));

    const cooldown = await page.evaluate('window.__tangentCooldownMs || 500').catch(() => -1);
    const before = await page.evaluate(INK);

    const navigated = await page.evaluate(`(() => {
      const here = location.href;
      const a = [...document.querySelectorAll('a[href]')].find(x => {
        const h = x.getAttribute('href') || '';
        if (!h || h.startsWith('#') || h.startsWith('javascript:') || h.startsWith('mailto:')) return false;
        try { const u = new URL(x.href, here); return u.origin === location.origin && u.href !== here; } catch (e) { return false; }
      });
      if (!a) return null;
      window.__navStart = performance.now();
      a.click();
      return a.href;
    })()`);
    if (!navigated) { console.log(`\n=== ${url} ===\nRESULT: N/A ⚠️  no same-origin link to click`); return; }

    // Poll: first observe the route putting something in the band, then time until it is clear.
    let dirtyAt = -1, cleanAt = -1, maxInk = 0, contextLost = false;
    for (let i = 0; i < 140; i++) {
      let ink;
      try { ink = await page.evaluate(INK); } catch (e) { contextLost = true; break; }
      const t = await page.evaluate('performance.now() - (window.__navStart || 0)').catch(() => -1);
      if (ink > 0) { if (dirtyAt < 0) dirtyAt = t; maxInk = Math.max(maxInk, ink); cleanAt = -1; }
      else if (dirtyAt >= 0 && cleanAt < 0) { cleanAt = t; break; }
      await new Promise(r => setTimeout(r, 50));
    }

    console.log(`\n=== ${url}  topPx=${topPx} ===`);
    console.log(`cooldown at nav: ${cooldown}ms  band before nav: ${before}  peak band ink after nav: ${maxInk}`);
    if (contextLost) { console.log(`RESULT: N/A ⚠️  full navigation (re-inject path, not the self-heal one)`); return; }
    if (dirtyAt < 0) { console.log(`RESULT: N/A ⚠️  route never put content in the band`); return; }
    const held = cleanAt < 0 ? -1 : Math.round(cleanAt - dirtyAt);
    console.log(`band went dirty at ${Math.round(dirtyAt)}ms after nav, clear ${cleanAt < 0 ? 'NEVER (>7s)' : Math.round(cleanAt) + 'ms'} — held ${held < 0 ? 'forever' : held + 'ms'}`);
    console.log(`\nRESULT: ${held >= 0 && held <= BUDGET_MS ? `PASS ✅ inset within ${BUDGET_MS}ms of the route rendering` : `FAIL ❌ ${held < 0 ? 'never inset' : held + 'ms > ' + BUDGET_MS + 'ms budget'}`}`);
  } finally {
    await browser.close();
  }
})();
