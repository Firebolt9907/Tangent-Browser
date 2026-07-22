const fs = require('fs');
const path = require('path');
const puppeteer = require('puppeteer-core');
const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';
const KT = path.resolve(__dirname, '../../app/src/main/java/com/firebolt9907/luminabrowser/ui/content/WebDarkMode.kt');

function extractDynamic() {
  const src = fs.readFileSync(KT, 'utf8');
  const start = src.indexOf('internal fun buildDynamicScript');
  const q1 = src.indexOf('"""', start), q2 = src.indexOf('"""', q1 + 3);
  let tpl = src.slice(q1 + 3, q2);
  const lines = tpl.split('\n');
  while (lines.length && lines[0].trim() === '') lines.shift();
  while (lines.length && lines[lines.length - 1].trim() === '') lines.pop();
  const indent = Math.min(...lines.filter(l => l.trim()).map(l => l.match(/^\s*/)[0].length));
  tpl = lines.map(l => l.slice(indent)).join('\n');
  tpl = tpl.replace("${DYN_STYLE_ID}", 'lumina-dyn-style').replace("${DYN_CLASS}", 'lumina-dyn')
           .replace("${DYN_BASE_BG}", '#1c1c1e').replace("${DYN_SCAN_CAP}", '8000');
  return tpl.replace(/\n/g, ' ').replace(/^javascript:/, '');
}
(async () => {
  const url = process.argv[2] || 'https://www.apple.com';
  const out = process.argv[3] || '/private/tmp/claude-501/-Users-rishu-Github-LuminaBrowser/7daa8f13-4f45-4e94-b395-240384cfd81e/scratchpad/apple-dark.png';
  const browser = await puppeteer.launch({ executablePath: CHROME, headless: 'new',
    args: ['--no-sandbox', '--hide-scrollbars'],
    defaultViewport: { width: 412, height: 915, isMobile: true, hasTouch: true, deviceScaleFactor: 2 } });
  try {
    const page = await browser.newPage();
    await page.setUserAgent(MOBILE_UA);
    await page.emulateMediaFeatures([{ name: 'prefers-color-scheme', value: 'dark' }]);
    await page.goto(url, { waitUntil: 'networkidle2', timeout: 45000 }).catch(e => console.error('nav', e.message));
    await new Promise(r => setTimeout(r, 1500));
    await page.evaluate(extractDynamic());
    await new Promise(r => setTimeout(r, 2000));
    await page.screenshot({ path: out, fullPage: false });
    console.log('shot ->', out);
  } finally { await browser.close(); }
})();
