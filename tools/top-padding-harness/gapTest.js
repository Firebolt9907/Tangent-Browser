// OVER-PADDING probe. run.js only asks "is the band clear?" — it can never see the opposite
// defect: content pushed down MUCH further than the status-bar inset, leaving an ugly empty gap
// (double-padding). That happens when two inset mechanisms stack on the same subtree — e.g. an
// in-flow spacer div AND host padding-top, or a fixed header that gets padding-top while its
// absolutely-positioned children are ALSO band-shifted.
//
// Metric: identify the same real content elements before and after injection (stable DOM path key)
// and compare their viewport `top`. The MEDIAN shift of top-region content should be ~topPx.
// A median well above topPx means we over-pad; individual outliers are reported too.
//
// Usage: node gapTest.js <url> [topPx]
const puppeteer = require('puppeteer-core');
const { buildViewportSpacerScript } = require('./extractScript');

const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const url = process.argv[2];
const topPx = parseInt(process.argv[3] || '48', 10);
if (!url) { console.error('usage: node gapTest.js <url> [topPx]'); process.exit(1); }

const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';

// Snapshot the geometry of visible, ink-bearing leaves near the top of the page, keyed by a
// structural path so the same node can be matched across the before/after pass.
function snapSrc(limitY) {
  return `(() => {
    const SPACER = 'tangent-top-scroll-spacer';
    /* Identity key. A pure index path is brittle on SPAs that re-render between the two passes
       (React swaps node order → almost nothing matches → N/A). Prefer intrinsic identity: the
       element's own text / src / id, falling back to a short index path for anonymous nodes. */
    function key(el){
      const tag = el.tagName.toLowerCase();
      const txt = (el.textContent||'').trim().replace(/\\s+/g,' ').slice(0,40);
      if (txt) return tag + '|t:' + txt;
      const src = el.getAttribute && (el.getAttribute('src') || el.getAttribute('href') || el.getAttribute('aria-label') || el.getAttribute('alt'));
      if (src) return tag + '|s:' + String(src).slice(-60);
      if (el.id) return tag + '|#' + el.id;
      const parts = [];
      let n = el;
      while (n && n.nodeType === 1 && parts.length < 6) {
        const p = n.parentElement;
        const i = p ? Array.prototype.indexOf.call(p.children, n) : 0;
        parts.unshift(n.tagName.toLowerCase() + ':' + i);
        n = p;
      }
      return parts.join('>');
    }
    function collectDeep(root, out){ (root.querySelectorAll ? root.querySelectorAll('*') : []).forEach(el => { out.push(el); if (el.shadowRoot) collectDeep(el.shadowRoot, out); }); return out; }
    const out = [];
    collectDeep(document, []).forEach(el => {
      if (el.id === SPACER || el.closest('#'+SPACER)) return;
      const tag = el.tagName.toLowerCase();
      if (['html','body','head','script','style','link','meta','noscript'].includes(tag)) return;
      const cs = getComputedStyle(el);
      if (cs.visibility === 'hidden' || cs.display === 'none' || parseFloat(cs.opacity) === 0) return;
      const r = el.getBoundingClientRect();
      if (r.width < 8 || r.height < 8) return;
      if (r.top < -50 || r.top > ${limitY}) return;
      const childEls = el.children.length;
      const directText = Array.from(el.childNodes).some(n => n.nodeType === 3 && n.textContent.trim().length);
      const isMedia = ['img','svg','canvas','video','picture'].includes(tag);
      if (!((directText && childEls === 0) || isMedia)) return;
      /* only nodes that are actually the top painted thing at their own centre — avoids matching
         hidden/occluded duplicates whose geometry is meaningless. */
      const sx = Math.min(Math.max(Math.round(r.left + r.width/2), 2), window.innerWidth - 2);
      const sy = Math.min(Math.max(Math.round(r.top + Math.min(r.height, 20)/2), 2), window.innerHeight - 2);
      let hit = document.elementFromPoint(sx, sy);
      while (hit && hit.shadowRoot) { const inner = hit.shadowRoot.elementFromPoint(sx, sy); if (!inner || inner === hit) break; hit = inner; }
      if (!hit || !(hit === el || el.contains(hit))) return;
      const pos = (function(){ let n = el; while (n && n !== document.documentElement) { const p = getComputedStyle(n).position; if (p === 'fixed' || p === 'sticky') return p; n = n.parentElement || (n.getRootNode() && n.getRootNode().host); } return 'flow'; })();
      out.push({ k: key(el), tag, pos, top: Math.round(r.top), left: Math.round(r.left),
                 t: (el.textContent||'').trim().replace(/\\s+/g,' ').slice(0,24) });
    });
    /* keep one entry per key (first wins) so the join is unambiguous */
    const seen = new Set();
    return out.filter(e => { if (seen.has(e.k)) return false; seen.add(e.k); return true; });
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

    const before = await page.evaluate(snapSrc(700));
    const body = buildViewportSpacerScript(topPx, false).replace(/^javascript:/, '');
    await page.evaluate(body);
    await new Promise(r => setTimeout(r, 1800));
    const after = await page.evaluate(snapSrc(700 + topPx * 3));
    const spacerH = await page.evaluate(`(() => { const s = document.getElementById('tangent-top-scroll-spacer'); return s ? Math.round(s.getBoundingClientRect().height) : -1; })()`);
    const hostPad = await page.evaluate(`(() => parseFloat(getComputedStyle(document.body).paddingTop) || 0)()`);

    const aMap = new Map(after.map(e => [e.k, e]));
    const pairs = [];
    before.forEach(b => { const a = aMap.get(b.k); if (a && a.tag === b.tag) pairs.push({ b, a, d: a.top - b.top }); });

    console.log(`\n=== ${url}  topPx=${topPx} ===`);
    console.log(`spacerDiv=${spacerH}px  body.paddingTop=${hostPad}px  matched=${pairs.length}/${before.length}`);
    if (pairs.length < 4) {
      console.log('RESULT: N/A ⚠️  too few stable elements matched across injection (page re-rendered)');
      return;
    }
    const ds = pairs.map(p => p.d).sort((x, y) => x - y);
    const median = ds[Math.floor(ds.length / 2)];
    const p90 = ds[Math.floor(ds.length * 0.9)];
    const over = pairs.filter(p => p.d > topPx * 1.5 + 8).sort((x, y) => y.d - x.d);
    console.log(`shift: median=${median}px  min=${ds[0]}  p90=${p90}  max=${ds[ds.length-1]}  (expected ~${topPx})`);
    if (over.length) {
      console.log(`over-shifted elements (${over.length}):`);
      over.slice(0, 10).forEach(p => console.log(`    +${p.d}px ${p.b.tag}[${p.b.pos}] ${p.b.top}->${p.a.top} "${p.b.t}"`));
    }
    const tol = Math.max(12, Math.round(topPx * 0.5));
    const bad = median > topPx + tol;
    // A large minority over-shifted (>25% of matched nodes) also signals a stacked-inset defect
    // even when the median looks fine (e.g. only the header subtree double-pads).
    const cluster = over.length >= Math.max(3, pairs.length * 0.25);
    console.log(`\nRESULT: ${bad || cluster ? 'FAIL ❌ over-padding' + (bad ? ` (median shift ${median} > ${topPx}+${tol})` : ` (${over.length}/${pairs.length} elements shifted >1.5x topPx)`) : 'PASS ✅ shift ~= topPx, no double-gap'}`);
  } finally {
    await browser.close();
  }
})();
