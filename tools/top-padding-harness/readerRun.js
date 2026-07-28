// Drives system Chrome to article URLs, injects the SHIPPING reader-extraction script, and
// reports what the extractor hands back over the tangent-reader bridge (title + block summary).
// Usage: node readerRun.js <url> [url2 ...] [--show] [--dump]
const puppeteer = require('puppeteer-core');
const { buildReaderExtractorScript, compactRules } = require('./readerExtract');

const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const args = process.argv.slice(2);
const show = args.includes('--show');
const dump = args.includes('--dump');
const urls = args.filter(a => !a.startsWith('--'));
if (!urls.length) { console.error('usage: node readerRun.js <url> [...]'); process.exit(1); }

const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';

(async () => {
  const rules = compactRules();
  let script = buildReaderExtractorScript(rules);
  if (script.startsWith('javascript:')) script = script.slice('javascript:'.length);

  const browser = await puppeteer.launch({
    executablePath: CHROME,
    headless: !show,
    args: ['--no-sandbox', '--window-size=412,915'],
  });

  for (const url of urls) {
    const page = await browser.newPage();
    await page.setUserAgent(MOBILE_UA);
    await page.setViewport({ width: 412, height: 915, isMobile: true, deviceScaleFactor: 2.6 });

    // The bridge posts the article via an iframe's native prompt(). Capture it.
    let captured = null;
    page.on('dialog', async (d) => {
      if (d.type() === 'prompt' && d.message() === 'tangent-reader') {
        captured = d.defaultValue();
      }
      try { await d.accept('ok'); } catch (e) {}
    });

    let matchedRule = null;
    try {
      await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 45000 });
      // let SPA/lazy content settle a touch, like FCP+load re-injection would
      await new Promise(r => setTimeout(r, 2500));
      // Report which rule matched, for diagnosis.
      matchedRule = await page.evaluate((rulesJson) => {
        const R = JSON.parse(rulesJson);
        const host = location.hostname.replace(/^www\./, '').toLowerCase();
        for (const r of R) {
          let pats = r.match; if (!Array.isArray(pats)) pats = [pats];
          for (let p of pats) { p = String(p).toLowerCase().replace(/^\*\./, '').replace(/^www\./, '');
            if (host === p || host.endsWith('.' + p)) return r; }
        }
        return null;
      }, rules);

      await page.evaluate(script);
      await new Promise(r => setTimeout(r, 1500));
    } catch (e) {
      console.log(`\n### ${url}\n  ERROR: ${e.message}`);
      await page.close();
      continue;
    }

    console.log(`\n### ${url}`);
    console.log(`  matchedRule: ${matchedRule ? JSON.stringify(matchedRule) : 'NONE'}`);
    if (!captured) {
      console.log('  RESULT: reader NOT eligible (no tangent-reader payload)');
    } else {
      try {
        const a = JSON.parse(captured);
        const counts = {};
        for (const b of a.blocks) counts[b.type] = (counts[b.type] || 0) + 1;
        const textLen = a.blocks.filter(b => b.type === 'p').reduce((n, b) => n + (b.text || '').length, 0);
        console.log(`  RESULT: eligible | title="${(a.title || '').slice(0, 80)}"`);
        console.log(`  byline=${JSON.stringify(a.byline)}`);
        console.log(`  blocks=${a.blocks.length} ${JSON.stringify(counts)} proseChars=${textLen}`);
        if (dump) {
          for (const b of a.blocks.slice(0, 12)) {
            console.log('   -', b.type, JSON.stringify((b.text || b.src || '').slice(0, 90)));
          }
        }
      } catch (e) {
        console.log('  RESULT: payload parse failed:', e.message, captured.slice(0, 120));
      }
    }
    await page.close();
  }
  await browser.close();
})();
