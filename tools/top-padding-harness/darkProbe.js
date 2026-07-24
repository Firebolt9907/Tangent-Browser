// Diagnoses why buildDynamicScript leaves section backgrounds light on apple.com.
// Extracts the SHIPPING buildDynamicScript from WebDarkMode.kt and injects the same bytes.
const fs = require('fs');
const path = require('path');
const puppeteer = require('puppeteer-core');

const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';
const KT = path.resolve(__dirname, '../../app/src/main/java/com/firebolt9907/tangentbrowser/ui/content/WebDarkMode.kt');

function extractDynamic() {
  const src = fs.readFileSync(KT, 'utf8');
  const marker = 'internal fun buildDynamicScript';
  const start = src.indexOf(marker);
  const q1 = src.indexOf('"""', start);
  const q2 = src.indexOf('"""', q1 + 3);
  let tpl = src.slice(q1 + 3, q2);
  const lines = tpl.split('\n');
  while (lines.length && lines[0].trim() === '') lines.shift();
  while (lines.length && lines[lines.length - 1].trim() === '') lines.pop();
  const indent = Math.min(...lines.filter(l => l.trim()).map(l => l.match(/^\s*/)[0].length));
  tpl = lines.map(l => l.slice(indent)).join('\n');
  // Kotlin interpolations: constants
  tpl = tpl.replace("${DYN_STYLE_ID}", 'tangent-dyn-style')
           .replace("${DYN_CLASS}", 'tangent-dyn')
           .replace("${DYN_BASE_BG}", '#1c1c1e')
           .replace("${DYN_SCAN_CAP}", '8000');
  return tpl.replace(/\n/g, ' ');
}

// Sample the biggest visible section backgrounds top-to-bottom.
function probeSrc() {
  return `(function(){
    function lum(c){var m=c&&c.match(/rgba?\\(([^)]+)\\)/);if(!m)return null;var p=m[1].split(',');var a=p.length>3?parseFloat(p[3]):1;return {r:+p[0],g:+p[1],b:+p[2],a:a,L:0.2126*p[0]+0.7152*p[1]+0.0722*p[2]};}
    var out=[];var all=document.body.getElementsByTagName('*');
    for(var i=0;i<all.length;i++){var el=all[i];var r=el.getBoundingClientRect();
      if(r.width<200||r.height<120)continue; if(r.top>3000)continue;
      var cs=getComputedStyle(el);var bg=lum(cs.backgroundColor);
      out.push({tag:el.tagName,cls:(typeof el.className==='string'?el.className:'').slice(0,30),
        w:Math.round(r.width),h:Math.round(r.height),top:Math.round(r.top),
        bg:cs.backgroundColor, bgImg:cs.backgroundImage.slice(0,40), bgL:bg?Math.round(bg.L):null, bgA:bg?bg.a:null,
        inlineBg:el.style.backgroundColor||'', done:!!el.__lumDynDone});
      if(out.length>=25)break;}
    return {rootBg:getComputedStyle(document.documentElement).backgroundColor,
      bodyBg:getComputedStyle(document.body).backgroundColor,
      bodyInline:document.body.style.backgroundColor,
      htmlHasClass:document.documentElement.classList.contains('tangent-dyn'),
      dynActive:!!window.__lumDynActive, sample:out};
  })()`;
}

(async () => {
  const url = process.argv[2] || 'https://www.apple.com';
  const browser = await puppeteer.launch({
    executablePath: CHROME, headless: 'new',
    args: ['--no-sandbox', '--hide-scrollbars'],
    defaultViewport: { width: 412, height: 915, isMobile: true, hasTouch: true, deviceScaleFactor: 2 },
  });
  try {
    const page = await browser.newPage();
    await page.setUserAgent(MOBILE_UA);
    // Emulate dark preference (Tier 1) exactly like the app does.
    await page.emulateMediaFeatures([{ name: 'prefers-color-scheme', value: 'dark' }]);
    await page.goto(url, { waitUntil: 'networkidle2', timeout: 45000 }).catch(e => console.error('nav warn:', e.message));
    await new Promise(r => setTimeout(r, 1500));

    const body = extractDynamic().replace(/^javascript:/, '').replace(/^\(function/, '(function');
    // it's wrapped as javascript:(function(){...})(); strip the javascript: prefix
    const js = extractDynamic().replace(/^javascript:/, '');
    await page.evaluate(js);
    await new Promise(r => setTimeout(r, 2000));

    const res = await page.evaluate(probeSrc());
    console.log(`\n=== ${url} AFTER dynamic dark inject ===`);
    console.log('dynActive=', res.dynActive, ' html.tangent-dyn=', res.htmlHasClass);
    console.log('html bg=', res.rootBg, ' body bg=', res.bodyBg, ' body inline=', JSON.stringify(res.bodyInline));
    console.log('\nBig visible sections (bgL = bg luminance 0-255, high=light):');
    for (const s of res.sample) {
      const flag = (s.bgL !== null && s.bgL > 140 && s.bgA >= 0.5) ? '  <-- STILL LIGHT' : '';
      console.log(`  ${s.tag.padEnd(8)} .${s.cls.padEnd(30)} ${(''+s.w+'x'+s.h).padEnd(9)} top=${(''+s.top).padEnd(5)} bgL=${String(s.bgL).padEnd(4)} a=${s.bgA} done=${s.done?'Y':'n'} inline="${s.inlineBg}" bgImg="${s.bgImg}"${flag}`);
    }
  } finally {
    await browser.close();
  }
})();
