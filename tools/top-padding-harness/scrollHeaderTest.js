// SCROLL-REACTIVE HEADER probe. scrollTest.js covers headers that become sticky on scroll. This
// one covers headers that CHANGE with scroll position: auto-hiding bars (slide out going down,
// back in going up), bars that shrink/grow, and bars that swap a tall marketing header for a
// compact one. Those rewrite their own geometry repeatedly at scroll positions no other probe
// visits, and they are where the inset is most likely to be lost or left stale.
//
// Checks BOTH things that make the strip look wrong, at every phase of a scroll cycle:
//   - ink:  is readable content sitting in the status-bar band
//   - fill: does the strip still match the colour of whatever is under it (a header that hides
//           on scroll leaves the strip painted its colour, floating over the content below)
//
// Usage: node scrollHeaderTest.js <url> [topPx]
const puppeteer = require('puppeteer-core');
const { PNG } = require('pngjs');
const { buildViewportSpacerScript } = require('./extractScript');

const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const url = process.argv[2];
const topPx = parseInt(process.argv[3] || '48', 10);
if (!url) { console.error('usage: node scrollHeaderTest.js <url> [topPx]'); process.exit(1); }

const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';
const TOL = 14;
const dist = (a, b) => Math.max(Math.abs(a[0] - b[0]), Math.abs(a[1] - b[1]), Math.abs(a[2] - b[2]));
const hex = (p) => '#' + p.slice(0, 3).map(v => v.toString(16).padStart(2, '0')).join('');

// Same ink metric as run.js: only readable text / media / interactive content counts, and only
// where it is the top PAINTED element (occlusion gate) with a real visible slice in the band.
const INK = (band) => `(() => {
  const BAND = ${band}, SPACER = 'tangent-top-scroll-spacer';
  const out = [];
  const all = [];
  (function walk(root){ (root.querySelectorAll ? root.querySelectorAll('*') : []).forEach(e => { all.push(e); if (e.shadowRoot) walk(e.shadowRoot); }); })(document);
  for (const el of all) {
    if (el.id === SPACER || el.closest('#'+SPACER)) continue;
    let r = el.getBoundingClientRect();
    if (el.tagName.toLowerCase() === 'svg') {
      const kids = el.querySelectorAll('path,rect,circle,ellipse,polygon,polyline,line,text,image');
      if (kids.length) { let t=1e9,b=-1e9,l=1e9,rr=-1e9;
        kids.forEach(k => { const q = k.getBoundingClientRect(); if (q.width||q.height) { t=Math.min(t,q.top); b=Math.max(b,q.bottom); l=Math.min(l,q.left); rr=Math.max(rr,q.right); } });
        if (b > -1e9) r = { top:t, bottom:b, left:l, right:rr, width:rr-l, height:b-t };
      }
    }
    if (!(r.top < BAND - 1 && r.bottom > 1)) continue;
    if (Math.min(BAND, r.bottom) - Math.max(0, r.top) < 6) continue;
    if (r.width < 6 || r.height < 6) continue;
    const cs = getComputedStyle(el);
    if (cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity) === 0) continue;
    const tag = el.tagName.toLowerCase();
    const isMedia = tag === 'img' || tag === 'svg';
    if (isMedia && r.height > 160 && r.width >= innerWidth * 0.6 && r.top <= 2) continue; // full-bleed hero
    const directText = Array.from(el.childNodes).some(k => k.nodeType === 3 && k.textContent.trim().length);
    if (!((directText && el.children.length === 0) || isMedia)) continue;
    const cx = Math.max(1, Math.min(innerWidth - 1, (r.left + r.right) / 2));
    const cy = Math.max(1, Math.min(BAND - 1, (Math.max(0, r.top) + Math.min(BAND, r.bottom)) / 2));
    const hit = document.elementFromPoint(cx, cy);
    if (!hit || !(hit === el || el.contains(hit) || hit.contains(el))) continue;
    out.push(tag + (el.id ? '#' + el.id : '') + ' "' + (el.textContent || '').trim().slice(0, 24) + '"');
  }
  return out;
})()`;

(async () => {
  const browser = await puppeteer.launch({
    executablePath: CHROME, headless: 'new', args: ['--no-sandbox', '--hide-scrollbars'],
    defaultViewport: { width: 412, height: 915, isMobile: true, hasTouch: true, deviceScaleFactor: 1 },
  });
  try {
    const page = await browser.newPage();
    await page.setUserAgent(MOBILE_UA);
    await page.goto(url, { waitUntil: 'networkidle2', timeout: 45000 }).catch(e => console.error('nav warn:', e.message));
    await new Promise(r => setTimeout(r, 1200));
    await page.evaluate(buildViewportSpacerScript(topPx, false).replace(/^javascript:/, ''));
    await new Promise(r => setTimeout(r, 1800));

    // Real wheel events: a site's scroll-direction logic never runs if you just set scrollTop.
    const wheel = async (dy, steps) => {
      for (let i = 0; i < steps; i++) { await page.mouse.wheel({ deltaY: dy }); await new Promise(r => setTimeout(r, 90)); }
      await new Promise(r => setTimeout(r, 900)); // let the site's own transition + our walk settle
    };

    const sample = async (phase) => {
      // Measure the DOM BEFORE capturing: a screenshot can perturb the page (see bandFill.js's
      // note on capture-beyond-viewport), and the ink question must be asked of the live state.
      const ink = await page.evaluate(INK(topPx));
      const y = await page.evaluate('window.scrollY');
      const png = PNG.sync.read(Buffer.from(await page.screenshot()));
      const at = (x, y) => { const i = (png.width * y + x) << 2; return [png.data[i], png.data[i + 1], png.data[i + 2]]; };
      const xs = [0.2, 0.5, 0.8].map(f => Math.floor(png.width * f));
      const band = xs.map(x => at(x, Math.max(1, Math.floor(topPx / 2))));
      const below = xs.map(x => at(x, topPx + 6));
      const worst = Math.max(...band.map((b, i) => dist(b, below[i])));
      const belowUniform = below.every(b => dist(b, below[0]) <= TOL);
      return { phase, y, ink, worst, belowUniform, band: hex(band[1]), below: hex(below[1]) };
    };

    const phases = [];
    phases.push(await sample('at top'));
    await wheel(400, 6); phases.push(await sample('scrolled down'));
    await wheel(400, 6); phases.push(await sample('further down'));
    await wheel(-400, 4); phases.push(await sample('scrolled back up'));
    await wheel(-400, 8); phases.push(await sample('back at top'));

    console.log(`\n=== ${url}  topPx=${topPx} ===`);
    for (const p of phases) {
      console.log(`  ${p.phase.padEnd(18)} y=${String(p.y).padStart(5)}  ink=${p.ink.length}  strip ${p.band} vs ${p.below} Δ=${p.worst}${p.belowUniform ? '' : ' (bar not flat)'}`);
      if (p.ink.length) p.ink.slice(0, 3).forEach(t => console.log(`      ink: ${t}`));
    }

    const inkBad = phases.filter(p => p.ink.length);
    // Only judge the fill where the thing under the strip is a flat colour; mid-article content,
    // photos and gradients legitimately differ from the strip above them.
    const fillBad = phases.filter(p => p.belowUniform && p.worst > TOL);
    let verdict;
    if (inkBad.length) verdict = `FAIL ❌ content in the band at: ${inkBad.map(p => p.phase).join(', ')}`;
    else if (fillBad.length) verdict = `FAIL ❌ strip colour stops matching the bar at: ${fillBad.map(p => `${p.phase} (${p.band} vs ${p.below})`).join(', ')}`;
    else if (phases.every(p => p.y === 0)) verdict = `N/A ⚠️  page never scrolled (bot wall / short page)`;
    else verdict = `PASS ✅ band stays clear and the strip keeps matching across the scroll cycle`;
    console.log(`\nRESULT: ${verdict}`);
  } finally {
    await browser.close();
  }
})();
