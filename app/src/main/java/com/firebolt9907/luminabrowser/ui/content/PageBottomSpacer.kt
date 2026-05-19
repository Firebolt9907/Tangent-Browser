package com.firebolt9907.luminabrowser.ui.content

import java.util.Locale
import mozilla.components.concept.engine.EngineSession

/**
 * Inject top/bottom spacer `<div>`s and a `scroll-padding` style so page content sits
 * inside the status-bar and SafariBar safe areas. When `skipHeaderInsets = false`, also
 * walks top-level `position: fixed/sticky` headers pinned near the top and moves their
 * visible content into the safe area, re-running on a MutationObserver. Pass `true`
 * when the EngineView itself is padded (per-site top-padding-fix) to avoid a restyle loop.
 */
internal fun EngineSession.injectViewportSpacers(
    topPx: Int,
    bottomPx: Int,
    skipHeaderInsets: Boolean = false,
) {
    val script = """
        javascript:(()=> {
        const topPx=${topPx};
        const bottomPx=${bottomPx};
        const skipHeader=${if (skipHeaderInsets) "true" else "false"};
        const top=topPx+'px';
        const bottom=bottomPx+'px';
        const topId='lumina-top-scroll-spacer';
        const bottomId='lumina-bottom-scroll-spacer';
        const styleId='lumina-viewport-scroll-style';
        let style=document.getElementById(styleId);
        if(!style){style=document.createElement('style');style.id=styleId;(document.head||document.documentElement).appendChild(style)}
        style.textContent='html{scroll-padding-top:'+top+'!important;scroll-padding-bottom:'+bottom+'!important;}';
        function spacer(id,h,prepend){let el=document.getElementById(id);if(!el){el=document.createElement('div');el.id=id;if(prepend){(document.body||document.documentElement).prepend(el)}else{(document.body||document.documentElement).appendChild(el)}}el.setAttribute('aria-hidden','true');el.dataset.luminaIgnoreSafeTop='true';el.style.cssText='height:'+h+'!important;min-height:'+h+'!important;display:block!important;pointer-events:none!important;visibility:hidden!important;'}
        function px(value){const n=parseFloat(value);return Number.isFinite(n)?n:0}
        function collect(root,out){if(!root)return out;const all=root.querySelectorAll?root.querySelectorAll('*'):[];all.forEach(el=>{out.push(el);if(el.shadowRoot)collect(el.shadowRoot,out)});return out}
        function restore(el){if(el.dataset.luminaSafeTopApplied!=='true')return;el.style.paddingTop=el.dataset.luminaOrigPad||'';el.style.height=el.dataset.luminaOrigH||'';el.style.minHeight=el.dataset.luminaOrigMh||'';el.style.top=el.dataset.luminaOrigTop||'';el.dataset.luminaSafeTopApplied='false'}
        function depth(el){let d=0;for(let n=el;n&&n.parentElement;n=n.parentElement)d++;return d}
        function pinnedTop(el,cs,rect){if(cs.top!=='auto'&&px(cs.top)<=1)return true;return rect.top<=Math.max(1,topPx+1)}
        function isCandidate(el,cs,rect){if(el.id===topId||el.id===bottomId||el.dataset.luminaIgnoreSafeTop==='true')return false;if(cs.display==='none'||cs.visibility==='hidden'||cs.opacity==='0'||cs.pointerEvents==='none')return false;const pos=cs.position;if(pos!=='fixed'&&pos!=='sticky')return false;if(rect.height<24||rect.height>240||rect.width<window.innerWidth*0.30)return false;if(rect.bottom<=0||rect.top>topPx+80)return false;return pinnedTop(el,cs,rect)}
        function topLevel(candidates){const chosen=[];candidates.sort((a,b)=>a.depth-b.depth||b.rect.width-a.rect.width);candidates.forEach(c=>{if(!chosen.some(p=>p.el!==c.el&&p.el.contains(c.el)))chosen.push(c)});return chosen}
        function applyFixed(el,cs,rect){if(el.dataset.luminaOrigPad==null){el.dataset.luminaOrigPad=el.style.paddingTop||'';el.dataset.luminaOrigH=el.style.height||'';el.dataset.luminaOrigMh=el.style.minHeight||'';el.dataset.luminaOrigTop=el.style.top||''}el.style.setProperty('padding-top',(px(cs.paddingTop)+topPx)+'px','important');el.style.setProperty('top',Math.max(0,px(cs.top))+'px','important');if(cs.boxSizing==='border-box'&&cs.height!=='auto'){const newH=rect.height+topPx;el.style.setProperty('height',newH+'px','important');el.style.setProperty('min-height',newH+'px','important');}el.dataset.luminaSafeTopApplied='true'}
        function applySticky(el,cs){if(el.dataset.luminaOrigPad==null){el.dataset.luminaOrigPad=el.style.paddingTop||'';el.dataset.luminaOrigH=el.style.height||'';el.dataset.luminaOrigMh=el.style.minHeight||'';el.dataset.luminaOrigTop=el.style.top||''}el.style.setProperty('top',(px(cs.top)+topPx)+'px','important');el.dataset.luminaSafeTopApplied='true'}
        function applyHeaderInsets(){if(topPx<=0||!document.documentElement)return;const now=Date.now();if(now-(window.__luminaLastHeaderInsetMs||0)<500){return;}(window.__luminaInsetedEls||[]).forEach(restore);const all=collect(document,[]);const candidates=[];all.forEach(el=>{const cs=getComputedStyle(el);const pos=cs.position;if(pos!=='fixed'&&pos!=='sticky')return;const rect=el.getBoundingClientRect();if(isCandidate(el,cs,rect))candidates.push({el,cs,rect,depth:depth(el)})});const chosen=topLevel(candidates);const chosenKey=chosen.map(c=>c.el).join('|');window.__luminaApplyingHeaderInsets=true;window.__luminaInsetedEls=[];chosen.forEach(c=>{if(c.cs.position==='fixed')applyFixed(c.el,c.cs,c.rect);else applySticky(c.el,c.cs);window.__luminaInsetedEls.push(c.el)});window.__luminaLastChosenKey=chosenKey;window.__luminaLastHeaderInsetMs=Date.now();window.__luminaApplyingHeaderInsets=false;}
        function schedule(){cancelAnimationFrame(window.__luminaHeaderInsetFrame||0);window.__luminaHeaderInsetFrame=requestAnimationFrame(applyHeaderInsets)}
        spacer(topId,top,true);spacer(bottomId,bottom,false);
        if(window.__luminaHeaderInsetObserver){window.__luminaHeaderInsetObserver.disconnect();window.__luminaHeaderInsetObserver=null}
        if(!skipHeader){schedule();setTimeout(schedule,1000);setTimeout(schedule,3000);setTimeout(schedule,10000);window.__luminaHeaderInsetObserver=new MutationObserver(function(records){if(window.__luminaApplyingHeaderInsets)return;const allOurs=records.every(function(r){return r.target.dataset&&r.target.dataset.luminaSafeTopApplied!=null});if(allOurs)return;schedule()});window.__luminaHeaderInsetObserver.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['class','style','slot']});window.addEventListener('resize',schedule,{passive:true});window.addEventListener('scroll',schedule,{passive:true});}
        })()
    """.trimIndent().replace("\n", "")
    loadUrl(
        url = script,
        flags = EngineSession.LoadUrlFlags.select(
            EngineSession.LoadUrlFlags.ALLOW_JAVASCRIPT_URL,
            EngineSession.LoadUrlFlags.LOAD_FLAGS_REPLACE_HISTORY,
        ),
    )
}

/** Set `document.documentElement.style.zoom = factor` (empty string when `factor == 1.0`). */
internal fun EngineSession.injectPageZoom(factor: Float) {
    val literal = if (factor == 1.0f) "''" else "'${"%.4f".format(Locale.US, factor)}'"
    val script = """
        javascript:(()=>{try{if(document.documentElement){document.documentElement.style.zoom=${literal};}}catch(e){}})()
    """.trimIndent().replace("\n", "")
    loadUrl(
        url = script,
        flags = EngineSession.LoadUrlFlags.select(
            EngineSession.LoadUrlFlags.ALLOW_JAVASCRIPT_URL,
            EngineSession.LoadUrlFlags.LOAD_FLAGS_REPLACE_HISTORY,
        ),
    )
}
