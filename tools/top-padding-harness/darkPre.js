const puppeteer = require('puppeteer-core');
const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';

async function run(url, dark) {
  const browser = await puppeteer.launch({ executablePath: CHROME, headless: 'new',
    args: ['--no-sandbox', '--hide-scrollbars'],
    defaultViewport: { width: 412, height: 915, isMobile: true, hasTouch: true, deviceScaleFactor: 2 } });
  try {
    const page = await browser.newPage();
    await page.setUserAgent(MOBILE_UA);
    if (dark) await page.emulateMediaFeatures([{ name: 'prefers-color-scheme', value: 'dark' }]);
    await page.goto(url, { waitUntil: 'networkidle2', timeout: 45000 }).catch(e => console.error('nav', e.message));
    await new Promise(r => setTimeout(r, 1800));
    const res = await page.evaluate(() => {
      function lum(c){var m=c&&c.match(/rgba?\(([^)]+)\)/);if(!m)return null;var p=m[1].split(',');var a=p.length>3?parseFloat(p[3]):1;return {a:a,L:0.2126*p[0]+0.7152*p[1]+0.0722*p[2]};}
      var bb=getComputedStyle(document.body).backgroundColor, hb=getComputedStyle(document.documentElement).backgroundColor;
      var bl=lum(bb), hl=lum(hb);
      // emulate pageIsDark(): body bg opaque? else html; L<128 dark
      var pick = (bl&&bl.a>=0.5)?bl:((hl&&hl.a>=0.5)?hl:null);
      return {bodyBg:bb, htmlBg:hb, pageIsDark: pick?pick.L<128:'NO-OPAQUE-BG(=light)', pickL: pick?Math.round(pick.L):null};
    });
    console.log(`  dark=${dark}: body=${res.bodyBg} html=${res.htmlBg} -> pageIsDark=${res.pageIsDark} (L=${res.pickL})`);
  } finally { await browser.close(); }
}
(async()=>{ const url=process.argv[2]||'https://www.apple.com';
  console.log('=== '+url+' (no script, just measuring the pre-inject page) ===');
  await run(url, false); await run(url, true);
})();
