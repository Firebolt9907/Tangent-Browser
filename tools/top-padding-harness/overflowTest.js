// BOTTOM-CLIPPING probe. A full-height app shell (`height:100vh` / `100%` with its own internal
// scroll) does not shrink when we add a top inset — pushing it down by topPx shoves its bottom
// `topPx` pixels (bottom nav bar, player, chat composer) off the screen, where the user can never
// reach them. run.js/gapTest.js cannot see this: the band is clean and the shift is exactly topPx.
//
// Metric: find viewport-height-locked elements (h ~= innerHeight before injection) and check
// whether, after injection, they still fit — and whether the bottom-most fixed/sticky UI inside
// them is still on screen.
//
// Usage: node overflowTest.js <url> [topPx]
const puppeteer = require('puppeteer-core');
const { buildViewportSpacerScript } = require('./extractScript');

const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const url = process.argv[2];
const topPx = parseInt(process.argv[3] || '48', 10);
if (!url) { console.error('usage: node overflowTest.js <url> [topPx]'); process.exit(1); }

const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';

function probeSrc(topPx) {
  return `(() => {
    const vh = window.innerHeight;
    const SPACER = 'tangent-top-scroll-spacer';
    const shells = [];
    const bottomUi = [];
    document.querySelectorAll('*').forEach(el => {
      if (el.id === SPACER || el.closest('#'+SPACER)) return;
      const cs = getComputedStyle(el);
      if (cs.display === 'none' || cs.visibility === 'hidden') return;
      const r = el.getBoundingClientRect();
      if (r.width < window.innerWidth * 0.6) return;
      /* viewport-locked shell: height pinned to ~100vh (a scrolling document body is not one —
         it is taller than the viewport and grows, so require a tight match). */
      /* top<=topPx+16 (not <=4): after injection the shell may have been pushed down by topPx, and
         we still need to see it to know it now overflows the bottom. */
      if (Math.abs(r.height - vh) <= 4 && r.top <= ${topPx} + 16) {
        shells.push({ tag: el.tagName.toLowerCase(), id: el.id||'', cls: (el.className&&el.className.toString?el.className.toString():'').slice(0,30),
                      top: Math.round(r.top), bottom: Math.round(r.bottom), h: Math.round(r.height) });
      }
      /* Bottom-pinned UI (nav/player/composer) that must stay reachable. It has to be ON SCREEN
         and anchored near the bottom right now — a sticky section heading sitting 2000px down the
         document (spiegel.de has 29 of them) is ordinary flow content, not bottom UI. */
      if ((cs.position === 'fixed' || cs.position === 'sticky') && r.height > 24 && r.height < vh * 0.4 && r.top < vh && r.bottom > vh * 0.5) {
        bottomUi.push({ tag: el.tagName.toLowerCase(), id: el.id||'', cls: (el.className&&el.className.toString?el.className.toString():'').slice(0,30),
                        top: Math.round(r.top), bottom: Math.round(r.bottom), text: (el.textContent||'').trim().replace(/\\s+/g,' ').slice(0,24) });
      }
    });
    /* Is the overflowing strip reachable at all? In a scroll-locked document (the app-shell norm)
       it is gone forever; in a scrollable one the user can at least scroll to it. */
    const de = document.documentElement;
    const hs = getComputedStyle(de), bs = getComputedStyle(document.body);
    const locked = hs.overflowY === 'hidden' || bs.overflowY === 'hidden';
    const scrollable = Math.max(de.scrollHeight - de.clientHeight, document.body.scrollHeight - document.body.clientHeight);
    return { vh, docH: Math.round(de.scrollHeight), locked, scrollable, shells, bottomUi };
  })()`;
}

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

    const before = await page.evaluate(probeSrc(topPx));
    const body = buildViewportSpacerScript(topPx, false).replace(/^javascript:/, '');
    await page.evaluate(body);
    await new Promise(r => setTimeout(r, 1800));
    const after = await page.evaluate(probeSrc(topPx));

    console.log(`\n=== ${url}  topPx=${topPx} ===`);
    console.log(`BEFORE: vh=${before.vh} locked=${before.locked} shells=${before.shells.length} bottomUi=${before.bottomUi.length}`);
    before.shells.forEach(s => console.log(`    shell ${s.tag}${s.id?'#'+s.id:''}.${s.cls} top=${s.top} h=${s.h}`));
    before.bottomUi.forEach(b => console.log(`    bottomUi ${b.tag}${b.id?'#'+b.id:''}.${b.cls} top=${b.top} bottom=${b.bottom} "${b.text}"`));

    // Only count what WE broke: an element already hanging off the bottom before injection (airbnb's
    // bottom nav sits at 850-975 on its own in headless Chrome) is the site's own doing.
    const wasOff = new Set(before.bottomUi.filter(b => b.bottom > before.vh + 4).map(b => b.tag + '|' + b.cls + '|' + b.text));
    // A shell that kept its full-viewport height but now starts at topPx overflows the bottom.
    const pushed = after.shells.filter(s => s.top >= topPx - 2 && s.bottom > after.vh + 4);
    // Bottom UI whose bottom edge left the viewport entirely because of the injection.
    const lost = after.bottomUi.filter(b => b.bottom > after.vh + 4 && !wasOff.has(b.tag + '|' + b.cls + '|' + b.text));
    console.log(`AFTER : vh=${after.vh} locked=${after.locked} scrollable=${after.scrollable} shells=${after.shells.length} bottomUi=${after.bottomUi.length}`);
    after.shells.forEach(s => console.log(`    shell ${s.tag}${s.id?'#'+s.id:''}.${s.cls} top=${s.top} bottom=${s.bottom} h=${s.h}${s.bottom > after.vh + 4 ? '  ⟵ OVERFLOWS' : ''}`));
    after.bottomUi.forEach(b => console.log(`    bottomUi ${b.tag}${b.id?'#'+b.id:''}.${b.cls} top=${b.top} bottom=${b.bottom} "${b.text}"${b.bottom > after.vh + 4 ? '  ⟵ OFF-SCREEN' : ''}`));

    // Overflow in a SCROLL-LOCKED document is unrecoverable (the strip can never be scrolled to) —
    // that is the defect. In a scrollable document the same overflow is merely a longer page.
    // `locked` alone is not enough: a page can set html{overflow:hidden} and still scroll a child
    // scroller (airbnb reports locked=true with 2354px of scroll). Require the strip to be truly
    // unreachable — nothing left to scroll.
    const severe = (pushed.length || lost.length) && after.scrollable < topPx - 4;
    console.log(`\nRESULT: ${!(pushed.length || lost.length)
      ? 'PASS ✅ nothing pushed off the bottom'
      : severe
        ? `FAIL ❌ ${pushed.length} viewport-locked shell(s) overflow / ${lost.length} bottom UI off-screen in a SCROLL-LOCKED document (unreachable)`
        : `PASS ⚠️  ${pushed.length} shell(s) overflow but the document scrolls (${after.scrollable}px) — reachable`}`);
  } finally {
    await browser.close();
  }
})();
