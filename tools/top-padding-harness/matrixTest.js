// Offline behavioral matrix for the exact top-inset script emitted by Kotlin.
// It tests geometry and lifecycle, never source snippets or private controller fields.
// Usage: node matrixTest.js [topPx] [case ...]
const puppeteer=require('puppeteer-core');
const {PNG}=require('pngjs');
const path=require('path');
const {buildViewportSpacerScript}=require('./extractScript');
const CHROME=process.env.CHROME_PATH||'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome';
const topPx=Number(process.argv[2])||48, only=process.argv.slice(3);
const fixture=`file://${path.resolve(__dirname,'fixtures/inset-matrix.html')}`;
const cases=['flowOnly','fixedViewport','fixedPositive','stickyPositive','gradient','transparent','stacked','absoluteChild','transformed','contained','willChange','narrowControl','nestedSafe','nestedUnsafe','shadow','late','promoteStyle','hiddenAuto','authorChange','lifecycle','negativeMediaSidebar'];
const wait=ms=>new Promise(r=>setTimeout(r,ms));
const script=(px,skip=false)=>buildViewportSpacerScript(px,skip).replace(/^javascript:/,'');
const near=(a,b,t=3)=>Number.isFinite(a)&&Math.abs(a-b)<=t;
const below=(r,y)=>r&&r.top>=y-2;
const check=(ok,msg,bad)=>{if(!ok)bad.push(msg)};
const snapshot=`(()=>{
 const shadow=document.querySelector('#shadowHost')?.shadowRoot;
 const h=document.querySelector('#header,#promo,#contained')||shadow?.querySelector('#header');
 const label=h?.querySelector('span')||document.querySelector('#absoluteChild'); const raw=e=>{if(!e)return null;const r=e.getBoundingClientRect();return{top:r.top,bottom:r.bottom,left:r.left,right:r.right,width:r.width,height:r.height}};
 const cs=e=>e?getComputedStyle(e):null;
 return {header:raw(h),label:raw(label),spacer:raw(document.querySelector('#tangent-top-scroll-spacer')),
  headerTop:h?parseFloat(cs(h).top):null,headerPad:h?parseFloat(cs(h).paddingTop):null,headerHeight:h?raw(h).height:null,
  control:raw(document.querySelector('#control')),scroller:raw(document.querySelector('#scroller')),
  media:raw(document.querySelector('#media')),sidebar:raw(document.querySelector('#sidebar'))};
})()`;
const author=`(()=>{const h=document.querySelector('#header')||document.querySelector('#contained')||document.querySelector('#shadowHost')?.shadowRoot?.querySelector('#header');return h&&{top:h.style.getPropertyValue('top'),topPriority:h.style.getPropertyPriority('top'),pad:h.style.getPropertyValue('padding-top'),padPriority:h.style.getPropertyPriority('padding-top'),height:h.style.getPropertyValue('height')}})()`;
async function assertCase(page,name,before){
 const bad=[];let s=await page.evaluate(snapshot), style=await page.evaluate(author);
 if(name==='flowOnly')check(near(s.spacer?.height,Math.round(topPx*0.6),1),`flow spacer height ${s.spacer?.height} did not use the compact inset`,bad);
 if(['fixedViewport','fixedPositive','stickyPositive','gradient','transparent','absoluteChild','shadow','late','authorChange','lifecycle'].includes(name))check(below(s.label,topPx),`${name}: content remains in band (${s.label?.top})`,bad);
 // The contract is visual: one inset's worth of movement, whether the controller uses a root
 // translation, padding, a size adjustment, or a combination appropriate to the layout.
 if(['fixedViewport','fixedPositive','stickyPositive','gradient','absoluteChild'].includes(name))check(s.label.top<=before.label.top+topPx+4,`${name}: content shifted more than one inset (${before.label.top} -> ${s.label.top})`,bad);
 if(name==='stacked'){const rows=await page.evaluate(`(()=>['promo','nav'].map(id=>{const e=document.querySelector('#'+id),r=e.getBoundingClientRect(),l=e.querySelector('span').getBoundingClientRect();return{id,top:r.top,bottom:r.bottom,labelTop:l.top,labelBottom:l.bottom}}))()`);check(rows[0].labelTop>=topPx-2&&rows[1].labelTop>=topPx-2,`stacked label remains in band (${rows.map(r=>r.labelTop)})`,bad);check(rows[1].labelTop-rows[0].labelTop>=36,`stacked label spacing collapsed (${rows[0].labelTop}, ${rows[1].labelTop})`,bad);check(rows[0].labelBottom<=rows[1].labelTop+2,`stacked visible content overlaps (${rows[0].labelBottom} > ${rows[1].labelTop})`,bad)}
 if(name==='absoluteChild')check(below(s.label,topPx),'absolute child remains in band',bad);
 if(['transformed','contained','willChange'].includes(name)){check(style?.top==='0px'&&style?.pad==='',`${name}: mutated fixed-containing-block child ${JSON.stringify(style)}`,bad);check(below(s.label,topPx),`${name}: flow owning the containing block was not inset (${s.label?.top})`,bad)}
 if(name==='narrowControl')check(below(s.control,topPx),`narrow control remains in band (${s.control?.top})`,bad);
 if(name==='nestedSafe'){check(near(s.scroller?.top,64),`already-safe scroller moved to ${s.scroller?.top}`,bad);check(below(s.label,topPx),`safe nested label moved into band`,bad)}
 if(name==='nestedUnsafe')check(below(s.label,topPx),`unsafe nested sticky remains in band (${s.label?.top})`,bad);
 if(name==='shadow'){await page.evaluate(`(()=>{const h=document.querySelector('#shadowHost').shadowRoot.querySelector('#header');h.style.setProperty('border-bottom','3px solid rgb(1,2,3)')})()`);await wait(750);s=await page.evaluate(snapshot);check(below(s.label,topPx),`shadow-root mutation lost inset (${s.label?.top})`,bad)}
 if(name==='promoteStyle'){await page.evaluate('window.promoteToFixed()');await wait(750);s=await page.evaluate(snapshot);check(below(s.label,topPx),`stylesheet-promoted fixed header remains in band (${s.label?.top})`,bad)}
 if(name==='hiddenAuto'){await page.evaluate('window.reveal()');await wait(750);s=await page.evaluate(snapshot);check(below(s.label,topPx),`CSS-hidden header was not inset after reveal (${s.label?.top})`,bad);await page.evaluate('window.showAuto()');await wait(750);s=await page.evaluate(snapshot);check(below(s.label,topPx),`auto-hidden header was not re-inset (${s.label?.top})`,bad)}
 if(name==='authorChange'){await page.evaluate('window.changeAuthor()');await wait(750);s=await page.evaluate(snapshot);check(below(s.label,topPx),`author rewrite not re-inset (${s.label?.top})`,bad);await page.evaluate(script(0));await wait(100);style=await page.evaluate(author);check(style?.top==='7px'&&style?.topPriority==='important'&&style?.pad==='5px'&&style?.padPriority==='important',`author declarations not retained: ${JSON.stringify(style)}`,bad)}
 if(name==='lifecycle'){await page.evaluate(script(topPx,true));await wait(100);style=await page.evaluate(author);check(style?.top==='0px'&&style?.pad==='4px',`skipHeader did not restore header author style: ${JSON.stringify(style)}`,bad);await page.evaluate(script(0));await wait(100);s=await page.evaluate(snapshot);style=await page.evaluate(author);const rootSpt=await page.evaluate(`(()=>({value:document.documentElement.style.getPropertyValue('scroll-padding-top'),priority:document.documentElement.style.getPropertyPriority('scroll-padding-top')}))()`);check(style?.top==='0px'&&style?.topPriority==='important'&&style?.pad==='4px'&&style?.padPriority==='important',`disable did not restore header author style: ${JSON.stringify(style)}`,bad);check(rootSpt.value==='13px'&&rootSpt.priority==='important',`disable did not restore page scroll padding: ${JSON.stringify(rootSpt)}`,bad);check(!s.spacer||s.spacer.height<1,`disable left top spacer ${s.spacer?.height}`,bad);await page.evaluate(script(64));await wait(120);s=await page.evaluate(snapshot);check(below(s.label,64),`re-enable/update missed 64px band (${s.label?.top})`,bad);check(s.label.top<=64+20,`re-enable double-shifted content to ${s.label.top}`,bad)}
 if(name==='negativeMediaSidebar'){const stable=await page.evaluate(`(()=>['media','sidebar'].map(id=>{const e=document.querySelector('#'+id);return{id,top:e.style.getPropertyValue('top'),pad:e.style.getPropertyValue('padding-top')} }))()`);const media=stable[0],side=stable[1];check(media.top==='0px'&&media.pad==='',`full-bleed media modified: ${JSON.stringify(media)}`,bad);check(side.top==='90px'&&side.pad==='',`off-band sidebar modified: ${JSON.stringify(side)}`,bad)}
 // The shipping script deliberately schedules a 1s and a 3s late-mount reconciliation. Begin
 // the quiet sample after both have had a chance to run; otherwise this measures planned work,
 // rather than a self-sustaining observer loop.
 await wait(2800);
 const mutations=await page.evaluate(`new Promise(resolve=>{let n=0;const o=new MutationObserver(r=>n+=r.length);o.observe(document.documentElement,{subtree:true,childList:true,attributes:true});setTimeout(()=>{o.disconnect();resolve(n)},700)})`);
 check(mutations<=2,`${name}: observer did not settle (${mutations} quiet-window mutations)`,bad);return bad;
}
async function assertColor(page,name,bad){
 // Sample a deliberately blank part of the bar.  The prior centre sample was often inside the
 // header label, which compared the cutout paint to a white glyph rather than the bar itself.
 // These are pixels in the header background, not its text/icons, so this is a paint contract.
 if(!['flowOnly','fixedViewport','stickyPositive','gradient','stacked','absoluteChild'].includes(name))return;
 const read=async()=>PNG.sync.read(Buffer.from(await page.screenshot({encoding:'binary'})));
 const pixel=(png,x,y)=>{const i=(png.width*y+x)*4;return [png.data[i],png.data[i+1],png.data[i+2]]};
 const same=(a,b,t=12)=>a.every((v,i)=>Math.abs(v-b[i])<=t);
 const x=name==='gradient'?40:name==='absoluteChild'?360:8;
 // Positive-offset bars start below topPx after correction, so sample their own background rather
 // than assuming it begins at topPx.  In the stacked case `snapshot.header` is the top promo bar.
 const expected=(png,state)=>pixel(png,x,Math.max(0,Math.min(png.height-1,Math.round((state.header?.top ?? topPx)+8))));
 const initial=await read();
 const initialState=await page.evaluate(snapshot);
 const strip=pixel(initial,x,10);
 check(same(strip,expected(initial,initialState)),`${name}: top strip ${strip} does not continue header paint ${expected(initial,initialState)}`,bad);
 await page.evaluate('window.scrollTo(0,360)');await wait(140);
 const scrolled=await read();
 const scrolledState=await page.evaluate(snapshot);
 const scrolledStrip=pixel(scrolled,x,10);
 check(same(scrolledStrip,expected(scrolled,scrolledState)),`${name}: scrolled top strip ${scrolledStrip} does not continue header paint ${expected(scrolled,scrolledState)}`,bad);
 await page.evaluate('window.scrollTo(0,0)')
}
(async()=>{const browser=await puppeteer.launch({executablePath:CHROME,headless:'new',args:['--no-sandbox','--hide-scrollbars'],defaultViewport:{width:412,height:915,isMobile:true,hasTouch:true,deviceScaleFactor:1}});let failures=0;try{for(const name of only.length?only:cases){const page=await browser.newPage();try{await page.goto(`${fixture}?case=${name}`,{waitUntil:'load'});if(name==='lifecycle')await page.evaluate(`document.documentElement.style.setProperty('scroll-padding-top','13px','important')`);const before=await page.evaluate(snapshot);await page.evaluate(script(topPx));if(name==='late'){await wait(100);await page.evaluate('window.mountLate()')}await wait(800);const bad=await assertCase(page,name,before);await assertColor(page,name,bad);if(bad.length){failures++;console.log(`FAIL ${name}: ${bad.join('; ')}`)}else console.log(`PASS ${name}`)}finally{await page.close()}}}finally{await browser.close()}console.log(`RESULT: ${failures?`FAIL ${failures} matrix case(s)`: `PASS ${only.length||cases.length} matrix cases`}`);process.exitCode=failures?1:0})().catch(e=>{console.error(e.stack||e);process.exitCode=1});
