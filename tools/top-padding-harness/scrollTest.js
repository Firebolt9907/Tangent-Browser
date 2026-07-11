// Tests scroll-activated sticky/fixed headers. Many sites keep the masthead in normal flow at the
// very top (the spacer pushes it down fine) but swap it to position:fixed/sticky once you scroll
// down, re-pinning it to top:0 — where its content can land under the status bar unless the
// injected script's IntersectionObserver/MutationObserver re-insets it. Normal scrolled flow
// content in the band is EXPECTED; the defect is a PINNED header (fixed/sticky, stays at the top
// while scrolled) whose readable content paints in [0, topPx). Usage: node scrollTest.js <url> [topPx]
const puppeteer = require('puppeteer-core');
const { buildViewportSpacerScript } = require('./extractScript');
const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const url = process.argv[2];
const topPx = parseInt(process.argv[3] || '48', 10);
const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';
if (!url) { console.error('usage: node scrollTest.js <url> [topPx]'); process.exit(1); }

// Ink in band that belongs to a PINNED (fixed/sticky) header, i.e. an element whose nearest
// positioned ancestor is fixed or sticky and currently rendered at the top. Excludes normal
// flow content that merely scrolled under the bar.
function pinnedBandProbe(topPx) {
  return `(() => {
    const band=${topPx}, SPACER='lumina-top-scroll-spacer';
    /* A genuinely pinned header is a BAR sitting at the top: stuck near top 0 and short enough to
       fit on screen. Exclude full-page sticky top:auto containers (e.g. WaPo main#main-content is a
       25000px sticky element scrolled to top=-972) whose normal flow content near the top is just
       scrolled content, not a pinned header. */
    function pinnedAncestor(el){for(let n=el;n&&n!==document.documentElement;n=n.parentElement){const cs=getComputedStyle(n);if(cs.position==='fixed'||cs.position==='sticky'){const r=n.getBoundingClientRect();if(r.top<=2&&r.bottom>1&&r.bottom<=window.innerHeight)return n;}}return null;}
    const hits=[];
    document.querySelectorAll('*').forEach(el=>{try{
      if(el.id===SPACER||(el.closest&&el.closest('#'+SPACER)))return;
      const tag=(el.tagName||'').toLowerCase();
      if(!tag||['html','body','head','script','style'].includes(tag))return;
      const cs=getComputedStyle(el);
      if(cs.visibility==='hidden'||cs.display==='none'||parseFloat(cs.opacity)===0)return;
      const r=el.getBoundingClientRect();
      if(r.width<3||r.height<3)return;
      if(!(r.top<band-1&&r.bottom>1))return;
      const childEls=el.children.length;
      const directText=Array.from(el.childNodes).some(n=>n.nodeType===3&&n.textContent.trim().length);
      const isMedia=['img','svg','canvas','video','picture','image','use','path'].includes(tag);
      const fullBleed=isMedia&&r.height>160&&r.width>=window.innerWidth*0.6&&r.top<=2;
      const inkLeaf=(((directText&&childEls===0)||isMedia)&&!fullBleed);
      if(!inkLeaf)return;
      const pin=pinnedAncestor(el);
      if(!pin)return; // not inside a pinned header → normal scrolled content, ignore
      // occlusion gate
      const sx=Math.min(Math.max(Math.round(r.left+r.width/2),2),window.innerWidth-2);
      const sy=Math.min(Math.max(Math.round((Math.max(0,r.top)+Math.min(band,r.bottom))/2),2),band-1);
      const hit=document.elementFromPoint(sx,sy);
      if(!hit||!(hit===el||el.contains(hit)))return;
      hits.push({tag,id:el.id||'',top:Math.round(r.top),text:(el.textContent||'').trim().replace(/\\s+/g,' ').slice(0,28),
                 pinTag:(pin.tagName||'').toLowerCase()+(pin.id?'#'+pin.id:''),pinPos:getComputedStyle(pin).position});
    }catch(e){}});
    return hits;
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
    await page.evaluate(buildViewportSpacerScript(topPx, false).replace(/^javascript:/, ''));
    await new Promise(r => setTimeout(r, 1200));

    // Scroll with REAL wheel events (not scrollTop=), so scroll listeners / direction detection
    // fire and any scroll-activated sticky header actually reveals. Down a lot, then back up a bit
    // (up-scroll is the common trigger for a re-pinned condensed header). Mouse mid-viewport so the
    // wheel targets the main (or internal) scroll container.
    await page.mouse.move(206, 460);
    for (let i = 0; i < 14; i++) { await page.mouse.wheel({ deltaY: 120 }); await new Promise(r => setTimeout(r, 40)); }
    await new Promise(r => setTimeout(r, 500));
    for (let i = 0; i < 5; i++) { await page.mouse.wheel({ deltaY: -120 }); await new Promise(r => setTimeout(r, 60)); }
    // Fallback for any internal container the wheel didn't reach.
    await page.evaluate(() => { document.querySelectorAll('*').forEach(el => { const cs = getComputedStyle(el); if ((cs.overflowY==='auto'||cs.overflowY==='scroll') && el.scrollHeight > el.clientHeight + 200 && el.scrollTop === 0) el.scrollTop = 900; }); });
    await new Promise(r => setTimeout(r, 1600)); // let observer/IO re-inset any revealed sticky header

    const hits = await page.evaluate(pinnedBandProbe(topPx));
    console.log(`\n=== scroll sticky-header: ${url}  topPx=${topPx} ===`);
    console.log(`pinned-header ink in band after scroll (${hits.length}):`);
    hits.forEach(h => console.log(`    ${h.tag}${h.id?'#'+h.id:''} top=${h.top} "${h.text}"  [pinned by ${h.pinPos} ${h.pinTag}]`));
    console.log(`\nRESULT: ${hits.length === 0 ? 'PASS ✅ no pinned-header content under the bar after scroll' : 'FAIL ❌ '+hits.length+' pinned-header element(s) in band'}`);
  } finally { await browser.close(); }
})();
