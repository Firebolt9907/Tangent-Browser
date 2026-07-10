package com.firebolt9907.luminabrowser.ui.content

import java.util.Locale
import mozilla.components.concept.engine.EngineSession

/**
 * Inject a top spacer `<div>` and a `scroll-padding-top` style so page content sits inside
 * the status-bar / SafariBar top safe area. When `skipHeaderInsets = false`, also walks
 * top-level `position: fixed/sticky` headers pinned near the top and moves their visible
 * content into the safe area, re-running on a MutationObserver. Pass `true` when the
 * EngineView itself is padded (per-site top-padding-fix) to avoid a restyle loop.
 *
 * Bottom inset is intentionally NOT handled here. The bottom chrome (pill / SafariBar)
 * space is reserved natively by GeckoView's dynamic-toolbar clipping
 * (`EngineView.setDynamicToolbarMaxHeight` / `setVerticalClipping` in [ContentHost]).
 * Injecting a bottom spacer `<div>` + `scroll-padding-bottom` on top of that double-pads
 * the page and fights the native toolbar, so it was removed.
 */
internal fun EngineSession.injectViewportSpacers(
    topPx: Int,
    skipHeaderInsets: Boolean = false,
) {
    loadJavascriptUrl(buildViewportSpacerScript(topPx, skipHeaderInsets))
}

/**
 * Builds the injected top-viewport-spacer `javascript:` URL as a plain string, so the
 * hard-won robustness invariants (see [injectViewportSpacers]) can be pinned by JVM unit
 * tests without a device. Pure — no [EngineSession] receiver, no side effects.
 */
internal fun buildViewportSpacerScript(topPx: Int, skipHeaderInsets: Boolean): String {
    return """
        javascript:(()=> {
        const topPx=${topPx};
        const skipHeader=${if (skipHeaderInsets) "true" else "false"};
        const top=topPx+'px';
        const topId='lumina-top-scroll-spacer';
        const styleId='lumina-viewport-scroll-style';
        function ensureStyle(){let s=document.getElementById(styleId);if(!s){s=document.createElement('style');s.id=styleId;(document.head||document.documentElement).appendChild(s)}s.textContent='html,body{scroll-padding-top:'+top+'!important;}'}
        ensureStyle();
        function px(value){const n=parseFloat(value);return Number.isFinite(n)?n:0}
        function spacer(id,h){const host=document.body||document.documentElement;let el=document.getElementById(id);if(!el){el=document.createElement('div');el.id=id;host.prepend(el)}else if(el.parentElement!==host){host.prepend(el)}el.setAttribute('aria-hidden','true');el.dataset.luminaIgnoreSafeTop='true';const hcs=getComputedStyle(host);const hdisp=hcs.display;const flexRow=(hdisp==='flex'||hdisp==='inline-flex')&&((hcs.flexDirection||'row').indexOf('row')===0)&&(hcs.flexWrap||'nowrap')==='nowrap';if(flexRow){if(host.dataset.luminaHostPadSaved!=='true'){host.dataset.luminaHostOrigPad=host.style.paddingTop||'';host.dataset.luminaHostBasePad=px(hcs.paddingTop);host.dataset.luminaHostPadSaved='true'}const tgt=(px(host.dataset.luminaHostBasePad)+topPx)+'px';if(host.__luminaHostPad!==tgt){host.style.setProperty('padding-top',tgt,'important');host.__luminaHostPad=tgt}el.style.cssText='display:none!important;';return}if(host.dataset.luminaHostPadSaved==='true'){host.style.paddingTop=host.dataset.luminaHostOrigPad||'';host.dataset.luminaHostPadSaved='false';host.__luminaHostPad=null}el.style.cssText='height:'+h+'!important;min-height:'+h+'!important;display:block!important;width:100%!important;flex:0 0 auto!important;grid-column:1 / -1!important;order:-2147483647!important;pointer-events:none!important;visibility:hidden!important;';const parent=el.parentElement;if(parent){const pcs=getComputedStyle(parent);const disp=pcs.display;const isCol=(disp==='flex'||disp==='inline-flex')?((pcs.flexDirection||'row').indexOf('column')===0):(disp==='grid'||disp==='inline-grid');const gap=isCol?(px(pcs.rowGap)||px(pcs.gap)):0;if(gap>0){el.style.setProperty('margin-bottom',(-gap)+'px','important')}else{el.style.removeProperty('margin-bottom')}}}
        function collect(root,out){if(!root)return out;const all=root.querySelectorAll?root.querySelectorAll('*'):[];all.forEach(el=>{out.push(el);if(el.shadowRoot)collect(el.shadowRoot,out)});return out}
        function restore(el){if(el.dataset.luminaSafeTopApplied!=='true')return;el.style.paddingTop=el.dataset.luminaOrigPad||'';el.style.height=el.dataset.luminaOrigH||'';el.style.minHeight=el.dataset.luminaOrigMh||'';el.style.top=el.dataset.luminaOrigTop||'';if(el.dataset.luminaOrigShadow!=null)el.style.boxShadow=el.dataset.luminaOrigShadow;el.dataset.luminaSafeTopApplied='false'}
        function depth(el){let d=0;for(let n=el;n&&n.parentElement;n=n.parentElement)d++;return d}
        function pinnedTop(el,cs,rect){if(cs.top!=='auto'&&px(cs.top)<=1)return true;return rect.top<=Math.max(1,topPx+1)}
        function isCandidate(el,cs,rect){if(el.id===topId||el.dataset.luminaIgnoreSafeTop==='true')return false;if(cs.display==='none'||cs.visibility==='hidden'||cs.opacity==='0'||cs.pointerEvents==='none')return false;const pos=cs.position;if(pos!=='fixed'&&pos!=='sticky')return false;const maxH=Math.min(400,Math.max(240,window.innerHeight*0.45));if(rect.height<24||rect.height>maxH||rect.width<window.innerWidth*0.30)return false;const nearTop=rect.top<=topPx+80;const stickyReachesTop=pos==='sticky'&&cs.top!=='auto'&&px(cs.top)<=1&&rect.top<=window.innerHeight*1.5;if(rect.bottom<=0||(!nearTop&&!stickyReachesTop))return false;if(pos==='fixed'&&rect.top>=topPx-1)return false;return pinnedTop(el,cs,rect)}
        function topLevel(candidates){const chosen=[];candidates.sort((a,b)=>a.depth-b.depth||b.rect.width-a.rect.width);candidates.forEach(c=>{if(!chosen.some(p=>p.el!==c.el&&p.el.contains(c.el)))chosen.push(c)});return chosen}
        function applyFixed(el,cs,rect){if(el.dataset.luminaOrigPad==null){el.dataset.luminaOrigPad=el.style.paddingTop||'';el.dataset.luminaOrigH=el.style.height||'';el.dataset.luminaOrigMh=el.style.minHeight||'';el.dataset.luminaOrigTop=el.style.top||''}el.style.setProperty('padding-top',(px(cs.paddingTop)+topPx)+'px','important');el.style.setProperty('top',Math.max(0,px(cs.top))+'px','important');if(cs.boxSizing==='border-box'&&cs.height!=='auto'){const newH=rect.height+topPx;el.style.setProperty('height',newH+'px','important');el.style.setProperty('min-height',newH+'px','important');}el.dataset.luminaSafeTopApplied='true'}
        function applySticky(el,cs){if(el.dataset.luminaOrigPad==null){el.dataset.luminaOrigPad=el.style.paddingTop||'';el.dataset.luminaOrigH=el.style.height||'';el.dataset.luminaOrigMh=el.style.minHeight||'';el.dataset.luminaOrigTop=el.style.top||'';el.dataset.luminaOrigShadow=el.style.boxShadow||''}el.style.setProperty('top',(px(cs.top)+topPx)+'px','important');const bg=cs.backgroundColor;if(bg&&bg!=='transparent'&&bg!=='rgba(0, 0, 0, 0)'){const prev=(cs.boxShadow&&cs.boxShadow!=='none')?cs.boxShadow+',':'';el.style.setProperty('box-shadow',prev+'0 -'+topPx+'px 0 0 '+bg,'important')}el.dataset.luminaSafeTopApplied='true'}
        function applyHeaderInsets(){if(!document.documentElement)return;if(topPx<=0){(window.__luminaInsetedEls||[]).forEach(restore);window.__luminaInsetedEls=[];return}if(document.hidden)return;const now=Date.now();const cd=window.__luminaCooldownMs||500;const since=now-(window.__luminaLastHeaderInsetMs||0);if(since<cd){if(!window.__luminaTrailingTimer){window.__luminaTrailingTimer=setTimeout(function(){window.__luminaTrailingTimer=0;applyHeaderInsets()},cd-since)}return;}if(window.__luminaTrailingTimer){clearTimeout(window.__luminaTrailingTimer);window.__luminaTrailingTimer=0}ensureStyle();spacer(topId,top);(window.__luminaInsetedEls||[]).forEach(restore);const all=collect(document,[]);const candidates=[];all.forEach(el=>{const cs=getComputedStyle(el);const pos=cs.position;if(pos!=='fixed'&&pos!=='sticky')return;const rect=el.getBoundingClientRect();if(pos==='sticky'&&cs.top!=='auto'&&px(cs.top)<=1&&window.__luminaStickyIO){try{window.__luminaStickyIO.observe(el)}catch(e){}}if(isCandidate(el,cs,rect))candidates.push({el,cs,rect,depth:depth(el)})});const chosen=topLevel(candidates);const chosenKey=chosen.map(c=>c.el).join('|');window.__luminaStableCount=(chosenKey===window.__luminaLastChosenKey)?(window.__luminaStableCount||0)+1:0;window.__luminaCooldownMs=window.__luminaStableCount>=3?2000:500;window.__luminaApplyingHeaderInsets=true;window.__luminaInsetedEls=[];try{chosen.forEach(c=>{try{if(c.cs.position==='fixed')applyFixed(c.el,c.cs,c.rect);else applySticky(c.el,c.cs);window.__luminaInsetedEls.push(c.el)}catch(e){}})}finally{window.__luminaLastChosenKey=chosenKey;window.__luminaLastHeaderInsetMs=Date.now();window.__luminaApplyingHeaderInsets=false;}}
        function schedule(){cancelAnimationFrame(window.__luminaHeaderInsetFrame||0);window.__luminaHeaderInsetFrame=requestAnimationFrame(applyHeaderInsets)}
        spacer(topId,top);
        if(window.__luminaHeaderInsetObserver){window.__luminaHeaderInsetObserver.disconnect();window.__luminaHeaderInsetObserver=null}
        if(!skipHeader){schedule();setTimeout(schedule,1000);setTimeout(schedule,3000);setTimeout(schedule,10000);window.__luminaHeaderInsetObserver=new MutationObserver(function(records){if(window.__luminaApplyingHeaderInsets)return;const allOurs=records.every(function(r){return r.target.dataset&&(r.target.dataset.luminaSafeTopApplied!=null||r.target.dataset.luminaIgnoreSafeTop==='true')});if(allOurs)return;schedule()});window.__luminaHeaderInsetObserver.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['class','style','slot','hidden','aria-hidden']});if(!window.__luminaStickyIO&&typeof IntersectionObserver==='function'){window.__luminaStickyIO=new IntersectionObserver(function(){schedule()},{threshold:0})}if(!window.__luminaListenersBound){window.__luminaListenersBound=true;window.addEventListener('resize',function(){schedule()},{passive:true});window.addEventListener('load',function(){schedule()},{passive:true});if(document.fonts&&document.fonts.ready){document.fonts.ready.then(function(){schedule()}).catch(function(){})}document.addEventListener('visibilitychange',function(){if(!document.hidden)schedule()});var wrapHist=function(n){var o=history[n];if(typeof o==='function'&&!o.__luminaWrapped){var w=function(){var r=o.apply(this,arguments);schedule();setTimeout(schedule,300);return r};w.__luminaWrapped=true;try{history[n]=w}catch(e){}}};wrapHist('pushState');wrapHist('replaceState');window.addEventListener('popstate',function(){schedule();setTimeout(schedule,300)});}}
        })()
    """.trimIndent().replace("\n", "")
}

/** Set `document.documentElement.style.zoom = factor` (empty string when `factor == 1.0`). */
internal fun EngineSession.injectPageZoom(factor: Float) {
    val literal = if (factor == 1.0f) "''" else "'${"%.4f".format(Locale.US, factor)}'"
    val script = """
        javascript:(()=>{try{if(document.documentElement){document.documentElement.style.zoom=${literal};}}catch(e){}})()
    """.trimIndent().replace("\n", "")
    loadJavascriptUrl(script)
}
