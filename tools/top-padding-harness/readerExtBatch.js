// Validates the rebuilt content-script extractor (Readability scorer + live-DOM walk) by injecting
// Readability.js + extractor.js into real pages and calling tangentExtractArticle(rules), then
// side-by-side against the shipping prompt-bridge extractor (buildReaderExtractorScript).
const puppeteer = require('puppeteer-core');
const fs = require('fs');
const path = require('path');
const { buildReaderExtractorScript, compactRules } = require('./readerExtract');

const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';
const EXTDIR = path.resolve(__dirname, '../../app/src/main/assets/extensions/reader');
const EXTRACTOR = fs.readFileSync(path.join(EXTDIR, 'extractor.js'), 'utf8');
const urls = process.argv.slice(2).filter(a => !a.startsWith('--'));
const dump = process.argv.includes('--dump');

function summarize(a) {
  if (!a) return 'null';
  const counts = {};
  for (const b of a.blocks) counts[b.type] = (counts[b.type] || 0) + 1;
  const prose = a.blocks.filter(b => b.type === 'p').reduce((n, b) => n + (b.text || '').length, 0);
  return `blocks=${a.blocks.length} ${JSON.stringify(counts)} prose=${prose} byline=${JSON.stringify(a.byline)}`;
}

(async () => {
  const rules = JSON.parse(compactRules());
  let promptScript = buildReaderExtractorScript(JSON.stringify(rules));
  if (promptScript.startsWith('javascript:')) promptScript = promptScript.slice(11);

  const b = await puppeteer.launch({ executablePath: CHROME, headless: true, args: ['--no-sandbox'] });
  for (const url of urls) {
    const page = await b.newPage();
    await page.setUserAgent(MOBILE_UA);
    await page.setViewport({ width: 412, height: 915, isMobile: true, deviceScaleFactor: 2.6 });
    // capture the prompt-bridge output
    let promptOut = null;
    page.on('dialog', async d => {
      if (d.type() === 'prompt' && d.message() === 'tangent-reader') { try { promptOut = JSON.parse(d.defaultValue()); } catch (e) {} }
      try { await d.accept('ok'); } catch (e) {}
    });
    console.log(`\n### ${url}`);
    try {
      await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 45000 });
      await new Promise(r => setTimeout(r, 2500));
      // new extractor
      await page.addScriptTag({ content: EXTRACTOR });
      const ext = await page.evaluate((r) => {
        try { return { ok: true, a: window.tangentExtractArticle(r) }; }
        catch (e) { return { ok: false, err: e.message + ' @ ' + (e.stack || '').split('\n')[1] }; }
      }, rules);
      // prompt bridge
      await page.evaluate((s) => { try { (0, eval)(s); } catch (e) {} }, promptScript);
      await new Promise(r => setTimeout(r, 800));

      if (!ext.ok) console.log(`  NEW: ERROR ${ext.err}`);
      else console.log(`  NEW:    ${summarize(ext.a)}${ext.a ? ' title="' + (ext.a.title || '').slice(0, 60) + '"' : ''}`);
      console.log(`  PROMPT: ${summarize(promptOut)}`);
      if (dump && ext.ok && ext.a) {
        for (const blk of ext.a.blocks.slice(0, 10)) console.log('     -', blk.type, JSON.stringify((blk.text || blk.src || '').slice(0, 80)));
      }
    } catch (e) {
      console.log(`  ERROR: ${e.message}`);
    }
    await page.close();
  }
  await b.close();
})();
