// Validates the compiled WASM cutout kernel against a real site: loads it (dark pref), runs the
// dynamic recolor for element bgs, then keys out each large image's light background via the kernel.
// Main-thread instantiation here (correctness/quality first); the shipping path uses a worker.
const fs = require('fs');
const path = require('path');
const puppeteer = require('puppeteer-core');
const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';
const B64 = fs.readFileSync(path.resolve(__dirname, 'wasm.b64'), 'utf8').trim();
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

// Driver injected into the page. Params tunable from CLI.
function driverSrc(b64, params) {
  return `(async function(){
    var B64='${b64}';
    var P=${JSON.stringify(params)};
    function b2u(b){var s=atob(b),a=new Uint8Array(s.length);for(var i=0;i<s.length;i++)a[i]=s.charCodeAt(i);return a;}
    var mod=await WebAssembly.instantiate(b2u(B64),{});
    var ex=mod.instance.exports; var mem=ex.memory; var CAP=ex.capacity();
    var pptr=ex.pixels_ptr();
    window.__cutStats={done:0,keyed:0,skipped:0,tainted:0};
    function cleanLoad(src){return new Promise(function(res){var p=new Image();p.crossOrigin='anonymous';p.onload=function(){res(p);};p.onerror=function(){res(null);};p.src=src;});}
    async function processImg(img){
      if(img.__lumCut)return;
      var iw=img.naturalWidth,ih=img.naturalHeight; if(!iw||!ih)return;
      if(Math.min(img.clientWidth,img.clientHeight)<P.minSize)return;
      img.__lumCut=1;
      // Re-fetch the source with CORS so cross-origin (CDN) images can be read (see driver.js).
      var probe=await cleanLoad(img.currentSrc||img.src);
      if(!probe){window.__cutStats.tainted++;return;}
      iw=probe.naturalWidth;ih=probe.naturalHeight;
      // downscale so w*h<=CAP
      var scale=Math.min(1,Math.sqrt(CAP/(iw*ih)),P.maxDim/Math.max(iw,ih));
      var w=Math.max(1,Math.round(iw*scale)),h=Math.max(1,Math.round(ih*scale));
      var cv=document.createElement('canvas');cv.width=w;cv.height=h;
      var ctx=cv.getContext('2d',{willReadFrequently:true});
      try{ctx.drawImage(probe,0,0,w,h);}catch(e){return;}
      var id;try{id=ctx.getImageData(0,0,w,h);}catch(e){window.__cutStats.tainted++;return;}
      var view=new Uint8Array(mem.buffer,pptr,w*h*4);
      view.set(id.data);
      ex.smooth_rgb(w,h,2);
      var filled=ex.cutout(w,h,P.lSeed,P.lFloor,P.stepMax,P.minAlpha);
      if(filled>0)ex.blur_alpha(w,h,6);
      var frac=filled/(w*h);
      window.__cutStats.done++;
      if(frac<P.minFrac||frac>P.maxFrac){window.__cutStats.skipped++;return;}
      // Collapse to an alpha mask (RGB=0, keyed alpha) in the kernel, then apply it to the ORIGINAL
      // image via CSS mask-image — no src swap, so the image stays full-resolution (see driver.js).
      ex.to_alpha_mask(w,h);
      id.data.set(view);
      ctx.putImageData(id,0,0);
      var url=cv.toDataURL('image/png');
      applyMask(img,url,probe.naturalWidth,probe.naturalHeight);
      window.__cutStats.keyed++;
    }
    function applyMask(img,url,natW,natH){
      var boxW=img.clientWidth,boxH=img.clientHeight; if(!boxW||!boxH)return;
      var fit='fill'; try{fit=getComputedStyle(img).objectFit||'fill';}catch(e){}
      var size='100% 100%',pos='0 0';
      if(fit!=='fill'&&natW>0&&natH>0){
        var na=natW/natH,ba=boxW/boxH,cw,ch;
        if(fit==='none'||(fit==='scale-down'&&natW<=boxW&&natH<=boxH)){cw=natW;ch=natH;}
        else if(fit==='cover'){if(na>ba){ch=boxH;cw=boxH*na;}else{cw=boxW;ch=boxW/na;}}
        else{if(na>ba){cw=boxW;ch=boxW/na;}else{ch=boxH;cw=boxH*na;}}
        size=Math.round(cw)+'px '+Math.round(ch)+'px';pos='center';
      }
      var s=img.style;
      s.setProperty('-webkit-mask-image','url('+url+')','important');
      s.setProperty('mask-image','url('+url+')','important');
      s.setProperty('-webkit-mask-size',size,'important');
      s.setProperty('mask-size',size,'important');
      s.setProperty('-webkit-mask-position',pos,'important');
      s.setProperty('mask-position',pos,'important');
      s.setProperty('-webkit-mask-repeat','no-repeat','important');
      s.setProperty('mask-repeat','no-repeat','important');
      s.setProperty('mask-mode','alpha','important');
    }
    function sweep(){var imgs=document.getElementsByTagName('img');for(var i=0;i<imgs.length;i++){if(imgs[i].complete)processImg(imgs[i]);else imgs[i].addEventListener('load',function(e){processImg(e.target);},{once:true});}}
    sweep(); window.__cutSweep=sweep;
  })();`;
}

(async () => {
  const url = process.argv[2] || 'https://www.apple.com';
  const out = process.argv[3] || path.resolve(__dirname, 'out-apple.png');
  const params = {
    minSize: 100, maxDim: 768,
    lSeed: 195, lFloor: 150, stepMax: 30, minAlpha: 128,
    minFrac: 0.02, maxFrac: 0.985,
  };
  const browser = await puppeteer.launch({ executablePath: CHROME, headless: 'new',
    args: ['--no-sandbox', '--hide-scrollbars'],
    defaultViewport: { width: 412, height: 915, isMobile: true, hasTouch: true, deviceScaleFactor: 2 } });
  try {
    const page = await browser.newPage();
    await page.setUserAgent(MOBILE_UA);
    await page.emulateMediaFeatures([{ name: 'prefers-color-scheme', value: 'dark' }]);
    await page.goto(url, { waitUntil: 'networkidle2', timeout: 45000 }).catch(e => console.error('nav', e.message));
    await new Promise(r => setTimeout(r, 1500));
    await page.evaluate(async () => { for (let y=0;y<10;y++){ window.scrollBy(0, window.innerHeight); await new Promise(r=>setTimeout(r,300)); } window.scrollTo(0,0); });
    await new Promise(r => setTimeout(r, 1200));
    await page.evaluate(extractDynamic());          // element-bg recolor
    await new Promise(r => setTimeout(r, 800));
    await page.evaluate(driverSrc(B64, params));    // WASM image cutout
    await new Promise(r => setTimeout(r, 1500));
    await page.evaluate(() => window.__cutSweep && window.__cutSweep());
    await new Promise(r => setTimeout(r, 800));
    const stats = await page.evaluate(() => window.__cutStats);
    console.log('cutout stats:', JSON.stringify(stats), 'params:', JSON.stringify(params));
    await page.screenshot({ path: out, fullPage: false });
    console.log('shot ->', out);
  } finally { await browser.close(); }
})();
