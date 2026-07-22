// Feasibility gate for image cutout: can we getImageData() on the big homepage images,
// or are they cross-origin-tainted? Reports per-image origin + readability + rough bg luminance.
const puppeteer = require('puppeteer-core');
const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';
(async () => {
  const url = process.argv[2] || 'https://www.apple.com';
  const browser = await puppeteer.launch({ executablePath: CHROME, headless: 'new',
    args: ['--no-sandbox', '--hide-scrollbars'],
    defaultViewport: { width: 412, height: 915, isMobile: true, hasTouch: true, deviceScaleFactor: 2 } });
  try {
    const page = await browser.newPage();
    await page.setUserAgent(MOBILE_UA);
    await page.emulateMediaFeatures([{ name: 'prefers-color-scheme', value: 'dark' }]);
    await page.goto(url, { waitUntil: 'networkidle2', timeout: 45000 }).catch(e => console.error('nav', e.message));
    await new Promise(r => setTimeout(r, 2000));
    const res = await page.evaluate(() => {
      var imgs = document.getElementsByTagName('img'); var out = [];
      for (var i = 0; i < imgs.length && out.length < 24; i++) {
        var im = imgs[i]; var r = im.getBoundingClientRect();
        if (r.width < 120 || r.height < 90) continue;
        var rec = { w: Math.round(r.width), h: Math.round(r.height),
          src: (im.currentSrc || im.src || '').slice(0, 70),
          origin: 'same', readable: false, cornerL: null, err: '' };
        try { rec.origin = new URL(im.currentSrc || im.src, location.href).origin === location.origin ? 'same' : 'cross'; } catch (e) {}
        try {
          var S = 24, cv = document.createElement('canvas'); cv.width = S; cv.height = S;
          var ctx = cv.getContext('2d'); ctx.drawImage(im, 0, 0, S, S);
          var d = ctx.getImageData(0, 0, S, S).data; rec.readable = true;
          // corner (0,0) luminance as a bg proxy
          rec.cornerL = Math.round(0.2126 * d[0] + 0.7152 * d[1] + 0.0722 * d[2]);
        } catch (e) { rec.err = e.name; }
        out.push(rec);
      }
      return out;
    });
    console.log(`\n=== ${url} — image CORS/readability ===`);
    for (const r of res)
      console.log(`  ${(''+r.w+'x'+r.h).padEnd(9)} ${r.origin.padEnd(5)} readable=${r.readable?'YES':'NO '} cornerL=${String(r.cornerL).padEnd(4)} ${r.err} ${r.src}`);
    const cross = res.filter(r => r.origin === 'cross'), tainted = res.filter(r => !r.readable);
    console.log(`\n${res.length} imgs | ${cross.length} cross-origin | ${tainted.length} unreadable(tainted)`);
  } finally { await browser.close(); }
})();
