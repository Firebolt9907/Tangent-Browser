// All URLs are intercepted locally; no credentials or network services are used.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const puppeteer = require('../top-padding-harness/node_modules/puppeteer-core');
const { fillScript, submitScript } = require('./extractProbeScript');
const origin = 'https://idp.example.test';
const sleep = ms => new Promise(resolve => setTimeout(resolve, ms));

(async () => {
  const browser = await puppeteer.launch({
    executablePath: process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
    headless: 'new',
  });
  try {
    const page = await browser.newPage();
    await page.setRequestInterception(true);
    const body = fs.readFileSync(path.join(__dirname, 'fixtures/classic.html'), 'utf8');
    page.on('request', request => request.respond({ status: 200, contentType: 'text/html', body }));
    // A script dispatched before navigation must not fill the new origin, including its retries.
    const fill = fillScript('synthetic-user', 'synthetic-secret', 'pw', origin);
    const submit = submitScript('pw', origin);
    for (const destination of ['https://other.example.test', 'http://idp.example.test', 'https://idp.example.test:8443']) {
      await page.goto(destination);
      await page.evaluate(fill);
      await sleep(1050);
      assert.equal(await page.$eval('[name=password]', el => el.value), '', destination);
      assert.equal(await page.$eval('[name=username]', el => el.value), '', destination);
      // Even a new page with filled fields must not receive the delayed submit from the old page.
      await page.$eval('[name=password]', el => { el.value = 'new-page-value'; });
      await page.evaluate(submit);
      await sleep(2400);
      assert.equal(await page.evaluate(() => window.__submitted || 0), 0, destination);
    }
    await page.goto(origin);
    await page.evaluate(fill);
    assert.equal(await page.$eval('[name=password]', el => el.value), 'synthetic-secret');
    await page.evaluate(submit);
    await sleep(500);
    assert.equal(await page.evaluate(() => window.__submitted), 1);
    console.log('PASS: origin-bound fill/retries and submit; same-origin login preserved');
  } finally {
    await browser.close();
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
