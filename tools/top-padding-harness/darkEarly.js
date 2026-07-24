// Reproduces the DEVICE injection sequence: inject buildDynamicScript EARLY (FCP-ish, before all
// CSS settles) the way ContentHost does, then let the page finish, then measure body/section bg.
const fs = require('fs');
const path = require('path');
const puppeteer = require('puppeteer-core');
const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';
const KT = path.resolve(__dirname, '../../app/src/main/java/com/firebolt9907/tangentbrowser/ui/content/WebDarkMode.kt');

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
  tpl = tpl.replace("${DYN_STYLE_ID}", 'tangent-dyn-style').replace("${DYN_CLASS}", 'tangent-dyn')
           .replace("${DYN_BASE_BG}", '#1c1c1e').replace("${DYN_SCAN_CAP}", '8000');
  return tpl.replace(/\n/g, ' ').replace(/^javascript:/, '');
}

(async () => {
  const url = process.argv[2] || 'https://www.apple.com';
  const js = extractDynamic();
  const browser = await puppeteer.launch({ executablePath: CHROME, headless: 'new',
    args: ['--no-sandbox', '--hide-scrollbars'],
    defaultViewport: { width: 412, height: 915, isMobile: true, hasTouch: true, deviceScaleFactor: 2 } });
  try {
    const page = await browser.newPage();
    await page.setUserAgent(MOBILE_UA);
    await page.emulateMediaFeatures([{ name: 'prefers-color-scheme', value: 'dark' }]);
    // Inject as EARLY as possible on every document, mimicking the FCP-time injection.
    await page.evaluateOnNewDocument((code) => {
      function fire(){ try { (0,eval)(code); } catch(e){ console.error('inject err', e.message); } }
      document.addEventListener('DOMContentLoaded', fire, { once: true });
    }, js);
    await page.goto(url, { waitUntil: 'networkidle2', timeout: 45000 }).catch(e => console.error('nav', e.message));
    // Give the in-script setTimeout(300)/setTimeout(1200) re-passes time to run, like on device.
    await new Promise(r => setTimeout(r, 2500));
    const res = await page.evaluate(() => {
      function lum(c){var m=c&&c.match(/rgba?\(([^)]+)\)/);if(!m)return null;var p=m[1].split(',');return Math.round(0.2126*p[0]+0.7152*p[1]+0.0722*p[2]);}
      var out=[];var all=document.body.getElementsByTagName('*');
      for(var i=0;i<all.length;i++){var el=all[i];var r=el.getBoundingClientRect();
        if(r.width<200||r.height<120||r.top>2600)continue;
        var cs=getComputedStyle(el);
        out.push({tag:el.tagName,cls:(typeof el.className==='string'?el.className:'').slice(0,26),
          bg:cs.backgroundColor,bgL:lum(cs.backgroundColor),inline:el.style.backgroundColor||'',done:!!el.__lumDynDone});
        if(out.length>=14)break;}
      return {dynActive:!!window.__lumDynActive, bodyBg:getComputedStyle(document.body).backgroundColor,
        bodyDone:!!document.body.__lumDynDone, sample:out};
    });
    console.log(`\n=== ${url}  EARLY (device-like) injection ===`);
    console.log('dynActive=', res.dynActive, ' body bg=', res.bodyBg, ' body __lumDynDone=', res.bodyDone);
    for (const s of res.sample)
      console.log(`  ${s.tag.padEnd(8)} .${s.cls.padEnd(26)} bgL=${String(s.bgL).padEnd(4)} done=${s.done?'Y':'n'} inline="${s.inline}"  ${s.bgL>140?'<-- LIGHT':''}`);
  } finally { await browser.close(); }
})();
