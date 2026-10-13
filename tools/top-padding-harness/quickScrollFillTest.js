const assert = require('node:assert/strict');
const puppeteer = require('puppeteer-core');
const {buildViewportSpacerScript} = require('./extractScript');
const wait = ms => new Promise(resolve => setTimeout(resolve, ms));
(async () => {
  const browser = await puppeteer.launch({executablePath: process.env.CHROME_PATH, headless: true});
  try {
    const page = await browser.newPage();
    await page.setViewport({width: 400, height: 800});
    await page.setContent('<style>body{margin:0;background:rgb(110,110,110)}header{height:32px;background:rgb(20,80,140)}main{height:3000px}</style><header>Non-fixed navigation</header><main>Article</main>');
    await page.evaluate(source => {location.href = source}, 'javascript:' + encodeURIComponent(buildViewportSpacerScript(48, false).slice(11)));
    await wait(100);
    for (let trial = 0; trial < 8; trial++) {
      const color = trial % 2 ? 'rgb(20, 80, 140)' : 'rgb(30, 150, 70)';
      await page.evaluate(() => window.scrollTo(0, 500));
      await wait(30);
      await page.evaluate(color => {
        window.__tangentCooldownMs = 2000;
        window.__tangentLastHeaderInsetMs = Date.now();
        document.querySelector('header').style.backgroundColor = color;
        window.scrollTo(0, 0);
      }, color);
      await wait(60);
      const observed = await page.$eval('#tangent-top-scroll-spacer', e => getComputedStyle(e).backgroundColor);
      assert.equal(observed, color, `trial ${trial}: returning header keeps a stale top strip`);
      assert.equal(await page.$('#tangent-pinned-header-fill'), null);
    }
    await page.evaluate(() => scrollTo(0, 24));
    await wait(60);
    assert.equal(await page.$eval('#tangent-top-scroll-spacer', e => getComputedStyle(e).backgroundColor), 'rgb(20, 80, 140)', 'partially visible strip samples its actual lower edge');
    await page.evaluate(() => {
      window.__tangentCooldownMs = 2000;
      window.__tangentLastHeaderInsetMs = Date.now();
      document.querySelector('header').style.backgroundColor = 'rgb(200, 30, 40)';
    });
    await wait(60);
    assert.equal(await page.$eval('#tangent-top-scroll-spacer', e => getComputedStyle(e).backgroundColor), 'rgb(200, 30, 40)', 'color-only mutation repaints during layout cooldown');
    await page.evaluate(() => scrollTo(0, 0));
    await page.evaluate(source => {location.href = source}, 'javascript:' + encodeURIComponent(buildViewportSpacerScript(72, false).slice(11)));
    await wait(100);
    await page.evaluate(() => {
      document.querySelector('header').style.backgroundColor = 'rgb(10, 40, 90)';
      scrollTo(0, 40);
    });
    await wait(60);
    assert.equal(await page.$eval('#tangent-top-scroll-spacer', e => getComputedStyle(e).backgroundColor), 'rgb(10, 40, 90)', 're-injection refreshes the scroll paint closure');
    console.log('PASS: 8 fast-return cycles, partial strip, color-only mutation and changed inset repaint within 60ms');
  } finally {await browser.close()}
})().catch(error => {console.error(error); process.exitCode = 1});
