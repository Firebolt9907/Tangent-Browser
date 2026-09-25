// Real URL transport, scroll-state pixels and animated menu style ownership.
const assert = require('node:assert/strict');
const puppeteer = require('puppeteer-core');
const {PNG} = require('pngjs');
const {buildViewportSpacerScript} = require('./extractScript');
const wait = ms => new Promise(r => setTimeout(r, ms));
(async () => {
  const browser = await puppeteer.launch({executablePath: process.env.CHROME_PATH || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome', headless: true});
  try {
    for (const type of ['sticky', 'fixed', 'static']) {
      const page = await browser.newPage();
      page.on('pageerror', e=>console.error('PAGE ERROR',e.message));
      await page.setViewport({width: 400, height: 800});
      await page.setContent(`<style>body{margin:0;background:rgb(200,30,40)}header{position:${type};top:0;width:100%;z-index:20;transition:transform .25s;height:60px;box-sizing:border-box}nav{height:60px;background:rgb(20,80,140)}.menu{height:0;overflow:hidden;transition:height .5s;background:white}header.open .menu{height:280px}main{height:2500px}header.away{transform:translateY(-150px)}</style><header><nav>Logo</nav><div class="menu">Dropdown</div></header><main>Article</main>`);
      const inject = px => page.evaluate(url => {location.href = url}, 'javascript:' + encodeURIComponent(buildViewportSpacerScript(px, false).slice(11)));
      await inject(50); await wait(400);
      if(type==='sticky') {
        await page.evaluate(()=>{const lead=document.createElement('div');lead.id='lead';lead.style.height='180px';document.querySelector('header').before(lead)});await wait(600);
        assert.equal(await page.$('#tangent-pinned-header-fill'),null,'not-yet-stuck header paints the status bar');
      }
      await page.evaluate(() => scrollTo(0, 350)); await wait(300);
      const pixel = async () => {const p=PNG.sync.read(Buffer.from(await page.screenshot()));const i=(10*p.width+8)*4;return Array.from(p.data.slice(i,i+3))};
      assert.deepEqual(await pixel(), type==='static'?[200,30,40]:[20,80,140], `${type}: scrolled band`);
      if(type!=='static') {
        // Observe the actual header, not the paint-only layer. No repeated clear/reapply writes.
        await page.evaluate(() => {window.writes=0;new MutationObserver(rs=>window.writes+=rs.filter(r=>r.attributeName==='style').length).observe(document.querySelector('header'),{attributes:true});document.querySelector('header').classList.add('open')});
        await wait(2400);
        assert.equal(await page.evaluate(()=>window.writes),0,`${type}: menu causes header style reset`);
        await page.evaluate(()=>document.querySelector('header').classList.remove('open'));await wait(600);
        await page.evaluate(()=>document.querySelector('header').classList.add('away'));await wait(400);
        assert.deepEqual(await pixel(),[200,30,40],`${type}: hidden header leaves opaque band`);
        await page.evaluate(()=>document.querySelector('header').classList.remove('away'));await wait(400);
        assert.deepEqual(await pixel(),[20,80,140],`${type}: returning header loses fill`);
        await inject(64);await wait(300);
        assert.equal(await page.evaluate(()=>document.querySelector('#tangent-pinned-header-fill')?.getBoundingClientRect().height),64,'fill uses stale inset');
        await inject(0);await wait(1200);
        assert.equal(await page.$('#tangent-pinned-header-fill'),null,'disabled fill remains');
      }
      console.log('PASS',type);await page.close();
    }
  } finally {await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
