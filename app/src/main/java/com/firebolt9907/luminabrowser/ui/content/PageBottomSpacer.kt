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
 * [bottomPx] appends an end-of-document spacer `<div>` sized to the bottom pill / SafariBar so
 * ordinary in-flow content at the very bottom of the page (a footer link, a "forgot password"
 * button) gains enough scroll room to be brought clear of the pill and tapped. Unlike the top
 * inset this does NOT push content up: the pill stays a translucent overlay the page still scrolls
 * under; the spacer only lengthens the scrollable document by the pill's height. Pass 0 to skip it
 * (e.g. video fullscreen, where there is no bottom chrome).
 */
internal fun EngineSession.injectViewportSpacers(
    topPx: Int,
    bottomPx: Int = 0,
    skipHeaderInsets: Boolean = false,
) {
    loadJavascriptUrl(buildViewportSpacerScript(topPx, skipHeaderInsets))
    if (bottomPx > 0) loadJavascriptUrl(buildBottomSpacerScript(bottomPx))
}

/**
 * Builds the end-of-document bottom-spacer `javascript:` URL as a plain flattened string (JVM-unit
 * testable; pure). Appends a fixed-height spacer `<div>` as the last child of `<body>` and sets
 * `scroll-padding-bottom` so `scrollIntoView` for a bottom control also clears the pill. The spacer
 * self-heals: SPAs that rebuild `<body>` (or append their own trailing nodes) can drop or bury it,
 * so a bounded MutationObserver re-appends / re-floats it to the end. Use only `/* */` comments —
 * the script is flattened to one line, so a `//` would comment out the rest.
 */
internal fun buildBottomSpacerScript(bottomPx: Int): String {
    return """
        javascript:(()=> {
        const bottomPx=${bottomPx};
        const bottom=bottomPx+'px';
        const spacerId='lumina-bottom-scroll-spacer';
        const styleId='lumina-viewport-bottom-style';
        function ensureStyle(){let s=document.getElementById(styleId);if(!s){s=document.createElement('style');s.id=styleId;(document.head||document.documentElement).appendChild(s)}s.textContent='html,body{scroll-padding-bottom:'+bottom+'!important;}'}
        function opaque(c){if(!c)return false;c=c.replace(/\s+/g,'');if(c==='transparent')return false;var m=c.match(/^rgba?\(([^)]+)\)${'$'}/i);if(m){var p=m[1].split(',');if(p.length>=4&&parseFloat(p[3])===0)return false}return true}
        /* Match the strip to the page: sample the element the spacer sits below, walking up its
           ancestors to the first real (opaque) background, then fall back to the body / html base
           color. Makes the reserved band read as a seamless continuation of the page instead of a
           transparent gap under a colored footer. */
        function bgColor(el){for(var n=el?el.previousElementSibling:null;n;n=n.parentElement){try{var c=getComputedStyle(n).backgroundColor;if(opaque(c))return c}catch(e){}}try{var b=getComputedStyle(document.body).backgroundColor;if(opaque(b))return b}catch(e){}try{var h=getComputedStyle(document.documentElement).backgroundColor;if(opaque(h))return h}catch(e){}return ''}
        function ensureSpacer(){const host=document.body;if(!host)return;let el=document.getElementById(spacerId);if(!el){el=document.createElement('div');el.id=spacerId}el.setAttribute('aria-hidden','true');el.dataset.luminaIgnoreSafeTop='true';if(host.lastElementChild!==el){host.appendChild(el)}const bg=bgColor(el);el.style.cssText='height:'+bottom+'!important;min-height:'+bottom+'!important;width:100%!important;flex:0 0 auto!important;pointer-events:none!important;margin:0!important;padding:0!important;border:0!important;background:'+(bg||'transparent')+'!important;'}
        function apply(){ensureStyle();ensureSpacer()}
        apply();
        function schedule(){if(window.__luminaBottomPending)return;window.__luminaBottomPending=true;requestAnimationFrame(function(){window.__luminaBottomPending=false;apply()})}
        setTimeout(schedule,1000);setTimeout(schedule,3000);
        if(window.__luminaBottomObserver){window.__luminaBottomObserver.disconnect();window.__luminaBottomObserver=null}
        window.__luminaBottomObserver=new MutationObserver(function(records){const allOurs=records.every(function(r){return r.target&&r.target.id===spacerId});if(allOurs)return;schedule()});
        try{window.__luminaBottomObserver.observe(document.documentElement,{childList:true,subtree:true})}catch(e){}
        if(!window.__luminaBottomListenersBound){window.__luminaBottomListenersBound=true;window.addEventListener('load',function(){schedule()},{passive:true});var wrapHist=function(n){var o=history[n];if(typeof o==='function'&&!o.__luminaBottomWrapped){var w=function(){var r=o.apply(this,arguments);schedule();setTimeout(schedule,300);return r};w.__luminaBottomWrapped=true;try{history[n]=w}catch(e){}}};wrapHist('pushState');wrapHist('replaceState');window.addEventListener('popstate',function(){schedule();setTimeout(schedule,300)});}
        })()
    """.trimIndent().replace("\n", "")
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
        function restore(el){if(el.dataset.luminaSafeTopApplied!=='true')return;el.style.paddingTop=el.dataset.luminaOrigPad||'';el.style.height=el.dataset.luminaOrigH||'';el.style.minHeight=el.dataset.luminaOrigMh||'';el.style.top=el.dataset.luminaOrigTop||'';if(el.dataset.luminaOrigShadow!=null)el.style.boxShadow=el.dataset.luminaOrigShadow;if(el.dataset.luminaOrigTransform!=null)el.style.transform=el.dataset.luminaOrigTransform;el.dataset.luminaSafeTopApplied='false'}
        function bandShift(el){if(el.dataset.luminaOrigTransform==null){el.dataset.luminaOrigTransform=el.style.transform||''}var base=el.dataset.luminaOrigTransform;el.style.setProperty('transform',(base?base+' ':'')+'translateY('+topPx+'px)','important');el.dataset.luminaSafeTopApplied='true'}
        function depth(el){let d=0;for(let n=el;n&&n.parentElement;n=n.parentElement)d++;return d}
        function pinnedTop(el,cs,rect){if(cs.top!=='auto'&&px(cs.top)<=1)return true;return rect.top<=Math.max(1,topPx+1)}
        function isCandidate(el,cs,rect){if(el.id===topId||el.dataset.luminaIgnoreSafeTop==='true')return false;if(cs.display==='none'||cs.visibility==='hidden'||cs.opacity==='0')return false;if(cs.pointerEvents==='none'&&!el.querySelector('a,button,input,select,textarea,[role="button"],[role="link"],[tabindex],[onclick]'))return false;const pos=cs.position;if(pos!=='fixed'&&pos!=='sticky')return false;const maxH=Math.min(400,Math.max(240,window.innerHeight*0.45));if(rect.height<24||rect.height>maxH||rect.width<window.innerWidth*0.30)return false;const nearTop=rect.top<=topPx+80;const stickyReachesTop=pos==='sticky'&&cs.top!=='auto'&&px(cs.top)<=1&&rect.top<=window.innerHeight*1.5;if(rect.bottom<=0||(!nearTop&&!stickyReachesTop))return false;if(pos==='fixed'&&rect.top>=topPx-1)return false;return pinnedTop(el,cs,rect)}
        function topLevel(candidates){const chosen=[];candidates.sort((a,b)=>a.depth-b.depth||b.rect.width-a.rect.width);candidates.forEach(c=>{if(!chosen.some(p=>p.el!==c.el&&p.el.contains(c.el)))chosen.push(c)});return chosen}
        function applyFixed(el,cs,rect){if(el.dataset.luminaOrigPad==null){el.dataset.luminaOrigPad=el.style.paddingTop||'';el.dataset.luminaOrigH=el.style.height||'';el.dataset.luminaOrigMh=el.style.minHeight||'';el.dataset.luminaOrigTop=el.style.top||''}el.style.setProperty('padding-top',(px(cs.paddingTop)+topPx)+'px','important');el.style.setProperty('top',Math.max(0,px(cs.top))+'px','important');if(cs.boxSizing==='border-box'&&cs.height!=='auto'){const newH=rect.height+topPx;el.style.setProperty('height',newH+'px','important');el.style.setProperty('min-height',newH+'px','important');}el.dataset.luminaSafeTopApplied='true'}
        function applySticky(el,cs){if(el.dataset.luminaOrigPad==null){el.dataset.luminaOrigPad=el.style.paddingTop||'';el.dataset.luminaOrigH=el.style.height||'';el.dataset.luminaOrigMh=el.style.minHeight||'';el.dataset.luminaOrigTop=el.style.top||'';el.dataset.luminaOrigShadow=el.style.boxShadow||''}el.style.setProperty('top',(px(cs.top)+topPx)+'px','important');const bg=cs.backgroundColor;if(bg&&bg!=='transparent'&&bg!=='rgba(0, 0, 0, 0)'){const prev=(cs.boxShadow&&cs.boxShadow!=='none')?cs.boxShadow+',':'';el.style.setProperty('box-shadow',prev+'0 -'+topPx+'px 0 0 '+bg,'important')}el.dataset.luminaSafeTopApplied='true'}
        function applyOverlay(el,cs){if(el.dataset.luminaOrigPad==null){el.dataset.luminaOrigPad=el.style.paddingTop||'';el.dataset.luminaOrigH=el.style.height||'';el.dataset.luminaOrigMh=el.style.minHeight||'';el.dataset.luminaOrigTop=el.style.top||''}el.style.setProperty('padding-top',(px(cs.paddingTop)+topPx)+'px','important');el.dataset.luminaSafeTopApplied='true'}
        function overlayHasBandContent(el){var kids=el.querySelectorAll?el.querySelectorAll('*'):[];for(var i=0;i<kids.length&&i<4000;i++){var k=kids[i];if(k.id===topId||(k.dataset&&k.dataset.luminaIgnoreSafeTop==='true'))continue;var kcs=getComputedStyle(k);if(kcs.display==='none'||kcs.visibility==='hidden'||kcs.opacity==='0'||kcs.position==='fixed')continue;var kr=k.getBoundingClientRect();if(kr.width>=6&&kr.height>=4&&kr.top<topPx-1&&kr.bottom>1)return true}return false}
        function applyHeaderInsets(){if(!document.documentElement)return;if(topPx<=0){(window.__luminaInsetedEls||[]).forEach(restore);window.__luminaInsetedEls=[];return}if(document.hidden)return;const now=Date.now();const cd=window.__luminaCooldownMs||500;const since=now-(window.__luminaLastHeaderInsetMs||0);if(since<cd){if(!window.__luminaTrailingTimer){window.__luminaTrailingTimer=setTimeout(function(){window.__luminaTrailingTimer=0;applyHeaderInsets()},cd-since)}return;}if(window.__luminaTrailingTimer){clearTimeout(window.__luminaTrailingTimer);window.__luminaTrailingTimer=0}ensureStyle();spacer(topId,top);(window.__luminaInsetedEls||[]).forEach(restore);const all=collect(document,[]);const candidates=[];const intruders=[];const overlays=[];const headerCap=Math.min(400,Math.max(240,window.innerHeight*0.45));all.forEach(el=>{const cs=getComputedStyle(el);const pos=cs.position;if(pos!=='fixed'&&pos!=='sticky'&&pos!=='absolute')return;const rect=el.getBoundingClientRect();if(pos!=='absolute'){if(pos==='sticky'&&cs.top!=='auto'&&px(cs.top)<=1&&window.__luminaStickyIO){try{window.__luminaStickyIO.observe(el)}catch(e){}}if(isCandidate(el,cs,rect))candidates.push({el,cs,rect,depth:depth(el)})}if((pos==='fixed'||pos==='absolute')&&el.id!==topId&&el.dataset.luminaIgnoreSafeTop!=='true'&&cs.display!=='none'&&cs.visibility!=='hidden'&&cs.opacity!=='0'&&rect.width>=6&&rect.height>=4&&rect.height<=160&&rect.top<=topPx-2&&rect.bottom>1){intruders.push({el,rect})}if((pos==='fixed'||pos==='absolute')&&el.id!==topId&&el.dataset.luminaIgnoreSafeTop!=='true'&&cs.display!=='none'&&cs.visibility!=='hidden'&&cs.opacity!=='0'&&rect.top<=1&&rect.height>headerCap&&rect.width>=window.innerWidth*0.6&&rect.bottom>topPx){overlays.push({el,cs})}});const chosen=topLevel(candidates);const chosenKey=chosen.map(c=>c.el).join('|');window.__luminaStableCount=(chosenKey===window.__luminaLastChosenKey)?(window.__luminaStableCount||0)+1:0;window.__luminaCooldownMs=window.__luminaStableCount>=3?2000:500;window.__luminaApplyingHeaderInsets=true;window.__luminaInsetedEls=[];try{chosen.forEach(c=>{try{if(c.cs.position==='fixed')applyFixed(c.el,c.cs,c.rect);else applySticky(c.el,c.cs);window.__luminaInsetedEls.push(c.el)}catch(e){}});var chosenEls=chosen.map(function(c){return c.el});function isChosenEl(x){return chosenEls.some(function(h){return h===x||x.contains(h)})}var topIntruders=intruders.filter(function(it){if(chosenEls.some(function(h){return h===it.el||it.el.contains(h)}))return false;return !intruders.some(function(o){return o.el!==it.el&&o.el.contains(it.el)&&!isChosenEl(o.el)})});topIntruders.forEach(function(it){try{bandShift(it.el);window.__luminaInsetedEls.push(it.el)}catch(e){}});overlays.forEach(function(ov){try{if(chosenEls.some(function(h){return h===ov.el||h.contains(ov.el)}))return;if(!overlayHasBandContent(ov.el))return;applyOverlay(ov.el,ov.cs);window.__luminaInsetedEls.push(ov.el)}catch(e){}})}finally{window.__luminaLastChosenKey=chosenKey;window.__luminaLastHeaderInsetMs=Date.now();window.__luminaApplyingHeaderInsets=false;}}
        function schedule(){if(window.__luminaHeaderInsetPending)return;window.__luminaHeaderInsetPending=true;requestAnimationFrame(function(){window.__luminaHeaderInsetPending=false;applyHeaderInsets()})}
        spacer(topId,top);
        if(window.__luminaHeaderInsetObserver){window.__luminaHeaderInsetObserver.disconnect();window.__luminaHeaderInsetObserver=null}
        if(!skipHeader){if(!window.__luminaStickyIO&&typeof IntersectionObserver==='function'){window.__luminaStickyIO=new IntersectionObserver(function(){schedule()},{threshold:0})}/* Run the first pass synchronously (not via rAF schedule) so the header inset lands in the SAME layout pass as the body spacer above. Deferring it one frame makes a fixed/sticky header visibly jump down after content has already shifted. The cooldown guard at the top of applyHeaderInsets makes this a no-op if a recent pass already ran (rapid re-inject); every SUBSEQUENT pass still goes through the rAF-debounced schedule(). */applyHeaderInsets();setTimeout(schedule,1000);setTimeout(schedule,3000);setTimeout(schedule,10000);window.__luminaHeaderInsetObserver=new MutationObserver(function(records){if(window.__luminaApplyingHeaderInsets)return;const allOurs=records.every(function(r){return r.target.dataset&&(r.target.dataset.luminaSafeTopApplied!=null||r.target.dataset.luminaIgnoreSafeTop==='true')});if(allOurs)return;schedule()});window.__luminaHeaderInsetObserver.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['class','style','slot','hidden','aria-hidden']});if(!window.__luminaListenersBound){window.__luminaListenersBound=true;window.addEventListener('resize',function(){schedule()},{passive:true});window.addEventListener('load',function(){schedule()},{passive:true});if(document.fonts&&document.fonts.ready){document.fonts.ready.then(function(){schedule()}).catch(function(){})}document.addEventListener('visibilitychange',function(){if(!document.hidden)schedule()});var wrapHist=function(n){var o=history[n];if(typeof o==='function'&&!o.__luminaWrapped){var w=function(){var r=o.apply(this,arguments);schedule();setTimeout(schedule,300);return r};w.__luminaWrapped=true;try{history[n]=w}catch(e){}}};wrapHist('pushState');wrapHist('replaceState');window.addEventListener('popstate',function(){schedule();setTimeout(schedule,300)});}}
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
