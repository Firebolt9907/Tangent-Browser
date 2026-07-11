// Verifies AGENTS.md invariant #11: after the spacer script is injected ONCE, a client-side
// (SPA / pushState) navigation — which fires no new page load, so ContentHost never re-injects —
// must self-heal: the top spacer div + scroll-padding-top stay, and any new header the route
// rendered gets inset. We inject once, then drive an in-page navigation and re-probe WITHOUT
// re-injecting. Usage: node spaTest.js <url> [topPx]
const puppeteer = require('puppeteer-core');
const { buildViewportSpacerScript } = require('./extractScript');
const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const url = process.argv[2];
const topPx = parseInt(process.argv[3] || '48', 10);
const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';
if (!url) { console.error('usage: node spaTest.js <url> [topPx]'); process.exit(1); }

// Reuse the same ink-in-band probe semantics as run.js.
function bandProbeSrc(topPx) {
  return `(() => {
    const band = ${topPx}, SPACER='lumina-top-scroll-spacer';
    function opaque(c){ return c && c!=='transparent' && !/rgba\\(0, 0, 0, 0\\)/.test(c) && !/^rgba\\(.*,\\s*0\\)$/.test(c); }
    const hits=[]; document.querySelectorAll('*').forEach(el=>{
      if(el.id===SPACER||el.closest('#'+SPACER))return;
      const tag=el.tagName.toLowerCase();
      if(['html','body','head','script','style'].includes(tag))return;
      const cs=getComputedStyle(el);
      if(cs.visibility==='hidden'||cs.display==='none'||parseFloat(cs.opacity)===0)return;
      const r=el.getBoundingClientRect();
      if(r.width<3||r.height<3)return;
      if(!(r.top<band-1&&r.bottom>1))return;
      if(Math.min(band,r.bottom)-Math.max(0,r.top)<6)return; // ignore slivers (skip-links poking a few px in)
      const childEls=el.children.length;
      const directText=Array.from(el.childNodes).some(n=>n.nodeType===3&&n.textContent.trim().length);
      const isMedia=['img','svg','canvas','video','picture','image','use','path'].includes(tag);
      const fullBleed=isMedia&&r.height>160&&r.width>=window.innerWidth*0.6&&r.top<=2;
      const inkLeaf=(((directText&&childEls===0)||isMedia)&&!fullBleed);
      if(!inkLeaf)return;
      hits.push({tag,id:el.id||'',top:Math.round(r.top),text:(el.textContent||'').trim().replace(/\\s+/g,' ').slice(0,30)});
    }); return hits;
  })()`;
}

(async () => {
  const browser = await puppeteer.launch({ executablePath: CHROME, headless: 'new',
    args: ['--no-sandbox','--hide-scrollbars'], defaultViewport: { width: 412, height: 915, isMobile: true, hasTouch: true, deviceScaleFactor: 2.625 } });
  try {
    const page = await browser.newPage();
    await page.setUserAgent(MOBILE_UA);
    await page.goto(url, { waitUntil: 'networkidle2', timeout: 45000 }).catch(()=>{});
    await new Promise(r => setTimeout(r, 1500));

    const body = buildViewportSpacerScript(topPx, false).replace(/^javascript:/, '');
    await page.evaluate(body);
    await new Promise(r => setTimeout(r, 1500));
    const startUrl = page.url();
    const bandAfterInject = await page.evaluate(bandProbeSrc(topPx));

    // Drive an in-page navigation: click the first same-origin, non-hash link that is likely to be
    // an SPA route. Fall back to history.pushState if no such link produces a client-side nav.
    const nav = await page.evaluate(async () => {
      const origin = location.origin, before = location.href;
      const links = Array.from(document.querySelectorAll('a[href]')).filter(a => {
        try { const u = new URL(a.href); return u.origin === origin && u.href !== before && !a.href.includes('#') && (a.target||'_self')==='_self'; } catch { return false; }
      });
      for (const a of links.slice(0, 8)) {
        a.scrollIntoView(); a.click();
        await new Promise(r => setTimeout(r, 900));
        if (location.href !== before) return { kind: 'link-click', to: location.href };
      }
      // fallback: synthetic SPA route change
      history.pushState({}, '', origin + '/lumina-spa-probe-' + Date.now());
      window.dispatchEvent(new PopStateEvent('popstate'));
      return { kind: 'pushState', to: location.href };
    }).catch(() => ({ kind: 'link-click', to: '(full navigation)' })); // context destroyed = full nav

    // Give the injected self-heal (history-wrap + MutationObserver + scheduled passes) time to run.
    await new Promise(r => setTimeout(r, 2500));

    // A full-page navigation destroys the execution context (and, on a real device, ContentHost
    // re-injects on the new load — that's the re-inject path, not the SPA self-heal invariant). If
    // the page did a full nav, report N/A instead of crashing.
    const isSpaNav = await page.evaluate(() => !!window.__luminaListenersBound).catch(() => false);
    if (!isSpaNav) {
      console.log(`\n=== SPA self-heal: ${url}  topPx=${topPx} ===`);
      console.log(`inject band clean: ${bandAfterInject.length === 0 ? 'yes' : 'NO ('+bandAfterInject.length+')'}`);
      console.log(`navigation: ${nav.kind} -> full page load (context replaced)`);
      console.log(`\nRESULT: N/A (full navigation → re-inject path, not the SPA self-heal invariant)`);
      return;
    }

    // Content scrolling under a translucent status bar is normal — the inset only reserves space at
    // the TOP of the scroll range. So before probing, return every scroll root to the top (the SPA
    // nav may have left an internal scroll container mid-scroll), then re-probe.
    await page.evaluate(() => {
      window.scrollTo(0, 0);
      document.querySelectorAll('*').forEach(el => { if (el.scrollTop > 0) { const cs = getComputedStyle(el); if (cs.overflowY === 'auto' || cs.overflowY === 'scroll') el.scrollTop = 0; } });
    }).catch(() => {});
    await new Promise(r => setTimeout(r, 700));

    const spacerAlive = await page.evaluate(() => {
      const sp = document.getElementById('lumina-top-scroll-spacer');
      const style = document.getElementById('lumina-viewport-scroll-style');
      const sc = style && /scroll-padding-top/.test(style.textContent);
      return { spacer: !!sp, spacerInBody: sp ? sp.parentElement === (document.body||document.documentElement) : false,
               spacerFirst: sp ? (sp.parentElement && sp.parentElement.firstElementChild === sp) : false, scrollStyle: !!sc };
    });
    const bandAfterNav = await page.evaluate(bandProbeSrc(topPx));

    console.log(`\n=== SPA self-heal: ${url}  topPx=${topPx} ===`);
    console.log(`inject band clean: ${bandAfterInject.length === 0 ? 'yes' : 'NO ('+bandAfterInject.length+')'}`);
    console.log(`navigation: ${nav.kind} -> ${nav.to.replace(startUrl,'(same)')}`);
    console.log(`spacer div present after nav: ${spacerAlive.spacer}  (in body: ${spacerAlive.spacerInBody}, first-child: ${spacerAlive.spacerFirst})`);
    console.log(`scroll-padding-top style present: ${spacerAlive.scrollStyle}`);
    console.log(`band after nav (${bandAfterNav.length}):`);
    bandAfterNav.forEach(e => console.log(`    ${e.tag}${e.id?'#'+e.id:''} top=${e.top} "${e.text}"`));
    const ok = spacerAlive.spacer && spacerAlive.scrollStyle && bandAfterNav.length === 0;
    console.log(`\nRESULT: ${ok ? 'PASS ✅ inset self-healed across SPA nav' : 'FAIL ❌ ' + (!spacerAlive.spacer ? 'spacer lost; ' : '') + (!spacerAlive.scrollStyle ? 'scroll-padding lost; ' : '') + (bandAfterNav.length ? bandAfterNav.length+' content in band' : '')}`);
  } finally { await browser.close(); }
})();
