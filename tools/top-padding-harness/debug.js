// Dumps positioned elements intersecting the top band + how the shipping script would classify
// them, so we can see WHY an element is missed. Usage: node debug.js <url> [topPx]
const puppeteer = require('puppeteer-core');
const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const url = process.argv[2];
const topPx = parseInt(process.argv[3] || '48', 10);
const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';

(async () => {
  const browser = await puppeteer.launch({ executablePath: CHROME, headless: 'new',
    args: ['--no-sandbox'], defaultViewport: { width: 412, height: 915, isMobile: true, hasTouch: true, deviceScaleFactor: 2.625 } });
  try {
    const page = await browser.newPage();
    await page.setUserAgent(MOBILE_UA);
    await page.goto(url, { waitUntil: 'networkidle2', timeout: 45000 }).catch(()=>{});
    await new Promise(r => setTimeout(r, 1800));
    const dump = await page.evaluate((topPx) => {
      const out = [];
      const all = document.querySelectorAll('*');
      all.forEach(el => {
        const cs = getComputedStyle(el);
        const pos = cs.position;
        if (pos !== 'fixed' && pos !== 'sticky' && pos !== 'absolute') return;
        const r = el.getBoundingClientRect();
        if (r.bottom <= 0 || r.top > topPx + 90) return;
        if (r.width < 20 || r.height < 8) return;
        out.push({
          tag: el.tagName.toLowerCase(), id: el.id||'', cls: (el.className&&el.className.toString?el.className.toString():'').slice(0,50),
          pos, top: Math.round(r.top), bottom: Math.round(r.bottom), left: Math.round(r.left),
          w: Math.round(r.width), h: Math.round(r.height), cssTop: cs.top, bg: cs.backgroundColor,
          pe: cs.pointerEvents, vw: window.innerWidth, vh: window.innerHeight,
        });
      });
      return out;
    }, topPx);
    console.log(`\n=== ${url} positioned els near top (topPx=${topPx}, vw=${dump[0]?.vw}) ===`);
    dump.sort((a,b)=>a.top-b.top).forEach(e => console.log(
      `${e.pos.padEnd(8)} ${e.tag}${e.id?'#'+e.id:''} .${e.cls}\n   top=${e.top} bottom=${e.bottom} left=${e.left} w=${e.w} h=${e.h} cssTop=${e.cssTop} bg=${e.bg} pe=${e.pe}`));
  } finally { await browser.close(); }
})();
