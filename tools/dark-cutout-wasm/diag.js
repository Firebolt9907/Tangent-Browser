// Diagnostic: per-image report for the cutout pass on a real site.
const fs = require('fs');
const path = require('path');
const puppeteer = require('puppeteer-core');
const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';
const B64 = fs.readFileSync(path.resolve(__dirname, 'wasm.b64'), 'utf8').trim();

function diagSrc(b64, P) {
  return `(async function(){
    var B64='${b64}'; var P=${JSON.stringify(P)};
    function b2u(b){var s=atob(b),a=new Uint8Array(s.length);for(var i=0;i<s.length;i++)a[i]=s.charCodeAt(i);return a;}
    var mod=await WebAssembly.instantiate(b2u(B64),{});
    var ex=mod.instance.exports; var mem=ex.memory; var CAP=ex.capacity(); var pptr=ex.pixels_ptr();
    function cleanLoad(src){return new Promise(function(res){var p=new Image();p.crossOrigin='anonymous';p.onload=function(){res(p);};p.onerror=function(){res(null);};p.src=src;});}
    var rep=[];
    var imgs=Array.prototype.slice.call(document.getElementsByTagName('img'));
    for(var i=0;i<imgs.length;i++){
      var img=imgs[i];
      var iw=img.naturalWidth,ih=img.naturalHeight;
      var cw=img.clientWidth,ch=img.clientHeight;
      var r={src:(img.currentSrc||img.src||'').slice(0,80),nat:iw+'x'+ih,client:cw+'x'+ch};
      if(!iw||!ih){r.res='no-natural';rep.push(r);continue;}
      if(Math.min(cw,ch)<P.minSize){r.res='too-small';rep.push(r);continue;}
      var probe=await cleanLoad(img.currentSrc||img.src);
      if(!probe){r.res='CORS-REFUSED';rep.push(r);continue;}
      iw=probe.naturalWidth;ih=probe.naturalHeight;
      var scale=Math.min(1,Math.sqrt(CAP/(iw*ih)),P.maxDim/Math.max(iw,ih));
      var w=Math.max(1,Math.round(iw*scale)),h=Math.max(1,Math.round(ih*scale));
      r.proc=w+'x'+h+' scale='+scale.toFixed(3);
      var cv=document.createElement('canvas');cv.width=w;cv.height=h;
      var ctx=cv.getContext('2d',{willReadFrequently:true});
      try{ctx.drawImage(probe,0,0,w,h);}catch(e){r.res='draw-err';rep.push(r);continue;}
      var id;try{id=ctx.getImageData(0,0,w,h);}catch(e){r.res='TAINTED';rep.push(r);continue;}
      var view=new Uint8Array(mem.buffer,pptr,w*h*4); view.set(id.data);
      var filled=ex.cutout(w,h,P.lSeed,P.lFloor,P.stepMax,P.minAlpha);
      var frac=filled/(w*h); r.frac=frac.toFixed(3);
      if(frac<P.minFrac){r.res='frac-too-low';}
      else if(frac>P.maxFrac){r.res='frac-too-high';}
      else {r.res='KEYED';}
      rep.push(r);
    }
    // Count CSS background-image elements with meaningful size
    var bgCount=0,bgLight=0;
    var all=document.querySelectorAll('*');
    for(var j=0;j<all.length;j++){
      var cs=getComputedStyle(all[j]);
      if(cs.backgroundImage && cs.backgroundImage.indexOf('url(')===0){
        var rct=all[j].getBoundingClientRect();
        if(rct.width>=150&&rct.height>=150){bgCount++;}
      }
    }
    return {images:rep, cssBgLarge:bgCount};
  })();`;
}

(async () => {
  const url = process.argv[2] || 'https://www.apple.com';
  const P = { minSize:100, maxDim:768, lSeed:195, lFloor:150, stepMax:30, minAlpha:128, minFrac:0.02, maxFrac:0.985 };
  const browser = await puppeteer.launch({ executablePath: CHROME, headless: 'new',
    args: ['--no-sandbox', '--hide-scrollbars'],
    defaultViewport: { width: 412, height: 915, isMobile: true, hasTouch: true, deviceScaleFactor: 2 } });
  try {
    const page = await browser.newPage();
    await page.setUserAgent(MOBILE_UA);
    await page.emulateMediaFeatures([{ name: 'prefers-color-scheme', value: 'dark' }]);
    await page.goto(url, { waitUntil: 'networkidle2', timeout: 45000 }).catch(e => console.error('nav', e.message));
    await new Promise(r => setTimeout(r, 2000));
    // scroll to trigger lazy loads
    await page.evaluate(async () => { for (let y=0;y<8;y++){ window.scrollBy(0, window.innerHeight); await new Promise(r=>setTimeout(r,300)); } window.scrollTo(0,0); });
    await new Promise(r => setTimeout(r, 1500));
    const out = await page.evaluate(diagSrc(B64, P));
    console.log('CSS background-image elems >=150px:', out.cssBgLarge);
    console.log('IMG count:', out.images.length);
    const summary = {};
    for (const im of out.images) summary[im.res] = (summary[im.res]||0)+1;
    console.log('by result:', JSON.stringify(summary));
    console.log('--- per image ---');
    for (const im of out.images) console.log(`[${im.res}] nat=${im.nat} client=${im.client} frac=${im.frac||'-'} ${im.proc||''} ${im.src}`);
  } finally { await browser.close(); }
})();
