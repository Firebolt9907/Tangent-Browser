package com.firebolt9907.tangentbrowser.ui.content

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
        const spacerId='tangent-bottom-scroll-spacer';
        const styleId='tangent-viewport-bottom-style';
        function ensureStyle(){let s=document.getElementById(styleId);if(!s){s=document.createElement('style');s.id=styleId;(document.head||document.documentElement).appendChild(s)}s.textContent='html,body{scroll-padding-bottom:'+bottom+'!important;}'}
        function opaque(c){if(!c)return false;c=c.replace(/\s+/g,'');if(c==='transparent')return false;var m=c.match(/^rgba?\(([^)]+)\)${'$'}/i);if(m){var p=m[1].split(',');if(p.length>=4&&parseFloat(p[3])===0)return false}return true}
        /* Match the strip to the page: sample the element the spacer sits below, walking up its
           ancestors to the first real (opaque) background, then fall back to the body / html base
           color. Makes the reserved band read as a seamless continuation of the page instead of a
           transparent gap under a colored footer. */
        function bgColor(el){for(var n=el?el.previousElementSibling:null;n;n=n.parentElement){try{var c=getComputedStyle(n).backgroundColor;if(opaque(c))return c}catch(e){}}try{var b=getComputedStyle(document.body).backgroundColor;if(opaque(b))return b}catch(e){}try{var h=getComputedStyle(document.documentElement).backgroundColor;if(opaque(h))return h}catch(e){}return ''}
        function ensureSpacer(){const host=document.body;if(!host)return;let el=document.getElementById(spacerId);if(!el){el=document.createElement('div');el.id=spacerId}el.setAttribute('aria-hidden','true');el.dataset.tangentIgnoreSafeTop='true';if(host.lastElementChild!==el){host.appendChild(el)}const bg=bgColor(el);el.style.cssText='height:'+bottom+'!important;min-height:'+bottom+'!important;width:100%!important;flex:0 0 auto!important;pointer-events:none!important;margin:0!important;padding:0!important;border:0!important;background:'+(bg||'transparent')+'!important;'}
        function apply(){ensureStyle();ensureSpacer()}
        apply();
        function schedule(){if(window.__tangentBottomPending)return;window.__tangentBottomPending=true;requestAnimationFrame(function(){window.__tangentBottomPending=false;apply()})}
        setTimeout(schedule,1000);setTimeout(schedule,3000);
        if(window.__tangentBottomObserver){window.__tangentBottomObserver.disconnect();window.__tangentBottomObserver=null}
        window.__tangentBottomObserver=new MutationObserver(function(records){const allOurs=records.every(function(r){return r.target&&r.target.id===spacerId});if(allOurs)return;schedule()});
        try{window.__tangentBottomObserver.observe(document.documentElement,{childList:true,subtree:true})}catch(e){}
        if(!window.__tangentBottomListenersBound){window.__tangentBottomListenersBound=true;window.addEventListener('load',function(){schedule()},{passive:true});var wrapHist=function(n){var o=history[n];if(typeof o==='function'&&!o.__tangentBottomWrapped){var w=function(){var r=o.apply(this,arguments);schedule();setTimeout(schedule,300);return r};w.__tangentBottomWrapped=true;try{history[n]=w}catch(e){}}};wrapHist('pushState');wrapHist('replaceState');window.addEventListener('popstate',function(){schedule();setTimeout(schedule,300)});}
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
        const topId='tangent-top-scroll-spacer';
        const styleId='tangent-viewport-scroll-style';
        function setSPT(v){let s=document.getElementById(styleId);if(!s){s=document.createElement('style');s.id=styleId;(document.head||document.documentElement).appendChild(s)}var vs=v+'px';s.textContent='html,body{scroll-padding-top:'+vs+'!important;}';try{if(document.documentElement)document.documentElement.style.setProperty('scroll-padding-top',vs,'important')}catch(e){}try{if(document.body)document.body.style.setProperty('scroll-padding-top',vs,'important')}catch(e){}}
        function ensureStyle(){let s=document.getElementById(styleId);if(!s){s=document.createElement('style');s.id=styleId;(document.head||document.documentElement).appendChild(s)}var de=document.documentElement;var base=0;try{de.style.removeProperty('scroll-padding-top');s.textContent='';base=px(getComputedStyle(de).scrollPaddingTop)}catch(e){}var floor=(base>0?base:0)+topPx;window.__tangentSptFloor=floor;var eff=Math.max(floor,window.__tangentSptApplied||0);var effS=eff+'px';window.__tangentSptEffPx=eff;s.textContent='html,body{scroll-padding-top:'+effS+'!important;}';try{if(de)de.style.setProperty('scroll-padding-top',effS,'important')}catch(e){}try{if(document.body)document.body.style.setProperty('scroll-padding-top',effS,'important')}catch(e){}}
        ensureStyle();
        function px(value){const n=parseFloat(value);return Number.isFinite(n)?n:0}
        /* Fill the reserved top strip with the color painted just BELOW it (the header / first content
           the spacer pushed down), Safari-style, so a colored nav bar reads as continuous up to the
           screen top instead of leaving a transparent gap above it. Sample the element at the strip's
           bottom edge and walk up to its first opaque background, falling back to body / html base.
           Fixed/sticky headers already fill the strip via applyFixed padding / applySticky box-shadow;
           this covers the in-flow (static) header case the spacer merely pushes down. */
        function topOpaque(c){if(!c)return false;c=c.replace(/\s+/g,'');if(c==='transparent')return false;var m=c.match(/^rgba?\(([^)]+)\)${'$'}/i);if(m){var p=m[1].split(',');if(p.length>=4&&parseFloat(p[3])===0)return false}return true}
        /* Read one element's paintable background: a gradient image (the common branded-header case,
           whose backgroundColor is often transparent) OR a solid opaque color. Returns null when the
           element paints nothing, so the walk continues to the next ancestor / the page base. Raster
           background-images are intentionally NOT carried up (a photo slice above the header looks
           worse than matching the base color). */
        function topPick(n){try{var cs=getComputedStyle(n);var img=cs.backgroundImage;var col=cs.backgroundColor;if(img&&img!=='none'&&/gradient/i.test(img))return {img:img,c:topOpaque(col)?col:''};if(topOpaque(col))return {c:col}}catch(e){}return null}
        function topBg(sp){try{var x=Math.floor(window.innerWidth/2);var probe=document.elementFromPoint(x,topPx+2);for(var n=probe;n&&n!==document.documentElement;n=n.parentElement){if(n===sp||n.id===topId||(n.dataset&&n.dataset.tangentIgnoreSafeTop==='true'))continue;var r=topPick(n);if(r)return r}}catch(e){}var rb=topPick(document.body);if(rb)return rb;var rh=topPick(document.documentElement);if(rh)return rh;return null}
        /* A viewport-height-locked app shell (height:100vh / 100dvh / 100% of a locked body) does NOT
           shrink when the in-flow spacer pushes it down: its bottom topPx pixels leave the screen,
           taking the bottom nav / player / composer with them. In a scroll-locked document
           (html or body overflow:hidden — the web-app-shell norm) that strip is unreachable forever.
           Detect that shape and pad the SHELL itself instead (border-box, so its outer height stays
           the viewport height): the shell's own content is inset below the status bar and nothing
           falls off the bottom. */
        function vpShell(host){try{if(topPx<=0)return null;var hs=getComputedStyle(document.documentElement);var bs=getComputedStyle(host);if(hs.overflowY!=='hidden'&&bs.overflowY!=='hidden')return null;var vh=window.innerHeight;var q=[],i;for(i=0;i<host.children.length&&i<20;i++)q.push(host.children[i]);var guard=0;while(q.length&&guard<60){var k=q.shift();guard++;if(!k||k.nodeType!==1)continue;if(k.id===topId||(k.dataset&&k.dataset.tangentIgnoreSafeTop==='true'))continue;var kcs=getComputedStyle(k);/* a display:contents wrapper has no box of its own (telegram's #auth-flow-root) — padding it would do nothing, so look through it at the real shell inside */if(kcs.display==='contents'){for(i=0;i<k.children.length&&i<20;i++)q.push(k.children[i]);continue}if(kcs.display==='none'||kcs.visibility==='hidden'||kcs.position==='fixed'||kcs.position==='absolute')continue;var r=k.getBoundingClientRect();if(r.width<window.innerWidth*0.8||r.top>topPx+2)continue;var inner=r.height-px(kcs.paddingTop)-px(kcs.paddingBottom)-px(kcs.borderTopWidth)-px(kcs.borderBottomWidth);if(Math.abs(r.height-vh)<=4||Math.abs(inner-vh)<=4)return k}}catch(e){}return null}
        function padShell(k){if(k.dataset.tangentShellPadSaved!=='true'){var kcs=getComputedStyle(k);k.dataset.tangentShellOrigPad=k.style.paddingTop||'';k.dataset.tangentShellOrigBox=k.style.boxSizing||'';k.dataset.tangentShellBasePad=px(kcs.paddingTop);k.dataset.tangentShellPadSaved='true'}/* if the shell is itself the page's scroll container (html/body are locked, so root scroll-padding-top never applies to it) it needs its own scroll-padding-top, or in-page anchor jumps land under the status bar */var scs=getComputedStyle(k);if(scs.overflowY==='auto'||scs.overflowY==='scroll'){if(k.dataset.tangentShellOrigSpt==null)k.dataset.tangentShellOrigSpt=k.style.scrollPaddingTop||'';k.style.setProperty('scroll-padding-top',(window.__tangentSptEffPx||topPx)+'px','important')}k.style.setProperty('box-sizing','border-box','important');var tgt=(px(k.dataset.tangentShellBasePad)+topPx)+'px';if(k.__tangentShellPad!==tgt){k.style.setProperty('padding-top',tgt,'important');k.__tangentShellPad=tgt}}
        /* Padding the outer shell fixes the shell, but a descendant that is ITSELF viewport-locked
           (`height:100vh` — vh always means the FULL viewport, nothing in the page can redefine it)
           is still 100vh tall inside the shell's now-shorter content box, so its bottom topPx
           pixels hang off the screen again (web.telegram.org nests two such layers). Cap those to
           the remaining height. Walk only the wide/tall spine from the shell, bounded, so this
           stays cheap on deep app DOMs. */
        function capEl(k){if(k.dataset.tangentShellCapSaved!=='true'){k.dataset.tangentShellOrigMaxH=k.style.maxHeight||'';k.dataset.tangentShellCapSaved='true';(window.__tangentShellCapped=window.__tangentShellCapped||[]).push(k)}k.style.setProperty('max-height','calc(100vh - '+topPx+'px)','important');k.style.setProperty('box-sizing','border-box','important')}
        function capNested(root){try{var vh=window.innerHeight;var stack=[root];var guard=0;while(stack.length&&guard<400){var el=stack.pop();var ch=el.children;for(var i=0;i<ch.length&&i<20&&guard<400;i++){var k=ch[i];guard++;if(k.nodeType!==1)continue;if(k.id===topId||(k.dataset&&k.dataset.tangentIgnoreSafeTop==='true'))continue;var kcs=getComputedStyle(k);if(kcs.display==='none'||kcs.visibility==='hidden'||kcs.position==='fixed')continue;var r=k.getBoundingClientRect();if(r.width<window.innerWidth*0.8||r.height<vh-topPx-4)continue;if(Math.abs(r.height-vh)<=4)capEl(k);stack.push(k)}}}catch(e){}}
        function uncapNested(){var list=window.__tangentShellCapped||[];list.forEach(function(k){try{if(k.dataset.tangentShellCapSaved!=='true')return;k.style.maxHeight=k.dataset.tangentShellOrigMaxH||'';k.dataset.tangentShellCapSaved='false'}catch(e){}});window.__tangentShellCapped=[]}
        function unpadShell(k){uncapNested();if(!k||!k.dataset||k.dataset.tangentShellPadSaved!=='true')return;k.style.paddingTop=k.dataset.tangentShellOrigPad||'';k.style.boxSizing=k.dataset.tangentShellOrigBox||'';if(k.dataset.tangentShellOrigSpt!=null){k.style.scrollPaddingTop=k.dataset.tangentShellOrigSpt;delete k.dataset.tangentShellOrigSpt}k.dataset.tangentShellPadSaved='false';k.__tangentShellPad=null}
        function spacer(id,h){const host=document.body||document.documentElement;let el=document.getElementById(id);if(!el){el=document.createElement('div');el.id=id;host.prepend(el)}else if(el.parentElement!==host){host.prepend(el)}el.setAttribute('aria-hidden','true');el.dataset.tangentIgnoreSafeTop='true';const hcs=getComputedStyle(host);const shell=vpShell(host);if(window.__tangentShellEl&&window.__tangentShellEl!==shell){unpadShell(window.__tangentShellEl);window.__tangentShellEl=null}if(shell){if(host.dataset.tangentHostPadSaved==='true'){host.style.paddingTop=host.dataset.tangentHostOrigPad||'';host.dataset.tangentHostPadSaved='false';host.__tangentHostPad=null}padShell(shell);capNested(shell);window.__tangentShellEl=shell;el.style.cssText='display:none!important;';return}const hdisp=hcs.display;const flexRow=(hdisp==='flex'||hdisp==='inline-flex')&&((hcs.flexDirection||'row').indexOf('row')===0)&&(hcs.flexWrap||'nowrap')==='nowrap';const isGrid=(hdisp==='grid'||hdisp==='inline-grid');if(flexRow||isGrid){if(host.dataset.tangentHostPadSaved!=='true'){host.dataset.tangentHostOrigPad=host.style.paddingTop||'';host.dataset.tangentHostBasePad=px(hcs.paddingTop);host.dataset.tangentHostPadSaved='true'}const tgt=(px(host.dataset.tangentHostBasePad)+topPx)+'px';if(host.__tangentHostPad!==tgt){host.style.setProperty('padding-top',tgt,'important');host.__tangentHostPad=tgt}el.style.cssText='display:none!important;';return}if(host.dataset.tangentHostPadSaved==='true'){host.style.paddingTop=host.dataset.tangentHostOrigPad||'';host.dataset.tangentHostPadSaved='false';host.__tangentHostPad=null}el.style.cssText='height:'+h+'!important;min-height:'+h+'!important;display:block!important;width:100%!important;flex:0 0 auto!important;grid-column:1 / -1!important;order:-2147483647!important;pointer-events:none!important;visibility:hidden!important;';var __sbg=topBg(el);if(__sbg){el.style.setProperty('visibility','visible','important');el.style.setProperty('background-color',__sbg.c||'transparent','important');el.style.setProperty('background-image',__sbg.img||'none','important')}else{el.style.setProperty('visibility','hidden','important');el.style.setProperty('background','transparent','important')}const parent=el.parentElement;if(parent){const pcs=getComputedStyle(parent);const disp=pcs.display;const isCol=(disp==='flex'||disp==='inline-flex')?((pcs.flexDirection||'row').indexOf('column')===0):(disp==='grid'||disp==='inline-grid');const gap=isCol?(px(pcs.rowGap)||px(pcs.gap)):0;if(gap>0){el.style.setProperty('margin-bottom',(-gap)+'px','important')}else{el.style.removeProperty('margin-bottom')}}}
        function collect(root,out){if(!root)return out;const all=root.querySelectorAll?root.querySelectorAll('*'):[];all.forEach(el=>{out.push(el);if(el.shadowRoot)collect(el.shadowRoot,out)});return out}
        function restore(el){if(el.dataset.tangentSafeTopApplied!=='true')return;el.style.paddingTop=el.dataset.tangentOrigPad||'';el.style.height=el.dataset.tangentOrigH||'';el.style.minHeight=el.dataset.tangentOrigMh||'';el.style.top=el.dataset.tangentOrigTop||'';if(el.dataset.tangentOrigShadow!=null)el.style.boxShadow=el.dataset.tangentOrigShadow;if(el.dataset.tangentOrigTransform!=null)el.style.transform=el.dataset.tangentOrigTransform;el.dataset.tangentSafeTopApplied='false';el.dataset.tangentCoverPadded='false'}
        function bandShift(el){if(el.dataset.tangentOrigTransform==null){el.dataset.tangentOrigTransform=el.style.transform||''}var base=el.dataset.tangentOrigTransform;el.style.setProperty('transform',(base?base+' ':'')+'translateY('+topPx+'px)','important');el.dataset.tangentSafeTopApplied='true'}
        function depth(el){let d=0;for(let n=el;n&&n.parentElement;n=n.parentElement)d++;return d}
        function pinnedTop(el,cs,rect){if(cs.top!=='auto'&&px(cs.top)<=1)return true;return rect.top<=Math.max(1,topPx+1)}
        function isCandidate(el,cs,rect){if(el.id===topId||el.dataset.tangentIgnoreSafeTop==='true')return false;if(cs.display==='none'||cs.visibility==='hidden'||cs.opacity==='0')return false;if(cs.pointerEvents==='none'&&!el.querySelector('a,button,input,select,textarea,[role="button"],[role="link"],[tabindex],[onclick]'))return false;const pos=cs.position;if(pos!=='fixed'&&pos!=='sticky')return false;const maxH=Math.min(400,Math.max(240,window.innerHeight*0.45));if(rect.height<24||rect.height>maxH||rect.width<window.innerWidth*0.30)return false;const nearTop=rect.top<=topPx+80;const stickyReachesTop=pos==='sticky'&&cs.top!=='auto'&&px(cs.top)<=1&&rect.top<=window.innerHeight*1.5;if(rect.bottom<=0||(!nearTop&&!stickyReachesTop))return false;if(pos==='fixed'&&rect.top>=topPx-1)return false;return pinnedTop(el,cs,rect)}
        function topLevel(candidates){const chosen=[];candidates.sort((a,b)=>a.depth-b.depth||b.rect.width-a.rect.width);candidates.forEach(c=>{if(!chosen.some(p=>p.el!==c.el&&p.el.contains(c.el)))chosen.push(c)});return chosen}
        /* A header whose visible bar is a position:absolute inset:0 child (Apple's #globalnav >
           .globalnav-content, holding the logo + icons) does not move that content when we pad the
           header: padding stretches the child's box so its background still fills the reserved strip,
           but the content is laid out from the child's own top and stays under the status bar.
           Mirror the header's padding onto any such full-cover absolute child so its content shifts
           into the safe area while its background keeps covering the strip. Mark each padded child so
           the intruder pass below does NOT also bandShift it (padding + translate would double the
           offset, dragging the translucent bar down over the page — the "apple.com bleed"). Direct
           children only (no recursion) so nested cover layers don't compound. */
        function padCover(el){try{var ch=el.children;for(var i=0;i<ch.length&&i<40;i++){var k=ch[i];if(k.id===topId||(k.dataset&&(k.dataset.tangentIgnoreSafeTop==='true'||k.dataset.tangentCoverPadded==='true')))continue;var kcs=getComputedStyle(k);if((kcs.position==='absolute'||kcs.position==='fixed')&&kcs.top==='0px'&&kcs.bottom==='0px'&&kcs.height!=='auto'){if(k.dataset.tangentOrigPad==null){k.dataset.tangentOrigPad=k.style.paddingTop||''}k.style.setProperty('padding-top',(px(kcs.paddingTop)+topPx)+'px','important');k.dataset.tangentCoverPadded='true';k.dataset.tangentSafeTopApplied='true';if(window.__tangentInsetedEls)window.__tangentInsetedEls.push(k)}}}catch(e){}}
        function applyFixed(el,cs,rect){if(el.dataset.tangentOrigPad==null){el.dataset.tangentOrigPad=el.style.paddingTop||'';el.dataset.tangentOrigH=el.style.height||'';el.dataset.tangentOrigMh=el.style.minHeight||'';el.dataset.tangentOrigTop=el.style.top||''}el.style.setProperty('padding-top',(px(cs.paddingTop)+topPx)+'px','important');el.style.setProperty('top',Math.max(0,px(cs.top))+'px','important');if(cs.boxSizing==='border-box'&&cs.height!=='auto'){const newH=rect.height+topPx;el.style.setProperty('height',newH+'px','important');el.style.setProperty('min-height',newH+'px','important');}padCover(el);el.dataset.tangentSafeTopApplied='true'}
        function applySticky(el,cs){if(el.dataset.tangentOrigPad==null){el.dataset.tangentOrigPad=el.style.paddingTop||'';el.dataset.tangentOrigH=el.style.height||'';el.dataset.tangentOrigMh=el.style.minHeight||'';el.dataset.tangentOrigTop=el.style.top||'';el.dataset.tangentOrigShadow=el.style.boxShadow||''}el.style.setProperty('top',(px(cs.top)+topPx)+'px','important');const bg=cs.backgroundColor;if(bg&&bg!=='transparent'&&bg!=='rgba(0, 0, 0, 0)'){const prev=(cs.boxShadow&&cs.boxShadow!=='none')?cs.boxShadow+',':'';el.style.setProperty('box-shadow',prev+'0 -'+topPx+'px 0 0 '+bg,'important')}el.dataset.tangentSafeTopApplied='true'}
        function applyOverlay(el,cs){if(el.dataset.tangentOrigPad==null){el.dataset.tangentOrigPad=el.style.paddingTop||'';el.dataset.tangentOrigH=el.style.height||'';el.dataset.tangentOrigMh=el.style.minHeight||'';el.dataset.tangentOrigTop=el.style.top||''}el.style.setProperty('padding-top',(px(cs.paddingTop)+topPx)+'px','important');padCover(el);el.dataset.tangentSafeTopApplied='true'}
        function overlayHasBandContent(el){var kids=el.querySelectorAll?el.querySelectorAll('*'):[];for(var i=0;i<kids.length&&i<4000;i++){var k=kids[i];if(k.id===topId||(k.dataset&&k.dataset.tangentIgnoreSafeTop==='true'))continue;var kcs=getComputedStyle(k);if(kcs.display==='none'||kcs.visibility==='hidden'||kcs.opacity==='0'||kcs.position==='fixed')continue;var kr=k.getBoundingClientRect();if(kr.width>=6&&kr.height>=4&&kr.top<topPx-1&&kr.bottom>1)return true}return false}
        function applyHeaderInsets(){if(!document.documentElement)return;if(topPx<=0){(window.__tangentInsetedEls||[]).forEach(restore);window.__tangentInsetedEls=[];return}if(document.hidden)return;const now=Date.now();const cd=window.__tangentCooldownMs||500;const since=now-(window.__tangentLastHeaderInsetMs||0);if(since<cd){if(!window.__tangentTrailingTimer){window.__tangentTrailingTimer=setTimeout(function(){window.__tangentTrailingTimer=0;applyHeaderInsets()},cd-since)}return;}if(window.__tangentTrailingTimer){clearTimeout(window.__tangentTrailingTimer);window.__tangentTrailingTimer=0}ensureStyle();spacer(topId,top);(window.__tangentInsetedEls||[]).forEach(restore);const all=collect(document,[]);const candidates=[];const intruders=[];const overlays=[];const topBars=[];const headerCap=Math.min(400,Math.max(240,window.innerHeight*0.45));all.forEach(el=>{const cs=getComputedStyle(el);const pos=cs.position;if(pos!=='fixed'&&pos!=='sticky'&&pos!=='absolute')return;const rect=el.getBoundingClientRect();if((pos==='fixed'||pos==='sticky')&&el.id!==topId&&el.dataset.tangentIgnoreSafeTop!=='true'&&cs.display!=='none'&&cs.visibility!=='hidden'&&cs.opacity!=='0'&&rect.width>=window.innerWidth*0.6&&rect.height<=headerCap&&rect.top<=topPx+2&&rect.bottom>topPx){topBars.push(el)}if(pos!=='absolute'){if(pos==='sticky'&&cs.top!=='auto'&&px(cs.top)<=1&&window.__tangentStickyIO){try{window.__tangentStickyIO.observe(el)}catch(e){}}if(isCandidate(el,cs,rect))candidates.push({el,cs,rect,depth:depth(el)})}if((pos==='fixed'||pos==='absolute')&&el.id!==topId&&el.dataset.tangentIgnoreSafeTop!=='true'&&cs.display!=='none'&&cs.visibility!=='hidden'&&cs.opacity!=='0'&&rect.width>=6&&rect.height>=4&&rect.height<=160&&rect.top<=topPx-2&&rect.bottom>1){intruders.push({el,rect})}if((pos==='fixed'||pos==='absolute')&&el.id!==topId&&el.dataset.tangentIgnoreSafeTop!=='true'&&cs.display!=='none'&&cs.visibility!=='hidden'&&cs.opacity!=='0'&&rect.top<=1&&rect.height>headerCap&&rect.width>=window.innerWidth*0.6&&rect.bottom>topPx){overlays.push({el,cs})}});const chosen=topLevel(candidates);const chosenKey=chosen.map(c=>c.el).join('|');window.__tangentStableCount=(chosenKey===window.__tangentLastChosenKey)?(window.__tangentStableCount||0)+1:0;window.__tangentCooldownMs=window.__tangentStableCount>=3?2000:500;window.__tangentApplyingHeaderInsets=true;window.__tangentInsetedEls=[];var sptWant=window.__tangentSptFloor||topPx;var sptCap=topPx+window.innerHeight*0.5;function sptMeas(el,pos){try{var _r=el.getBoundingClientRect();if((pos==='fixed'||pos==='sticky')&&_r.top<=topPx+2&&_r.width>=window.innerWidth*0.6&&_r.bottom>sptWant&&_r.bottom<=sptCap)sptWant=_r.bottom}catch(e){}}try{chosen.forEach(c=>{try{if(c.cs.position==='fixed')applyFixed(c.el,c.cs,c.rect);else applySticky(c.el,c.cs);window.__tangentInsetedEls.push(c.el);sptMeas(c.el,c.cs.position)}catch(e){}});var chosenEls=chosen.map(function(c){return c.el});function isChosenEl(x){return chosenEls.some(function(h){return h===x||x.contains(h)})}var topIntruders=intruders.filter(function(it){if(it.el.dataset&&it.el.dataset.tangentCoverPadded==='true')return false;if(chosenEls.some(function(h){return h===it.el||it.el.contains(h)||h.contains(it.el)}))return false;return !intruders.some(function(o){return o.el!==it.el&&o.el.contains(it.el)&&!isChosenEl(o.el)})});topIntruders.forEach(function(it){try{bandShift(it.el);window.__tangentInsetedEls.push(it.el);sptMeas(it.el,getComputedStyle(it.el).position)}catch(e){}});overlays.forEach(function(ov){try{if(chosenEls.some(function(h){return h===ov.el||h.contains(ov.el)}))return;if(!overlayHasBandContent(ov.el))return;applyOverlay(ov.el,ov.cs);window.__tangentInsetedEls.push(ov.el)}catch(e){}});topBars.forEach(function(el){try{var _r=el.getBoundingClientRect();if(_r.top<=topPx+2&&_r.width>=window.innerWidth*0.6&&_r.bottom>sptWant&&_r.bottom<=sptCap)sptWant=_r.bottom}catch(e){}})}finally{try{var _applied=Math.max(window.__tangentSptApplied||0,Math.round(sptWant),window.__tangentSptFloor||topPx);window.__tangentSptApplied=_applied;setSPT(_applied)}catch(e){}window.__tangentLastChosenKey=chosenKey;window.__tangentLastHeaderInsetMs=Date.now();window.__tangentApplyingHeaderInsets=false;}}
        function schedule(){if(window.__tangentHeaderInsetPending)return;window.__tangentHeaderInsetPending=true;requestAnimationFrame(function(){window.__tangentHeaderInsetPending=false;applyHeaderInsets()})}
        spacer(topId,top);
        if(window.__tangentHeaderInsetObserver){window.__tangentHeaderInsetObserver.disconnect();window.__tangentHeaderInsetObserver=null}
        if(!skipHeader){if(!window.__tangentStickyIO&&typeof IntersectionObserver==='function'){window.__tangentStickyIO=new IntersectionObserver(function(){schedule()},{threshold:0})}/* Run the first pass synchronously (not via rAF schedule) so the header inset lands in the SAME layout pass as the body spacer above. Deferring it one frame makes a fixed/sticky header visibly jump down after content has already shifted. The cooldown guard at the top of applyHeaderInsets makes this a no-op if a recent pass already ran (rapid re-inject); every SUBSEQUENT pass still goes through the rAF-debounced schedule(). */applyHeaderInsets();setTimeout(schedule,1000);setTimeout(schedule,3000);setTimeout(schedule,10000);window.__tangentHeaderInsetObserver=new MutationObserver(function(records){if(window.__tangentApplyingHeaderInsets)return;const allOurs=records.every(function(r){return r.target.dataset&&(r.target.dataset.tangentSafeTopApplied!=null||r.target.dataset.tangentIgnoreSafeTop==='true')});if(allOurs)return;schedule()});window.__tangentHeaderInsetObserver.observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['class','style','slot','hidden','aria-hidden']});if(!window.__tangentListenersBound){window.__tangentListenersBound=true;window.addEventListener('resize',function(){schedule()},{passive:true});window.addEventListener('load',function(){schedule()},{passive:true});if(document.fonts&&document.fonts.ready){document.fonts.ready.then(function(){schedule()}).catch(function(){})}document.addEventListener('visibilitychange',function(){if(!document.hidden)schedule()});var wrapHist=function(n){var o=history[n];if(typeof o==='function'&&!o.__tangentWrapped){var w=function(){var r=o.apply(this,arguments);schedule();setTimeout(schedule,300);return r};w.__tangentWrapped=true;try{history[n]=w}catch(e){}}};wrapHist('pushState');wrapHist('replaceState');window.addEventListener('popstate',function(){schedule();setTimeout(schedule,300)});}}
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
