// Drives system Chrome to a URL, injects the SHIPPING viewport-spacer script, and reports
// whether real page content still paints in the [0, topPx) status-bar band after injection.
// Usage: node run.js <url> [topPx] [--show]
const puppeteer = require('puppeteer-core');
const { buildViewportSpacerScript } = require('./extractScript');

const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const url = process.argv[2];
const topPx = parseInt(process.argv[3] || '48', 10);
const show = process.argv.includes('--show');
if (!url) { console.error('usage: node run.js <url> [topPx] [--show]'); process.exit(1); }

// Pixel-3-ish mobile viewport (matches a phone GeckoView surface).
const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';

// Probe: which INK-BEARING leaves paint inside the band. We ignore transparent hit-test
// containers (e.g. a padded fixed toolbar whose real buttons were pushed below the band) and
// only flag things a user actually SEES painted in [0, topPx): text-bearing leaves, images,
// svg/canvas/video, or elements with a non-transparent background/border painting in the band.
function bandProbeSrc(topPx) {
  return `(() => {
    const band = ${topPx};
    const SPACER = 'lumina-top-scroll-spacer';
    function opaque(c){ return c && c !== 'transparent' && !/rgba\\(0, 0, 0, 0\\)/.test(c) && !/^rgba\\(.*,\\s*0\\)$/.test(c); }
    const hits = [];
    const all = document.querySelectorAll('*');
    all.forEach(el => {
      if (el.id === SPACER) return;
      const tag = el.tagName.toLowerCase();
      if (tag === 'html' || tag === 'body' || tag === 'head' || tag === 'script' || tag === 'style') return;
      if (el.closest('#'+SPACER)) return;
      const cs = getComputedStyle(el);
      if (cs.visibility === 'hidden' || cs.display === 'none' || parseFloat(cs.opacity) === 0) return;
      let r = el.getBoundingClientRect();
      // An <svg> root hit-tests its WHOLE box, but its box often has empty canvas padding around the
      // artwork (e.g. Nike's swoosh: 78px-tall svg, actual path ink at y=68-88, box top=39). Using
      // the box would flag empty canvas poking into the band as "visible ink". Tighten an svg to the
      // union of its painted child geometry so we test the artwork's real bounds.
      if (tag === 'svg') {
        let it = Infinity, ib = -Infinity, il = Infinity, ir = -Infinity;
        el.querySelectorAll('path,rect,circle,ellipse,polygon,polyline,line,image,text').forEach(g => {
          const gr = g.getBoundingClientRect();
          if (gr.width > 0 && gr.height > 0) { it = Math.min(it, gr.top); ib = Math.max(ib, gr.bottom); il = Math.min(il, gr.left); ir = Math.max(ir, gr.right); }
        });
        if (Number.isFinite(it)) r = { top: it, bottom: ib, left: il, right: ir, width: ir - il, height: ib - it };
      }
      if (r.width < 3 || r.height < 3) return;
      // must actually intersect the band with a MEANINGFUL visible slice. An element whose text is
      // centred above the viewport but whose box pokes a few px into the top (e.g. Dropbox's
      // "Skip to main content" skip-link at top=-44,bottom=4 — glyphs are at y~-20, only 4px of
      // empty bottom padding shows) is not readable content. Require >=6px of in-band visible height.
      if (!(r.top < band - 1 && r.bottom > 1)) return;
      if (Math.min(band, r.bottom) - Math.max(0, r.top) < 6) return;
      // classify ink
      const childEls = el.children.length;
      const directText = Array.from(el.childNodes).some(n => n.nodeType === 3 && n.textContent.trim().length);
      const isMedia = ['img','svg','canvas','video','picture','image','use','path'].includes(tag);
      const hasBg = opaque(cs.backgroundColor);
      const hasBorderTop = parseFloat(cs.borderTopWidth) > 0 && opaque(cs.borderTopColor);
      const bgImg = cs.backgroundImage && cs.backgroundImage !== 'none';
      // A large full-bleed top media surface (hero image, map/video canvas) is meant to run
      // edge-to-edge under a translucent status bar — the script deliberately leaves it (bandShift
      // is bounded to height<=160). Only small media (logos/icons) count as band content.
      const fullBleedMedia = isMedia && r.height > 160 && r.width >= window.innerWidth * 0.6 && r.top <= 2;
      const inkLeaf = ((directText && childEls === 0) || isMedia) && !fullBleedMedia;
      // A background/border only counts as band content if it's a top-localized bar/strip — not a
      // full-page canvas or a giant decorative gradient that legitimately sits behind everything
      // (including the translucent status-bar area). Require it to START at/near the top and not
      // span most of the viewport height.
      const bandLocalBg = (hasBg || bgImg || hasBorderTop) && r.top >= -8 && r.height <= window.innerHeight * 0.9;
      const paints = inkLeaf || bandLocalBg;
      if (!paints) return;
      // Occlusion / real-visibility gate: an element whose bounding box overlaps the band is only a
      // real defect if it is actually the TOP painted thing at its in-band location. Sample the
      // centre of the element's band-intersection; if elementFromPoint returns an ancestor (e.g.
      // <body>) or an unrelated element, this element isn't visible there — it's occluded, clipped,
      // or an off-screen a11y skip-link (Etsy's "Skip to Content"). Keep only self-or-descendant.
      const sx = Math.min(Math.max(Math.round(r.left + r.width / 2), 2), window.innerWidth - 2);
      const sy = Math.min(Math.max(Math.round((Math.max(0, r.top) + Math.min(band, r.bottom)) / 2), 2), band - 1);
      const hit = document.elementFromPoint(sx, sy);
      if (!hit || !(hit === el || el.contains(hit))) return;
      // a painting element whose TOP edge is >= band is fine (it just extends up? no—top<band means it's in band)
      const interactive = !!el.closest('a,button,input,select,textarea,[role="button"],[role="link"],[tabindex],[onclick]');
      hits.push({ tag, id: el.id||'', cls: (el.className&&el.className.toString?el.className.toString():'').slice(0,40),
                  interactive, kind: inkLeaf ? (isMedia?'media':'text') : 'bg',
                  top: Math.round(r.top), bottom: Math.round(r.bottom), left: Math.round(r.left), w: Math.round(r.width),
                  text: (el.textContent||'').trim().replace(/\\s+/g,' ').slice(0,30) });
    });
    return hits;
  })()`;
}

(async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: show ? false : 'new',
    args: ['--no-sandbox', '--hide-scrollbars'],
    defaultViewport: { width: 412, height: 915, isMobile: true, hasTouch: true, deviceScaleFactor: 2.625 },
  });
  try {
    const page = await browser.newPage();
    await page.setUserAgent(MOBILE_UA);
    await page.goto(url, { waitUntil: 'networkidle2', timeout: 45000 }).catch(e => console.error('nav warn:', e.message));
    await new Promise(r => setTimeout(r, 1500));

    const before = await page.evaluate(bandProbeSrc(topPx));

    const full = buildViewportSpacerScript(topPx, false);
    const body = full.replace(/^javascript:/, '');
    await page.evaluate(body);
    // let RAF + timeouts + observer settle
    await new Promise(r => setTimeout(r, 1800));
    const after = await page.evaluate(bandProbeSrc(topPx));

    const fmt = h => h.map(e => `    ${e.kind.padEnd(5)} ${e.tag}${e.id?'#'+e.id:''}${e.cls?'.'+e.cls:''} ${e.interactive?'INTERACTIVE ':''}top=${e.top} bottom=${e.bottom} left=${e.left} "${e.text}"`).join('\n');
    console.log(`\n=== ${url}  topPx=${topPx} ===`);
    console.log(`BEFORE injection — ink in band (${before.length}):`);
    console.log(before.length ? fmt(before) : '    (none)');
    console.log(`AFTER injection — ink in band (${after.length}):`);
    console.log(after.length ? fmt(after) : '    (none — CLEAN)');
    // A header/bar's own opaque background filling the status-bar strip is DESIRED (the status bar
    // sits over it); the defect is readable text / icons / images / controls painting in the band.
    const real = after.filter(e => e.kind === 'text' || e.kind === 'media');
    console.log(`\nRESULT: ${real.length === 0 ? 'PASS ✅ no readable content/icons in band'+(after.length?' (only bg fill remains)':'') : 'FAIL ❌ '+real.length+' text/icon element(s) still in band'}`);
    if (show) await new Promise(r => setTimeout(r, 8000));
  } finally {
    await browser.close();
  }
})();
