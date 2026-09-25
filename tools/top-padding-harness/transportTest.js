// Exercise javascript: URL decoding, which page.evaluate(script) bypasses.
// The native helper's UTF-8 URL encoding is round-trip checked by JavascriptUrlEncodingTest.
const assert = require('node:assert/strict');
const puppeteer = require('puppeteer-core');
const { buildViewportSpacerScript, buildBottomSpacerScript } = require('./extractScript');

(async () => {
  const browser = await puppeteer.launch({
    executablePath: process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    headless: true,
  });
  try {
    const page = await browser.newPage();
    await page.setViewport({ width: 390, height: 844 });
    const errors = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.setContent('<style>body{margin:0}header{position:fixed;top:0;width:100%;height:60px;background:rgb(20,80,140);box-sizing:border-box}main{height:2000px}</style><header><span id="logo">Logo and controls</span></header><main>Article</main>');
    const top = buildViewportSpacerScript(50, false);
    // Prove the original unencoded navigation fails; direct eval previously hid this error.
    await page.evaluate(url => { location.href = url; }, top);
    await new Promise(resolve => setTimeout(resolve, 100));
    assert(errors.length > 0, 'unencoded modulo expression must reproduce the URL parse failure');
    assert.equal(await page.$('#tangent-top-scroll-spacer'), null);
    errors.length = 0;
    // Separate successive loads also check the earlier unproven cancellation theory.
    for (const source of [top, buildBottomSpacerScript(64), 'javascript:void(document.documentElement.style.zoom="")']) {
      await page.evaluate(url => { location.href = url; }, 'javascript:' + encodeURIComponent(source.slice('javascript:'.length)));
    }
    await page.waitForFunction(() => document.getElementById('logo').getBoundingClientRect().top >= 50);
    const result = await page.evaluate(() => ({
      logoTop: document.getElementById('logo').getBoundingClientRect().top,
      topHeight: document.getElementById('tangent-top-scroll-spacer').getBoundingClientRect().height,
      bottomHeight: document.getElementById('tangent-bottom-scroll-spacer').getBoundingClientRect().height,
      bandColor: getComputedStyle(document.elementFromPoint(195, 25)).backgroundColor,
    }));
    assert.deepEqual(errors, []);
    assert.equal(result.topHeight, 50);
    assert.equal(result.bottomHeight, 64);
    assert.equal(result.bandColor, 'rgb(20, 80, 140)');
    console.log('PASS: real javascript URLs preserve both spacers and keep header content below its extended background', result);
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
