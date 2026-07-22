package com.firebolt9907.luminabrowser.ui.content

import mozilla.components.concept.engine.EngineSession

/**
 * High-performance "dark mode for every website" — a two-tier design that reaches DarkReader-like
 * coverage at a fraction of DarkReader *dynamic* mode's cost.
 *
 * DarkReader dynamic parses every stylesheet, recomputes a dark equivalent for every declared color,
 * analyzes background-image pixels, and watches the whole DOM with a MutationObserver that re-runs
 * that analysis on each mutation. The result is high quality but expensive — measurable jank on
 * complex / SPA sites and steady CPU + memory overhead. We get most of the coverage far more cheaply
 * by splitting the problem into a native tier and a GPU-composited fallback tier:
 *
 * ### Tier 1 — native `prefers-color-scheme: dark` (free, best quality)
 * The host sets `engine.settings.preferredColorScheme = Dark` (see `Engine.applyEngineSettings`),
 * which flips the `@media (prefers-color-scheme: dark)` query for **all** content. Any site that
 * ships a real dark theme (most modern sites) renders it — pixel-perfect, authored by the site, and
 * costing exactly nothing extra to us. This tier needs no injection at all.
 *
 * ### Tier 2 — composited filter-invert fallback (cheap, only when needed)
 * Sites with no dark theme stay light even under the override. For *only those* pages, this injector
 * adds a single class to `<html>` whose CSS applies `filter: invert(1) hue-rotate(180deg)` — a
 * hue-preserving inversion the GPU composites in one pass over the whole layer (no per-element color
 * math, no pixel analysis). Media (`img`/`video`/`canvas`/`iframe`/inline background-images) is
 * re-inverted by a static CSS selector so it renders normally — the browser's selector engine keeps
 * that correct as the DOM grows, so newly-added nodes need **no** JS. The only runtime work is one
 * `getComputedStyle` read to decide whether the page is already dark, plus a couple of re-checks.
 *
 * Deciding light-vs-dark: after Tier 1 is active we read the effective page background color. If it's
 * dark the site honored the override (Tier 1 handled it) and we leave it alone; if it's light we add
 * the fallback class. `getComputedStyle` returns the *declared* background, unaffected by our filter,
 * so re-running the check is idempotent and stable.
 *
 * ### Known tradeoff (the price of "high performance")
 * The fallback re-inverts only media it can select statically — `<img>`/`<video>`/etc. and
 * **inline**-style background images. Background images set from a CSS *class* (a `.hero { background:
 * url(…) }`) are inverted along with the page and can look off. This is the same limitation as
 * DarkReader's non-dynamic "Filter" mode; catching class backgrounds is exactly the expensive
 * per-element analysis this design deliberately avoids by default. Modern sites that have this problem
 * almost always also ship a real dark theme, so Tier 1 covers them and the fallback never engages.
 *
 * For the sites where it matters, the opt-in **aggressive** sub-mode ([injectWebDarkMode]`(aggressive
 * = true)`, the `webDarkModeAggressive` pref) closes this gap far more cheaply than DarkReader
 * dynamic: instead of recomputing every color on every mutation, it runs one bounded `getComputedStyle`
 * walk that *tags* elements with a `url(...)` background (`[data-lumina-dark-bg]`, added to the
 * re-invert selector), plus a debounced/rAF-batched MutationObserver that tags only newly-added
 * subtrees. It's off by default so the standard path stays the pure high-performance filter.
 *
 * Injected exactly like the viewport spacers / [SiteTint] (via [loadJavascriptUrl]); AGENTS.md
 * invariant #11 applies at the call site (set `suppressJavascriptLoadCallback` when injecting from a
 * load callback). One-way only — posts nothing back over the prompt bridge, so it needs no
 * [BridgeGuard] nonce.
 */
object WebDarkMode {

    private const val STYLE_ID = "lumina-dark-style"
    private const val FALLBACK_CLASS = "lumina-dark-fallback"

    /**
     * Applies (or refreshes) the fallback tier on the current page. When [aggressive] is true the
     * script also detects and re-inverts class-based CSS background images (the opt-in quality mode).
     */
    fun EngineSession.injectWebDarkMode(aggressive: Boolean = false) {
        loadJavascriptUrl(buildApplyScript(aggressive))
    }

    /** Removes the fallback tier's style + class (used when dark mode is toggled off). */
    fun EngineSession.removeWebDarkMode() {
        loadJavascriptUrl(buildRemoveScript())
    }

    /** Cap on elements the aggressive background-image scan touches per pass — bounds worst-case cost. */
    private const val AGGRESSIVE_SCAN_CAP = 4000

    /**
     * Comfort softening for the filter tier. A plain `invert` turns a white page into *pure* black
     * background + *pure* white text — a harsh, halation-inducing contrast that reads as "cheap
     * inversion". DarkReader deliberately lands on a softer palette (~#181a1b bg, ~#e8e6e3 text). We
     * match that for free by folding a mild `contrast(<1)` into the root filter, which pulls both
     * extremes toward mid-gray (pure black → dark gray, pure white → light gray).
     *
     * To keep photos faithful (DarkReader dynamic preserves image contrast), media carries the
     * *inverse* contrast: the element's own filter and the root filter compose multiplicatively, so
     * `contrast(1/ROOT)` under the page's `contrast(ROOT)` nets ≈ `contrast(1.0)` on the original
     * image — only text/backgrounds get the softening, images stay crisp.
     */
    private const val ROOT_CONTRAST = "0.9"
    private const val MEDIA_CONTRAST = "1.1111"

    /**
     * The apply script as a flattened one-line `javascript:` URL (pure; JVM-testable). Use only block
     * comments — the source is flattened to one line so a `//` would eat the rest. Idempotent: safe to
     * run repeatedly (per load, on the re-check timers).
     *
     * When [aggressive] is true the script additionally runs a **bounded** background-image scanner: a
     * one-shot (capped) `getComputedStyle` walk that tags elements whose computed `background-image` is
     * a `url(...)` with `[data-lumina-dark-bg]` (added to the re-invert selector), plus a debounced,
     * capped MutationObserver that tags only newly-added subtrees. This closes the class-based
     * background-image gap (hero photos rendering as negatives) at a fraction of DarkReader dynamic's
     * cost — it tags once instead of recomputing every color on every mutation. Off by default.
     */
    internal fun buildApplyScript(aggressive: Boolean = false): String {
        return """
            javascript:(function(){
              var STYLE_ID='${STYLE_ID}';
              var CLS='${FALLBACK_CLASS}';
              var AGG=${aggressive};
              var SCAN_CAP=${AGGRESSIVE_SCAN_CAP};
              function ensureStyle(){
                var media='html.'+CLS+' img,html.'+CLS+' video,html.'+CLS+' picture,html.'+CLS+' canvas,html.'+CLS+' iframe,html.'+CLS+' embed,html.'+CLS+' object,html.'+CLS+' [style*="background-image"],html.'+CLS+' [style*="background-image" i],html.'+CLS+' [data-lumina-dark-bg]';
                var css='html.'+CLS+'{filter:invert(1) hue-rotate(180deg) contrast(${ROOT_CONTRAST})!important;background-color:#fafafa!important;color-scheme:dark;}'
                  +media+'{filter:invert(1) hue-rotate(180deg) contrast(${MEDIA_CONTRAST})!important;}';
                var s=document.getElementById(STYLE_ID);
                if(!s){s=document.createElement('style');s.id=STYLE_ID;(document.head||document.documentElement).appendChild(s);}
                if(s.textContent!==css){s.textContent=css;}
              }
              function tagBg(el){
                if(!el||el.nodeType!==1||el.__lumBg)return;
                el.__lumBg=1;
                var bi;
                try{bi=getComputedStyle(el).backgroundImage;}catch(e){return;}
                /* Only real raster/vector backgrounds (url()) invert wrong; gradients are decorative
                   and reading them inverted is fine/desired, so leave them to the page filter. */
                if(bi&&bi.indexOf('url(')>=0){el.setAttribute('data-lumina-dark-bg','1');}
              }
              function scanBg(rootEl){
                if(!AGG)return;
                var root=rootEl||document.body;if(!root)return;
                var all=root.getElementsByTagName('*');
                var n=Math.min(all.length,SCAN_CAP);
                for(var i=0;i<n;i++){tagBg(all[i]);}
                if(rootEl&&rootEl.nodeType===1)tagBg(rootEl);
              }
              function ensureBgObserver(){
                if(!AGG||window.__luminaDarkObserver)return;
                var pending=false;var queue=[];
                function flush(){pending=false;var q=queue;queue=[];for(var i=0;i<q.length;i++){scanBg(q[i]);}}
                window.__luminaDarkObserver=new MutationObserver(function(records){
                  for(var i=0;i<records.length;i++){
                    var added=records[i].addedNodes;
                    for(var j=0;j<added.length;j++){if(added[j].nodeType===1)queue.push(added[j]);}
                  }
                  if(queue.length&&!pending){pending=true;requestAnimationFrame(flush);}
                });
                try{window.__luminaDarkObserver.observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
              }
              function bgLum(el){
                if(!el)return -1;
                var c;
                try{c=getComputedStyle(el).backgroundColor;}catch(e){return -1;}
                var m=c&&c.match(/rgba?\(([^)]+)\)/);
                if(!m)return -1;
                var p=m[1].split(',');
                var a=p.length>3?parseFloat(p[3]):1;
                if(a<0.5)return -1;
                var r=parseFloat(p[0]),g=parseFloat(p[1]),b=parseFloat(p[2]);
                return 0.2126*r+0.7152*g+0.0722*b;
              }
              function pageIsDark(){
                var l=bgLum(document.body);
                if(l<0)l=bgLum(document.documentElement);
                /* No opaque background declared anywhere: the UA canvas is white (light), so under the
                   prefers-color-scheme override an honored dark theme would have set an opaque dark bg.
                   Treat "no bg" as light so the fallback engages. */
                if(l<0)return false;
                return l<128;
              }
              function isMediaDoc(){
                /* A directly-viewed image/video/audio/PDF is a browser-generated document wrapping a
                   single media element. Inverting it turns a photo into a negative (and a PDF into a
                   wrongly-lit scan), so the filter must never touch these — the page IS the content,
                   not chrome around it. Detected by the document's own content type, with a structural
                   fallback for the lone-media-child wrapper Gecko builds. */
                try{
                  var ct=document.contentType||'';
                  if(/^(image|video|audio)\//.test(ct)||ct==='application/pdf')return true;
                  var b=document.body;
                  if(b&&b.children&&b.children.length===1){
                    var t=b.children[0].tagName;
                    if(t==='IMG'||t==='VIDEO'||t==='AUDIO')return true;
                  }
                }catch(e){}
                return false;
              }
              function apply(){
                ensureStyle();
                var root=document.documentElement;
                if(!root)return;
                if(pageIsDark()||isMediaDoc()){root.classList.remove(CLS);}
                else{
                  root.classList.add(CLS);
                  /* Only pay the background-image scan on pages actually getting the filter. */
                  if(AGG){scanBg(document.body);ensureBgObserver();}
                }
              }
              apply();
              /* A couple of re-checks catch sites that swap in a (dark) theme a beat after load, so we
                 remove the fallback once their real dark theme lands. Cheap: one getComputedStyle each. */
              setTimeout(apply,300);
              setTimeout(apply,1200);
            })();
        """.trimIndent().replace("\n", " ")
    }

    /** The removal script: drops the fallback class + the injected `<style>`. */
    internal fun buildRemoveScript(): String {
        return """
            javascript:(function(){
              try{document.documentElement.classList.remove('${FALLBACK_CLASS}');}catch(e){}
              try{var s=document.getElementById('${STYLE_ID}');if(s&&s.parentNode)s.parentNode.removeChild(s);}catch(e){}
              try{if(window.__luminaDarkObserver){window.__luminaDarkObserver.disconnect();window.__luminaDarkObserver=null;}}catch(e){}
            })();
        """.trimIndent().replace("\n", " ")
    }
}
