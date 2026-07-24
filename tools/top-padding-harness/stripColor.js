// Loads a live site, injects the shipping spacer script, and reports:
//  - the top spacer's filled background color (should match the header/content below it)
//  - the color painted at the very top-center of the viewport
//  - spacer height after 1 inject vs after 3 re-injects + a synthetic SPA nav (creep check)
const puppeteer = require('puppeteer-core');
const { buildViewportSpacerScript } = require('./extractScript');
const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const url = process.argv[2];
const topPx = parseInt(process.argv[3] || '48', 10);
const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';
const script = buildViewportSpacerScript(topPx, false).replace(/^javascript:/, '');
(async () => {
  const browser = await puppeteer.launch({ executablePath: CHROME, args: ['--no-sandbox'] });
  const page = await browser.newPage();
  await page.setUserAgent(MOBILE_UA);
  await page.setViewport({ width: 412, height: 915, isMobile: true, hasTouch: true });
  try {
    await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 30000 });
    await new Promise(r => setTimeout(r, 1500));
    await page.evaluate(script);
    await new Promise(r => setTimeout(r, 500));
    const read = () => page.evaluate((topPx) => {
      const sp = document.getElementById('tangent-top-scroll-spacer');
      const cs = sp ? getComputedStyle(sp) : null;
      const host = document.body || document.documentElement;
      const hostPad = host ? getComputedStyle(host).paddingTop : '';
      return {
        h: cs ? cs.height : (sp===null ? 'none(host-pad='+hostPad+')' : ''),
        vis: cs ? cs.visibility : '',
        bg: cs ? cs.backgroundColor : '',
        hostPad,
      };
    }, topPx);
    const after1 = await read();
    // creep probe: re-inject 3x + synthetic SPA nav
    for (let i=0;i<3;i++){ await page.evaluate(script); await new Promise(r=>setTimeout(r,120)); }
    await page.evaluate(() => { history.pushState({}, '', location.pathname + '?x=' + Date.now()); window.dispatchEvent(new PopStateEvent('popstate')); });
    await new Promise(r => setTimeout(r, 500));
    const after4 = await read();
    const creep = after1.h !== after4.h ? 'CREEP!' : 'stable';
    console.log(url.replace(/^https?:\/\//,'').padEnd(28), 'bg='+String(after1.bg).padEnd(20), 'vis='+String(after1.vis).padEnd(8), 'h1='+after1.h, 'h4='+after4.h, creep, after1.hostPad!==''&&after1.h.startsWith('none')?'':'');
  } catch (e) {
    console.log(url.replace(/^https?:\/\//,'').padEnd(28), 'ERROR', e.message.slice(0,60));
  }
  await browser.close();
})();
