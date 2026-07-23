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
 * ### Tier 2 — composited filter-invert fallback + image classification (cheap, only when needed)
 * Sites with no dark theme stay light even under the override. For *only those* pages, this injector
 * adds a single class to `<html>` whose CSS applies `filter: invert(1) hue-rotate(180deg)` — a
 * hue-preserving inversion the GPU composites in one pass over the whole layer (no per-element color
 * math, no pixel analysis). Media (`img`/`video`/`canvas`/`iframe`/inline background-images) is
 * re-inverted by a static CSS selector so it renders normally — the browser's selector engine keeps
 * that correct as the DOM grows.
 *
 * Blanket counter-invert is right for *photos* but wrong for one class of images: logos, icons, line
 * art and white-background diagrams, which SHOULD follow the page (a black-on-transparent logo the
 * page filter turns white must stay white — counter-inverting it back to black makes it vanish on the
 * dark page). So the fallback runs a **cheap structural classifier**: a bounded pass over
 * `document.images` (plus an rAF-batched MutationObserver for newly-added images) tags likely graphics
 * — svg source, `logo`/`icon`/`sprite`/… name hints, or ≤32px — with `[data-lumina-dark-keep]`, which
 * the counter-invert selector *excludes* (`:not([data-lumina-dark-keep])`) so they invert with the
 * page. No pixel decode; images only, so it stays far cheaper than a full-DOM walk. Plus one
 * `getComputedStyle` read to decide whether the page is already dark, and a couple of re-checks.
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
 * ### Tier 3 — pixel-content image analysis (experimental, opt-in)
 * The Tier-2 structural classifier is heuristic: it can't tell a colorful photo thumbnail from a flat
 * icon by name/size alone. When [injectWebDarkMode]`(analyze = true)` (the `webDarkModeImageAnalysis`
 * pref), images the structural pass doesn't resolve are decided by their **actual pixels**: each is
 * drawn to a 16×16 offscreen canvas and classified photo-vs-graphic from the luminance range and
 * colorfulness of its **opaque** pixels (DarkReader's `analyzeImage` approach, downscaled). Transparency
 * is deliberately *not* a signal — a hero cutout of people on transparency is a photo, not a graphic,
 * so it stays counter-inverted (true color, dark page showing through). Verdicts cache by URL so
 * repeat images/visits cost nothing. Two hard limits keep this a *last-resort refinement*, never the
 * baseline: cross-origin images without CORS taint the canvas (`getImageData` throws → we fall back to
 * "photo"), and the per-pixel loop is the one genuinely CPU-heavy piece — this JS version is the
 * prototype of a future Rust→WASM kernel (runs in-page, so no process boundary). Off by default.
 *
 * Injected exactly like the viewport spacers / [SiteTint] (via [loadJavascriptUrl]); AGENTS.md
 * invariant #11 applies at the call site (set `suppressJavascriptLoadCallback` when injecting from a
 * load callback). One-way only — posts nothing back over the prompt bridge (Tier 3 reads canvas
 * pixels locally, never transmits them), so it needs no [BridgeGuard] nonce.
 *
 * ### Dynamic tier — per-section recoloring ([buildDynamicScript], opt-in)
 * The filter tiers above share one hard limit: a single GPU filter is *all-or-nothing* across the
 * page. On a professionally-designed page that mixes authored-light and authored-dark sections (e.g.
 * apple.com: a light "College" hero above an already-dark iPhone tile) the filter flips BOTH — the
 * dark tile bleaches to light — and no per-image tweak can fix it, because the filter can't see
 * sections. The **dynamic** path ([injectWebDarkMode]`(dynamic = true)`) is the DarkReader-*dynamic*
 * approach and the only thing that actually fixes mixed pages: no global filter at all. It walks the
 * DOM and, per element, reads the *computed* `background-color`/`color`/border colors and remaps each
 * in HSL space — **light colors are darkened; already-dark colors are left alone** (so the iPhone
 * tile survives). Because nothing is inverted, images/video need no counter-invert or classification —
 * photos (the people cutout) render in true color for free. This is the expensive tier the filter
 * tiers exist to avoid (a bounded `getComputedStyle` walk + an rAF-batched MutationObserver for
 * SPAs), so it is opt-in; hue is always preserved and extreme saturation is gently compressed for
 * comfort. It reuses the same `pageIsDark`/`isMediaDoc` gates, but decides *once* (before it mutates
 * any color — otherwise its own darkening would make the re-check see a "dark" page and bail).
 *
 * ### Dynamic tier — image cutout: ML Kit primary, WASM fallback ([buildImageCutoutScript])
 * The cutout has two keyers. **Primary:** native ML Kit **Subject Segmentation**
 * ([DarkImageSegmenter]) — semantic, so it keeps a light-on-light product (apple.com's silver
 * MacBook on a light-blue canvas) *whole* where a color/edge threshold physically cannot (the
 * palm-rest pixels sit inside the background's own color spread). When its model is ready the driver
 * only posts image URLs to native over the `lumina-darkcut` bridge. **Fallback** (offline / model not
 * yet downloaded): the WASM flood fill below.
 * The recolor above leaves images untouched, which is right for photos but wrong for the one case it
 * can't otherwise reach: a section whose entire background is a full-bleed **image** with a light
 * color baked in (apple.com's product heroes). Those stay glaring-light on the dark page, and any
 * live heading over them — lifted to near-white by `mapFgL` — turns light-on-light invisible. So in
 * dynamic mode we additionally run a per-image **cutout**: a tiny Rust→WASM kernel
 * (`tools/dark-cutout-wasm`, `no_std`, ~2 KB) does an edge-aware flood fill from each image's borders,
 * keying out its light, edge-connected background while the subject — and any light detail *not*
 * connected to the border — is preserved. It runs off the main thread in a Web Worker (`ImageBitmap`
 * transferred in), so there's no jank.
 *
 * The keyed alpha is emitted as a small **alpha mask** (RGB=0, keyed alpha) which the driver applies
 * to the *original* `<img>` via CSS `mask-image` (`mask-mode: alpha`, sized to the displayed content
 * rect so `object-fit` still lines up). The mask makes the light background transparent so the dark
 * page shows through, while the image itself is **never replaced** — so it stays full-resolution (no
 * downscale/re-encode blur) and a mis-key is non-destructive (correctable, and reversible on reload).
 * Fail-safe: a full-bleed photo (no light border) keys ~nothing and is left in true color, and any
 * CSP/worker/WASM/CORS failure just leaves the image alone. Assets are generated into
 * [DarkCutoutAssets] by `tools/dark-cutout-wasm/build.sh`.
 */
object WebDarkMode {

    private const val STYLE_ID = "lumina-dark-style"
    private const val FALLBACK_CLASS = "lumina-dark-fallback"
    private const val DYN_STYLE_ID = "lumina-dyn-style"
    private const val DYN_CLASS = "lumina-dyn"

    /**
     * Applies (or refreshes) web dark mode on the current page. When [dynamic] is true it runs the
     * per-section recoloring engine ([buildDynamicScript]) — the DarkReader-dynamic path that fixes
     * mixed light/dark pages; [aggressive]/[analyze] are filter-tier concepts and are ignored in that
     * mode. Otherwise it runs the composited filter fallback ([buildApplyScript]): [aggressive] also
     * re-inverts class-based CSS background images, [analyze] adds Tier-3 pixel image classification.
     *
     * In [dynamic] mode it *also* injects the WASM image-cutout pass ([buildImageCutoutScript]): the
     * per-section recolor can't darken sections whose background is a full-bleed **image** (Apple's
     * light product heroes), because it never touches images. The cutout keys out each image's light,
     * edge-connected background so those sections go dark and any overlaid heading stays readable —
     * while true photos (no light border) are left in true color.
     */
    fun EngineSession.injectWebDarkMode(
        dynamic: Boolean = false,
        aggressive: Boolean = false,
        analyze: Boolean = false,
        mlReady: Boolean = false,
    ) {
        loadJavascriptUrl(if (dynamic) buildDynamicScript() else buildApplyScript(aggressive, analyze))
        if (dynamic) loadJavascriptUrl(buildImageCutoutScript(mlReady))
    }

    /** Removes both the filter fallback and the dynamic recoloring (used when dark mode is off). */
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
    internal fun buildApplyScript(aggressive: Boolean = false, analyze: Boolean = false): String {
        return """
            javascript:(function(){
              var STYLE_ID='${STYLE_ID}';
              var CLS='${FALLBACK_CLASS}';
              var AGG=${aggressive};
              var ANALYZE=${analyze};
              var SCAN_CAP=${AGGRESSIVE_SCAN_CAP};
              function ensureStyle(){
                /* Media is counter-inverted so photos render normally — EXCEPT elements the classifier
                   tags [data-lumina-dark-keep] (logos/icons/line-art), which must invert WITH the page. */
                var KEEP=':not([data-lumina-dark-keep])';
                var sel=['img','video','picture','canvas','iframe','embed','object','[style*="background-image"]','[style*="background-image" i]','[data-lumina-dark-bg]'];
                var parts=[];for(var i=0;i<sel.length;i++){parts.push('html.'+CLS+' '+sel[i]+KEEP);}
                var media=parts.join(',');
                var css='html.'+CLS+'{filter:invert(1) hue-rotate(180deg) contrast(${ROOT_CONTRAST})!important;background-color:#fafafa!important;color-scheme:dark;}'
                  +media+'{filter:invert(1) hue-rotate(180deg) contrast(${MEDIA_CONTRAST})!important;}';
                var s=document.getElementById(STYLE_ID);
                if(!s){s=document.createElement('style');s.id=STYLE_ID;(document.head||document.documentElement).appendChild(s);}
                if(s.textContent!==css){s.textContent=css;}
              }
              function markKeep(el,keep){try{if(keep)el.setAttribute('data-lumina-dark-keep','1');else el.removeAttribute('data-lumina-dark-keep');}catch(e){}}
              /* className is a plain string on HTML but an SVGAnimatedString on SVG-namespaced nodes. */
              function nameHint(el){var cn=el.className;if(cn&&typeof cn!=='string'&&cn.baseVal!==undefined)cn=cn.baseVal;return (cn||'')+' '+(el.id||'')+' '+(el.alt||'');}
              /* Tier 2: cheap structural signals — decidable without decoding pixels. A match means
                 "graphic/icon, let it invert with the page"; anything else stays a counter-inverted photo. */
              function isGraphicImg(el){
                var src=(el.currentSrc||el.src||'').toLowerCase();
                if(/\.svg(\?|#|${'$'})/.test(src))return true;
                if(/logo|icon|sprite|badge|symbol|glyph|emoji|favicon/i.test(nameHint(el)))return true;
                var w=Math.max(el.naturalWidth||0,el.width||0,el.clientWidth||0);
                var h=Math.max(el.naturalHeight||0,el.height||0,el.clientHeight||0);
                if(w>0&&h>0&&w<=32&&h<=32)return true;
                return false;
              }
              /* Tier 3: decide photo-vs-graphic from actual pixels (16x16 downscale). Cached by URL.
                 CORS-tainted canvases throw on getImageData -> treated as photo (counter-inverted). */
              function analyzeImg(img){
                var src=img.currentSrc||img.src||'';
                var cache=window.__lumImgVerdict||(window.__lumImgVerdict={});
                if(src&&cache[src]!==undefined){markKeep(img,cache[src]);return;}
                var keep=false;
                try{
                  var S=16;var cv=document.createElement('canvas');cv.width=S;cv.height=S;
                  var ctx=cv.getContext('2d');ctx.drawImage(img,0,0,S,S);
                  var d=ctx.getImageData(0,0,S,S).data;
                  var minL=255,maxL=0,cnt=0,colorful=0;
                  for(var i=0;i<d.length;i+=4){
                    /* Skip transparent pixels: judge only the visible SUBJECT, so a cutout is
                       classified by what it depicts, not by its (irrelevant) transparent area. */
                    if(d[i+3]<200)continue;
                    var rr=d[i],gg=d[i+1],bb=d[i+2];
                    var l=0.2126*rr+0.7152*gg+0.0722*bb;
                    if(l<minL)minL=l;if(l>maxL)maxL=l;cnt++;
                    if(Math.max(rr,gg,bb)-Math.min(rr,gg,bb)>32)colorful++;
                  }
                  var colorRatio=cnt>0?colorful/cnt:0;var range=maxL-minL;
                  /* Graphic (logo/line-art/chart) => flat + low-color across its opaque pixels; invert
                     it WITH the page. Everything else — INCLUDING transparent photo cutouts (a hero of
                     people on transparency) — is a photo: counter-invert it so it keeps true color and
                     the dark page shows through the transparency. Transparency is NOT a graphic signal. */
                  keep=(cnt>0&&range<64&&colorRatio<0.15);
                }catch(e){keep=false;}
                if(src)cache[src]=keep;
                markKeep(img,keep);
              }
              function classifyEl(el){
                if(!el||el.nodeType!==1||el.tagName!=='IMG'||el.__lumKeepDone||el.__lumPending)return;
                if(isGraphicImg(el)){el.__lumKeepDone=1;markKeep(el,true);return;}
                /* Not loaded yet: defer so the size heuristic (and Tier-3 decode) sees real dimensions. */
                if(!(el.complete&&el.naturalWidth>0)){
                  el.__lumPending=1;
                  el.addEventListener('load',function(){el.__lumPending=0;el.__lumKeepDone=1;if(isGraphicImg(el)){markKeep(el,true);return;}if(ANALYZE)analyzeImg(el);},{once:true});
                  return;
                }
                el.__lumKeepDone=1;
                if(ANALYZE)analyzeImg(el);
              }
              function classifyImages(root){
                var r=root||document;
                if(!r.getElementsByTagName){if(r.nodeType===1)classifyEl(r);return;}
                var imgs=r.getElementsByTagName('img');
                var n=Math.min(imgs.length,SCAN_CAP);
                for(var i=0;i<n;i++)classifyEl(imgs[i]);
                if(r.nodeType===1)classifyEl(r);
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
              /* One observer serves both the always-on image classifier and the opt-in bg scanner:
                 newly-added subtrees are classified (imgs) and, when AGG, bg-tagged — rAF-batched. */
              function ensureObserver(){
                if(window.__luminaDarkObserver)return;
                var pending=false;var queue=[];
                function flush(){pending=false;var q=queue;queue=[];for(var i=0;i<q.length;i++){classifyImages(q[i]);if(AGG)scanBg(q[i]);}}
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
                  /* Only pay the image/background scan on pages actually getting the filter. */
                  classifyImages(document);
                  if(AGG)scanBg(document.body);
                  ensureObserver();
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

    /** Cap on elements the dynamic per-section walk touches per pass — bounds worst-case cost. */
    private const val DYN_SCAN_CAP = 8000

    /** Dark canvas base for the dynamic tier (mixed pages need an opaque dark floor behind transparency). */
    private const val DYN_BASE_BG = "#1c1c1e"

    /**
     * The **dynamic** per-section recoloring script (pure; JVM-testable). Same one-line-`javascript:`
     * shape and block-comment-only rule as [buildApplyScript].
     *
     * Unlike the filter tiers this applies **no** page-wide filter. It walks the DOM (bounded by
     * [DYN_SCAN_CAP], plus an rAF-batched MutationObserver for later/ SPA nodes) and, per element,
     * reads the computed `background-color`/`color`/border colors, converts each to HSL, and remaps
     * lightness so light colors go dark while already-dark colors stay put (`mapBgL`/`mapFgL` are
     * piecewise, hinged at a threshold below which the input passes through unchanged). Hue is always
     * preserved; extreme saturation is gently compressed. Transparent colors are left untouched so a
     * darkened ancestor shows through. Media (`img`/`video`/`canvas`/`iframe`/…) and SVG-namespaced
     * nodes are skipped entirely — with no inversion, photos and logos are already correct.
     *
     * The light-vs-dark verdict is taken **once** and latched in `window.__lumDynActive`: after the
     * first pass our own darkening would make `getComputedStyle` report a dark page, so a re-decide
     * would wrongly bail. Later `apply()` passes only catch up newly-added nodes.
     */
    internal fun buildDynamicScript(): String {
        return """
            javascript:(function(){
              var STYLE_ID='${DYN_STYLE_ID}';
              var CLS='${DYN_CLASS}';
              var BASE_BG='${DYN_BASE_BG}';
              var CAP=${DYN_SCAN_CAP};
              var DONE='__lumDynDone';
              function baseStyle(){
                var css='html.'+CLS+'{background-color:'+BASE_BG+' !important;color-scheme:dark;}'
                  +'html.'+CLS+' img,html.'+CLS+' video,html.'+CLS+' canvas,html.'+CLS+' picture,html.'+CLS+' iframe,html.'+CLS+' embed,html.'+CLS+' object{filter:none !important;}';
                var s=document.getElementById(STYLE_ID);
                if(!s){s=document.createElement('style');s.id=STYLE_ID;(document.head||document.documentElement).appendChild(s);}
                if(s.textContent!==css)s.textContent=css;
              }
              function parseColor(c){
                if(!c)return null;
                if(c==='transparent')return[0,0,0,0];
                var m=c.match(/rgba?\(([^)]+)\)/);
                if(!m)return null;
                var p=m[1].split(',');
                var r=parseFloat(p[0]),g=parseFloat(p[1]),b=parseFloat(p[2]);
                var a=p.length>3?parseFloat(p[3]):1;
                if(isNaN(r)||isNaN(g)||isNaN(b))return null;
                return[r,g,b,a];
              }
              function rgbToHsl(r,g,b){
                r/=255;g/=255;b/=255;
                var mx=Math.max(r,g,b),mn=Math.min(r,g,b),h,s,l=(mx+mn)/2;
                if(mx===mn){h=0;s=0;}
                else{var d=mx-mn;s=l>0.5?d/(2-mx-mn):d/(mx+mn);
                  if(mx===r)h=(g-b)/d+(g<b?6:0);else if(mx===g)h=(b-r)/d+2;else h=(r-g)/d+4;h/=6;}
                return[h,s,l];
              }
              function hue2rgb(p,q,t){if(t<0)t+=1;if(t>1)t-=1;if(t<1/6)return p+(q-p)*6*t;if(t<1/2)return q;if(t<2/3)return p+(q-p)*(2/3-t)*6;return p;}
              function hslToRgb(h,s,l){
                var r,g,b;
                if(s===0){r=l;g=l;b=l;}
                else{var q=l<0.5?l*(1+s):l+s-l*s;var p=2*l-q;r=hue2rgb(p,q,h+1/3);g=hue2rgb(p,q,h);b=hue2rgb(p,q,h-1/3);}
                return[Math.round(r*255),Math.round(g*255),Math.round(b*255)];
              }
              /* Backgrounds: dark inputs (<=T) pass through so a section a site already made dark is
                 preserved; light inputs are pulled down into a 0.12..T dark band. */
              function mapBgL(l){var T=0.35;if(l<=T)return l;return T-(l-T)/(1-T)*(T-0.12);}
              /* Text: light inputs (>=T) pass through (already readable on dark); dark inputs are
                 lifted up toward 0.92 so body copy becomes legible. */
              function mapFgL(l){var T=0.55;if(l>=T)return l;return T+(T-l)/T*(0.92-T);}
              /* Borders sit between: darken light rules but keep them a touch above the bg band. */
              function mapBorderL(l){var T=0.4;if(l<=T)return l;return T-(l-T)/(1-T)*(T-0.22);}
              function modify(c,kind){
                var p=parseColor(c);if(!p)return null;
                if(p[3]===0)return null;
                var hsl=rgbToHsl(p[0],p[1],p[2]);
                var nl=kind===0?mapBgL(hsl[2]):(kind===1?mapFgL(hsl[2]):mapBorderL(hsl[2]));
                var ns=hsl[1];if(ns>0.6)ns=0.6+(ns-0.6)*0.5;
                var rgb=hslToRgb(hsl[0],ns,nl);
                if(p[3]<1)return 'rgba('+rgb[0]+','+rgb[1]+','+rgb[2]+','+p[3]+')';
                return 'rgb('+rgb[0]+','+rgb[1]+','+rgb[2]+')';
              }
              function isMedia(t){return t==='IMG'||t==='VIDEO'||t==='CANVAS'||t==='IFRAME'||t==='EMBED'||t==='OBJECT'||t==='PICTURE'||t==='SVG';}
              function styleEl(el){
                if(!el||el.nodeType!==1||el[DONE])return;
                el[DONE]=1;
                if(isMedia(el.tagName))return;
                /* Skip anything in the SVG namespace (icons/logos) — recoloring their fills mangles art. */
                if(el.namespaceURI&&el.namespaceURI.indexOf('svg')>=0)return;
                var cs;try{cs=getComputedStyle(el);}catch(e){return;}
                var nb=modify(cs.backgroundColor,0);
                if(nb)el.style.setProperty('background-color',nb,'important');
                var nf=modify(cs.color,1);
                if(nf)el.style.setProperty('color',nf,'important');
                if(cs.borderTopWidth!=='0px'||cs.borderBottomWidth!=='0px'||cs.borderLeftWidth!=='0px'||cs.borderRightWidth!=='0px'){
                  var sides=['borderTopColor','borderRightColor','borderBottomColor','borderLeftColor'];
                  var props=['border-top-color','border-right-color','border-bottom-color','border-left-color'];
                  for(var k=0;k<4;k++){var nc=modify(cs[sides[k]],2);if(nc)el.style.setProperty(props[k],nc,'important');}
                }
                /* Recolor gradient stops (cheap regex) but leave url() image backgrounds to render as-is. */
                var bi=cs.backgroundImage;
                if(bi&&bi.indexOf('gradient')>=0&&bi.indexOf('url(')<0){
                  var ng=bi.replace(/rgba?\([^)]+\)/g,function(m){var r=modify(m,0);return r||m;});
                  if(ng!==bi)el.style.setProperty('background-image',ng,'important');
                }
              }
              function scan(host){
                styleEl(document.documentElement);
                if(document.body)styleEl(document.body);
                var root=host||document.body;if(!root)return;
                if(root.nodeType===1)styleEl(root);
                if(!root.getElementsByTagName)return;
                var all=root.getElementsByTagName('*');
                var n=Math.min(all.length,CAP);
                for(var i=0;i<n;i++)styleEl(all[i]);
              }
              function ensureObserver(){
                if(window.__luminaDynObserver)return;
                var queue=[],pending=false;
                function flush(){pending=false;var q=queue;queue=[];for(var i=0;i<q.length;i++)scan(q[i]);}
                window.__luminaDynObserver=new MutationObserver(function(recs){
                  for(var i=0;i<recs.length;i++){var a=recs[i].addedNodes;for(var j=0;j<a.length;j++){if(a[j].nodeType===1)queue.push(a[j]);}}
                  if(queue.length&&!pending){pending=true;requestAnimationFrame(flush);}
                });
                try{window.__luminaDynObserver.observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
              }
              function bgLum(el){
                if(!el)return -1;
                var c;try{c=getComputedStyle(el).backgroundColor;}catch(e){return -1;}
                var p=parseColor(c);if(!p||p[3]<0.5)return -1;
                return 0.2126*p[0]+0.7152*p[1]+0.0722*p[2];
              }
              function pageIsDark(){
                var l=bgLum(document.body);if(l<0)l=bgLum(document.documentElement);
                if(l<0)return false;
                return l<128;
              }
              function isMediaDoc(){
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
                var root=document.documentElement;if(!root)return;
                /* Already engaged: only catch up newly-added nodes. Re-deciding here would be wrong —
                   our own recoloring has since darkened the page. */
                if(window.__lumDynActive){scan(document.body);return;}
                if(pageIsDark()||isMediaDoc())return;
                window.__lumDynActive=1;
                baseStyle();
                root.classList.add(CLS);
                scan(document.body);
                ensureObserver();
              }
              apply();
              setTimeout(apply,300);
              setTimeout(apply,1200);
            })();
        """.trimIndent().replace("\n", " ")
    }

    /* ---- WASM image-cutout tuning (see tools/dark-cutout-wasm; kernel = src/lib.rs) ---- */
    /** Min on-screen side (px) an image must have before it's worth a cutout pass (skips icons). */
    private const val CUT_MIN_SIZE = 100
    /** Max long side the image is downscaled to before keying — bounds getImageData + kernel cost.
     *  Matches the kernel's 1536px arena so full-bleed hero/product images key at native 2×-DPR
     *  resolution instead of a soft 768px downscale that looked pixelated on large slots. */
    private const val CUT_MAX_DIM = 1536
    /** Border seed luminance (0-255): only opaque pixels this bright seed the background fill. */
    private const val CUT_L_SEED = 210
    /** Fill floor luminance: growing stops below this so a soft edge can't leak into a dark subject. */
    private const val CUT_L_FLOOR = 170
    /** Edge barrier: max sum-abs per-channel step the fill may cross (halts at the subject outline).
     *  Low value (5) restricts the fill to solid colors and very mild gradients — a steep gradient
     *  crosses this barrier in 1–2 pixels and stops, leaving the image untouched. */
    private const val CUT_STEP_MAX = 5
    /** Min alpha to treat a pixel as opaque background (transparent cutouts already show the page). */
    private const val CUT_MIN_ALPHA = 128
    /** Adaptive background band: max sum-abs per-channel distance a filled pixel may sit from the
     *  **measured** background color (mean of the seed/border pixels). This is what makes keying
     *  relative to the image's own background instead of an absolute contrast threshold — it halts the
     *  fill creeping through a smooth fade into a light-but-off-background surface (a MacBook's silver
     *  deck), even where no sharp edge exists, while still allowing a genuine background gradient
     *  (which stays near its own mean). Fallback-tier only now — the ML segmenter is the primary
     *  keyer — so it errs toward preserving product surfaces (70 ≈ 23/channel). */
    private const val CUT_COLOR_BAND = 70
    /** De-fringe luminance gate: after the fill, opaque pixels bordering a keyed pixel whose luminance
     *  is at least this get keyed too, peeling the light anti-aliased halo ring the fill leaves one
     *  pixel short of the subject outline. Kept above mid-luma so a genuine dark subject edge survives
     *  (only the light blended fringe, which glows on the dark page, is removed). */
    private const val CUT_L_EDGE = 150
    /** How many halo rings to peel (each pass removes one pixel of light fringe). Two clears the
     *  typical 1–2 px anti-aliased seam without visibly eroding the subject. 0 disables de-fringing. */
    private const val CUT_DEFRINGE_ITERS = 2
    /** Apply the result only if the keyed fraction lands in this band (else it's a photo / near-blank). */
    private const val CUT_MIN_FRAC = "0.02"
    /** Upper reject bound: only a *near-total* key (essentially the whole image read as light) is
     *  dropped. Large full-bleed product heroes legitimately key 90%+ of their pixels, so this stays
     *  high — and because the cutout is now a non-destructive CSS mask over the original image, an
     *  over-key is low-harm (crisp original, wrong transparency) rather than a baked-in mistake. */
    private const val CUT_MAX_FRAC = "0.985"

    /**
     * The image-cutout injection: the page-context driver ([DarkCutoutAssets.DRIVER_JS]) plus the call
     * that boots it with the WASM kernel + Web Worker blobs and the tuning params above. Runs only in
     * dynamic mode. Fail-safe by construction — if the worker, WASM compile, or `createImageBitmap`
     * is blocked (CSP) or an image is cross-origin-tainted, the image is simply left untouched and the
     * element recolor stands. Same one-line-`javascript:` shape as the other builders; the driver is
     * pre-flattened and block-comment-only (invariant #11) in [DarkCutoutAssets].
     */
    internal fun buildImageCutoutScript(mlReady: Boolean = false): String {
        // When mlReady, the driver takes the native ML-Kit path: it only tags large <img>s and posts
        // their URLs over this nonce-tagged bridge (DarkImageSegmenter). Otherwise it runs the WASM
        // flood-fill fallback with the tuning params below.
        val bridge = BridgeGuard.tag(DarkImageSegmenter.BRIDGE_MESSAGE)
        val params = "{minSize:$CUT_MIN_SIZE,maxDim:$CUT_MAX_DIM,lSeed:$CUT_L_SEED,lFloor:$CUT_L_FLOOR," +
            "stepMax:$CUT_STEP_MAX,minAlpha:$CUT_MIN_ALPHA,colorBand:$CUT_COLOR_BAND," +
            "lEdge:$CUT_L_EDGE,defringeIters:$CUT_DEFRINGE_ITERS," +
            "minFrac:$CUT_MIN_FRAC,maxFrac:$CUT_MAX_FRAC,mlReady:$mlReady,bridge:\"$bridge\"}"
        return "javascript:(function(){${DarkCutoutAssets.DRIVER_JS} try{lumCutSetup(" +
            "\"${DarkCutoutAssets.WASM_B64}\",\"${DarkCutoutAssets.WORKER_B64}\",$params);}catch(e){}})();"
    }

    /**
     * A self-contained `javascript:` that applies a finished alpha mask (a `data:image/png` URI) to the
     * `<img>` the ML path tagged `data-lumina-cut-id="[id]"`. Injected by the host when
     * [DarkImageSegmenter] returns a segmentation result. Sizes/positions the mask to the displayed
     * content rect so `object-fit` still lines up — same geometry as the driver's WASM applyMask.
     * Self-contained (doesn't depend on the driver still being present) and fail-safe.
     */
    internal fun buildApplyMaskScript(id: String, maskDataUri: String, natW: Int, natH: Int): String {
        return """
            javascript:(function(){
              try{
                var el=document.querySelector('[data-lumina-cut-id="$id"]');
                if(!el)return;
                var natW=$natW,natH=$natH;
                var boxW=el.clientWidth,boxH=el.clientHeight;if(!boxW||!boxH)return;
                var fit='fill';try{fit=getComputedStyle(el).objectFit||'fill';}catch(e){}
                var size='100% 100%',pos='0 0';
                if(fit!=='fill'&&natW>0&&natH>0){
                  var na=natW/natH,ba=boxW/boxH,cw,ch;
                  if(fit==='none'||(fit==='scale-down'&&natW<=boxW&&natH<=boxH)){cw=natW;ch=natH;}
                  else if(fit==='cover'){if(na>ba){ch=boxH;cw=boxH*na;}else{cw=boxW;ch=boxW/na;}}
                  else{if(na>ba){cw=boxW;ch=boxW/na;}else{ch=boxH;cw=boxH*na;}}
                  size=Math.round(cw)+'px '+Math.round(ch)+'px';pos='center';
                }
                var u='$maskDataUri';var s=el.style;
                s.setProperty('-webkit-mask-image','url('+u+')','important');
                s.setProperty('mask-image','url('+u+')','important');
                s.setProperty('-webkit-mask-size',size,'important');
                s.setProperty('mask-size',size,'important');
                s.setProperty('-webkit-mask-position',pos,'important');
                s.setProperty('mask-position',pos,'important');
                s.setProperty('-webkit-mask-repeat','no-repeat','important');
                s.setProperty('mask-repeat','no-repeat','important');
                s.setProperty('mask-mode','alpha','important');
              }catch(e){}
            })();
        """.trimIndent().replace("\n", " ")
    }

    /**
     * The removal script: drops both the filter fallback (class + `<style>` + observer) and the
     * dynamic tier (class + `<style>` + observer + the per-element inline recolors it set). Stripping
     * `background-color`/`color`/border/`background-image` from every element is coarse — it also
     * clears any inline color a page had set on those same elements — but a reload restores them, and
     * toggling dark mode off mid-session is rare; the alternative (tracking each element's original)
     * is far heavier for a teardown path.
     */
    internal fun buildRemoveScript(): String {
        return """
            javascript:(function(){
              try{document.documentElement.classList.remove('${FALLBACK_CLASS}');}catch(e){}
              try{var s=document.getElementById('${STYLE_ID}');if(s&&s.parentNode)s.parentNode.removeChild(s);}catch(e){}
              try{if(window.__luminaDarkObserver){window.__luminaDarkObserver.disconnect();window.__luminaDarkObserver=null;}}catch(e){}
              try{document.documentElement.classList.remove('${DYN_CLASS}');}catch(e){}
              try{var ds=document.getElementById('${DYN_STYLE_ID}');if(ds&&ds.parentNode)ds.parentNode.removeChild(ds);}catch(e){}
              try{if(window.__luminaDynObserver){window.__luminaDynObserver.disconnect();window.__luminaDynObserver=null;}}catch(e){}
              /* Tear down the image-cutout worker and observer. */
              try{if(window.__lumCut&&window.__lumCut.worker)window.__lumCut.worker.terminate();window.__lumCut=null;}catch(e){}
              try{if(window.__lumCutObserver){window.__lumCutObserver.disconnect();window.__lumCutObserver=null;}}catch(e){}
              window.__lumDynActive=0;
              /* Strip the per-element recolor AND the cutout's CSS mask (the mask is inline on <img>), so
                 toggling dark mode off restores the original page without a reload. */
              try{var el=document.getElementsByTagName('*');for(var i=0;i<el.length;i++){var st=el[i].style;if(st){st.removeProperty('background-color');st.removeProperty('color');st.removeProperty('border-top-color');st.removeProperty('border-right-color');st.removeProperty('border-bottom-color');st.removeProperty('border-left-color');st.removeProperty('background-image');st.removeProperty('mask-image');st.removeProperty('-webkit-mask-image');st.removeProperty('mask-size');st.removeProperty('-webkit-mask-size');st.removeProperty('mask-position');st.removeProperty('-webkit-mask-position');st.removeProperty('mask-repeat');st.removeProperty('-webkit-mask-repeat');st.removeProperty('mask-mode');}el[i].__lumDynDone=0;el[i].__lumCut=0;}}catch(e){}
            })();
        """.trimIndent().replace("\n", " ")
    }
}
