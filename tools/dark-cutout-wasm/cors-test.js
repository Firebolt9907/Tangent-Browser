// Test whether cross-origin mzstatic images can be read via (a) crossOrigin reload or (b) fetch(cors),
// under apple.com's real CSP.
const puppeteer = require('puppeteer-core');
const CHROME = process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const MOBILE_UA = 'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36';

(async () => {
  const browser = await puppeteer.launch({ executablePath: CHROME, headless: 'new',
    args: ['--no-sandbox'], defaultViewport: { width: 412, height: 915, isMobile: true, hasTouch: true, deviceScaleFactor: 2 } });
  try {
    const page = await browser.newPage();
    await page.setUserAgent(MOBILE_UA);
    await page.goto('https://www.apple.com', { waitUntil: 'networkidle2', timeout: 45000 }).catch(e => console.error('nav', e.message));
    await new Promise(r => setTimeout(r, 1500));
    await page.evaluate(async () => { for (let y=0;y<10;y++){ window.scrollBy(0, window.innerHeight); await new Promise(r=>setTimeout(r,300)); } window.scrollTo(0,0); });
    await new Promise(r => setTimeout(r, 1500));
    const res = await page.evaluate(async () => {
      // find a tainting mzstatic image
      var src = null;
      var imgs = document.getElementsByTagName('img');
      for (var i=0;i<imgs.length;i++){ if((imgs[i].currentSrc||'').indexOf('mzstatic')>=0 && imgs[i].naturalWidth>100){ src=imgs[i].currentSrc; break; } }
      if(!src) return {err:'no mzstatic img found'};
      var out = {src:src.slice(0,90)};
      // (a) crossOrigin reload
      out.crossOrigin = await new Promise(res=>{
        var im=new Image(); im.crossOrigin='anonymous';
        im.onload=function(){ try{ var c=document.createElement('canvas');c.width=im.naturalWidth;c.height=im.naturalHeight;var cx=c.getContext('2d');cx.drawImage(im,0,0);cx.getImageData(0,0,2,2);res('CLEAN');}catch(e){res('tainted:'+e.name);} };
        im.onerror=function(){res('load-error');};
        im.src=src;
      });
      // (b) fetch cors -> blob -> createImageBitmap
      out.fetchCors = await (async ()=>{
        try{ var r=await fetch(src,{mode:'cors'}); if(!r.ok) return 'http:'+r.status; var b=await r.blob(); var bm=await createImageBitmap(b); var c=new OffscreenCanvas(bm.width,bm.height); var cx=c.getContext('2d'); cx.drawImage(bm,0,0); cx.getImageData(0,0,2,2); return 'CLEAN'; }catch(e){ return 'err:'+e.name+':'+(e.message||'').slice(0,40); }
      })();
      return out;
    });
    console.log(JSON.stringify(res, null, 2));
  } finally { await browser.close(); }
})();
