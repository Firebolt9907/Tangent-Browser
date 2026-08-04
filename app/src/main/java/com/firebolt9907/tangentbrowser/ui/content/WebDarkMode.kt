package com.firebolt9907.tangentbrowser.ui.content

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
 * — svg source, `logo`/`icon`/`sprite`/… name hints, or ≤32px — with `[data-tangent-dark-keep]`, which
 * the counter-invert selector *excludes* (`:not([data-tangent-dark-keep])`) so they invert with the
 * page. No pixel decode; images only, so it stays far cheaper than a full-DOM walk. Plus one
 * `getComputedStyle` read to decide whether the page is already dark, and a couple of re-checks.
 *
 * Deciding light-vs-dark is [darkDetectJs] — see its doc for why reading `body`/`html`'s declared
 * background is not enough (wrapper-painted surfaces and `color-scheme`-only dark themes both read as
 * light, and get inverted on top of their own dark theme). If the page is already dark the site
 * honored the override and Tier 1 handled it, so we leave it alone; if it's light we add the fallback
 * class. The check measures with our own class off, so re-running it is idempotent and stable.
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
 * walk that *tags* elements with a `url(...)` background (`[data-tangent-dark-bg]`, added to the
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
 * only posts image URLs to native over the `tangent-darkcut` bridge. **Fallback** (offline / model not
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

    private const val STYLE_ID = "tangent-dark-style"
    private const val FALLBACK_CLASS = "tangent-dark-fallback"
    private const val DYN_STYLE_ID = "tangent-dyn-style"
    private const val DYN_CLASS = "tangent-dyn"

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

    /**
     * Lightweight teardown of ONLY the document_start pre-paint filter (the `tangent-dark-fallback`
     * class + `#tangent-dark-style`), without the heavy per-element inline-style walk of
     * [buildRemoveScript]. Used per-load on a page where web dark mode is globally on but this
     * specific site is set to Off: the pre-paint content script still ran (extension is global), so
     * its provisional invert must be stripped, but the page has no dynamic recolors to undo.
     */
    fun EngineSession.removeWebDarkModePrePaint() {
        loadJavascriptUrl(buildRemovePrePaintScript())
    }

    /** @see removeWebDarkModePrePaint. Pure/JVM-testable; block comments only (flattened to one line). */
    internal fun buildRemovePrePaintScript(): String {
        return """
            javascript:(function(){
              try{document.documentElement.classList.remove('${FALLBACK_CLASS}');}catch(e){}
              try{var s=document.getElementById('${STYLE_ID}');if(s&&s.parentNode)s.parentNode.removeChild(s);}catch(e){}
            })();
        """.trimIndent().replace("\n", " ")
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
     * The shared **"is this page already dark?"** detector — the gate that decides whether Tier 1 (the
     * site's own dark theme, honored via the forced `prefers-color-scheme: dark`) already handled the
     * page, in which case we must keep our hands off it entirely.
     *
     * Getting this wrong is the most visible failure the whole feature has: a false *negative* inverts
     * a page that is already dark, which reads as "dark mode is broken on this site". Inlined verbatim
     * into [buildApplyScript] and [buildDynamicScript], and mirrored in
     * `assets/extensions/darkmode/darkmode.js` (the document_start pre-paint script, which has no way
     * to import it) — `WebDarkModeScriptTest` pins that all three agree.
     *
     * Three signals, because reading only `body`/`html`'s declared background misses two entire
     * classes of dark theme:
     *
     *  - **Painted surface.** Sites overwhelmingly leave the *document* background alone and paint
     *    their real surface on a full-bleed wrapper `<div>`. `body` then computes to the light default
     *    and the page reads as light no matter how dark it looks. So we hit-test a few viewport points
     *    and walk up to the first opaque background at each — what the user is actually looking at.
     *    The probe returns the **lightest** surface it found, not the average: that encodes unanimity,
     *    so a light page with one dark hero (or a dark sticky header) is still correctly light, and the
     *    bias is toward applying our filter — the status quo — rather than toward skipping it.
     *  - **`color-scheme`.** With no opaque background declared anywhere, the *UA* paints the canvas,
     *    and under `color-scheme: dark` (or `light dark`, which our forced dark preference resolves to
     *    dark) that canvas is **dark, not white**. The old code assumed "no background ⇒ white", which
     *    inverts every site that ships its dark theme this way.
     *  - **Text colour as a contradiction check.** Near-white body text over an apparently light
     *    background is not a light page — it is a dark page whose surface we failed to sample. Text
     *    that light would be unreadable if the background really were light.
     */
    private val darkDetectJs = """
        var TG_CLS='${FALLBACK_CLASS}';
        function tgLum(c){
          if(!c)return -1;
          var m=c.match(/rgba?\(([^)]+)\)/);if(!m)return -1;
          var p=m[1].split(',');
          var a=p.length>3?parseFloat(p[3]):1;
          if(a<0.5)return -1;
          var r=parseFloat(p[0]),g=parseFloat(p[1]),b=parseFloat(p[2]);
          if(isNaN(r)||isNaN(g)||isNaN(b))return -1;
          return 0.2126*r+0.7152*g+0.0722*b;
        }
        function bgLum(el){if(!el)return -1;try{return tgLum(getComputedStyle(el).backgroundColor);}catch(e){return -1;}}
        function fgLum(el){if(!el)return -1;try{return tgLum(getComputedStyle(el).color);}catch(e){return -1;}}
        /* The LIGHTEST opaque surface under a handful of viewport probes, or -1 if none resolved.
           Lightest (not average) so this only ever reports "dark" when the whole visible page is dark. */
        function paintedLum(){
          try{
            if(!document.body||!document.elementFromPoint)return -1;
            var w=window.innerWidth||0,h=window.innerHeight||0;
            if(w<=0||h<=0)return -1;
            var pts=[[w*0.5,h*0.35],[w*0.5,h*0.65],[w*0.12,h*0.5],[w*0.88,h*0.5],[w*0.5,h*0.9]];
            var worst=-1;
            for(var i=0;i<pts.length;i++){
              var el=document.elementFromPoint(pts[i][0],pts[i][1]),d=0;
              while(el&&d<12){var l=bgLum(el);if(l>=0){if(l>worst)worst=l;break;}el=el.parentElement;d++;}
            }
            return worst;
          }catch(e){return -1;}
        }
        /* True when the page declares a dark color-scheme, i.e. the UA canvas it relies on is dark. */
        function schemeIsDark(){
          try{
            var m=document.querySelector('meta[name="color-scheme"]');
            if(m&&/dark/i.test(m.content||''))return true;
          }catch(e){}
          try{
            var s=getComputedStyle(document.documentElement);
            return /\bdark\b/.test(s.colorScheme||s.getPropertyValue('color-scheme')||'');
          }catch(e){}
          return false;
        }
        function measureDark(deep){
          var declared=bgLum(document.body);
          if(declared<0)declared=bgLum(document.documentElement);
          if(declared>=0&&declared<128)return true;
          var painted=deep?paintedLum():-1;
          if(declared<0)return painted>=0?painted<128:schemeIsDark();
          /* The document background is light. Override it only when two independent signals agree the
             page is really dark — every visible surface AND the body text — so a light page with a
             dark hero filling the probes is still treated as light. */
          var fg=fgLum(document.body);
          if(painted>=0&&painted<100&&fg>=150)return true;
          return fg>=200;
        }
        /* deep=false skips the hit-testing probe — used before the first paint, where there is no
           layout to hit-test and forcing one would be the very cost the pre-paint script avoids.

           Every measurement runs with OUR filter class off. The class carries an opaque
           `background-color:#fafafa` and `color-scheme:dark` on <html>, so once applied it is what
           `getComputedStyle` reports — the detector would be reading its own output back, which is
           why a page that relies on the UA canvas could never be recognised as dark on a re-check.
           Toggling inside one task forces a style recalc but never paints, so there is no flicker. */
        function pageIsDark(deep){
          var root=document.documentElement;
          var had=false;
          try{had=!!(root&&root.classList.contains(TG_CLS));if(had)root.classList.remove(TG_CLS);}catch(e){}
          try{return measureDark(deep);}
          finally{if(had){try{root.classList.add(TG_CLS);}catch(e){}}}
        }
        function isMediaDoc(){
          /* A directly-viewed image/video/audio/PDF is a browser-generated document wrapping a single
             media element. Inverting it turns a photo into a negative (and a PDF into a wrongly-lit
             scan), so the filter must never touch these — the page IS the content, not chrome around
             it. Detected by the document's own content type, with a structural fallback for the
             lone-media-child wrapper Gecko builds. */
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
    """

    /**
     * The apply script as a flattened one-line `javascript:` URL (pure; JVM-testable). Use only block
     * comments — the source is flattened to one line so a `//` would eat the rest. Idempotent: safe to
     * run repeatedly (per load, on the re-check timers).
     *
     * When [aggressive] is true the script additionally runs a **bounded** background-image scanner: a
     * one-shot (capped) `getComputedStyle` walk that tags elements whose computed `background-image` is
     * a `url(...)` with `[data-tangent-dark-bg]` (added to the re-invert selector), plus a debounced,
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
                   tags [data-tangent-dark-keep] (logos/icons/line-art), which must invert WITH the page. */
                var KEEP=':not([data-tangent-dark-keep])';
                var sel=['img','video','picture','canvas','iframe','embed','object','[style*="background-image"]','[style*="background-image" i]','[data-tangent-dark-bg]'];
                var parts=[];for(var i=0;i<sel.length;i++){parts.push('html.'+CLS+' '+sel[i]+KEEP);}
                var media=parts.join(',');
                var css='html.'+CLS+'{filter:invert(1) hue-rotate(180deg) contrast(${ROOT_CONTRAST})!important;background-color:#fafafa!important;color-scheme:dark;}'
                  +media+'{filter:invert(1) hue-rotate(180deg) contrast(${MEDIA_CONTRAST})!important;}';
                var s=document.getElementById(STYLE_ID);
                if(!s){s=document.createElement('style');s.id=STYLE_ID;(document.head||document.documentElement).appendChild(s);}
                if(s.textContent!==css){s.textContent=css;}
              }
              function markKeep(el,keep){try{if(keep)el.setAttribute('data-tangent-dark-keep','1');else el.removeAttribute('data-tangent-dark-keep');}catch(e){}}
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
                if(bi&&bi.indexOf('url(')>=0){el.setAttribute('data-tangent-dark-bg','1');}
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
                if(window.__tangentDarkObserver)return;
                var pending=false;var queue=[];
                function flush(){pending=false;var q=queue;queue=[];for(var i=0;i<q.length;i++){classifyImages(q[i]);if(AGG)scanBg(q[i]);}}
                window.__tangentDarkObserver=new MutationObserver(function(records){
                  for(var i=0;i<records.length;i++){
                    var added=records[i].addedNodes;
                    for(var j=0;j<added.length;j++){if(added[j].nodeType===1)queue.push(added[j]);}
                  }
                  if(queue.length&&!pending){pending=true;requestAnimationFrame(flush);}
                });
                try{window.__tangentDarkObserver.observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
              }
              $darkDetectJs
              function apply(){
                ensureStyle();
                var root=document.documentElement;
                if(!root)return;
                if(pageIsDark(true)||isMediaDoc()){root.classList.remove(CLS);}
                else{
                  root.classList.add(CLS);
                  /* Only pay the image/background scan on pages actually getting the filter. */
                  classifyImages(document);
                  if(AGG)scanBg(document.body);
                  ensureObserver();
                }
              }
              apply();
              /* Re-checks catch sites that swap in a (dark) theme a beat after load, so we remove the
                 fallback once their real dark theme lands. Cheap: a handful of getComputedStyle reads
                 and five hit-tests each. The 3s pass is for slow hydration on heavy app shells, where
                 the surface that carries the dark theme does not exist at 1.2s. */
              setTimeout(apply,300);
              setTimeout(apply,1200);
              setTimeout(apply,3000);
            })();
        """.trimIndent().replace("\n", " ").trim()
    }

    /** Cap on elements the dynamic per-section walk touches per pass — bounds worst-case cost. */
    /**
     * Backstop on how many elements the dynamic tier will ever touch on one page. This is NOT the
     * old per-pass scan cap: work is now frame-budgeted ([DYN_FRAME_BUDGET_MS]) and resumable, so a
     * long page is finished across several frames instead of being truncated. The old fixed cap
     * silently stranded everything past element 8000 — every pass restarted at index 0, so the tail
     * of a long page was *never* darkened no matter how many times the scan re-ran. That was the
     * single biggest "some elements stay light" cause. This value only guards pathological DOMs.
     */
    private const val DYN_MAX_ELEMENTS = 50000

    /** Per-frame work budget (ms) for the resumable walk — keeps a long page from ever blocking a frame. */
    private const val DYN_FRAME_BUDGET_MS = 8

    /**
     * Cap on elements whose `::before`/`::after` we probe. Pseudo-elements can't take an inline
     * style, so each match costs a generated rule in our stylesheet; we only probe elements we
     * already had to recolor (decorative pseudos hang off styled containers), and stop at this many.
     */
    private const val DYN_PSEUDO_CAP = 800

    /** Min box side (px) before a `url()` background is worth scrimming — skips icons and sprites. */
    private const val DYN_BG_IMAGE_MIN = 64

    /**
     * Opacity of the black scrim composited over a light `url()` background image. The recolor can't
     * see *into* an image, so a section whose background is a light photo stays glaring; a scrim
     * layered in the same `background-image` stack darkens it without touching the children drawn on
     * top (a `filter` on the element would drag its text down with it).
     */
    private const val DYN_BG_SCRIM = "0.45"

    /** Dark canvas base for the dynamic tier (mixed pages need an opaque dark floor behind transparency). */
    private const val DYN_BASE_BG = "#1c1c1e"

    /**
     * The **dynamic** per-section recoloring script (pure; JVM-testable). Same one-line-`javascript:`
     * shape and block-comment-only rule as [buildApplyScript].
     *
     * Unlike the filter tiers this applies **no** page-wide filter. It walks the DOM and, per element,
     * reads the computed `background-color`/`color`/border colors, converts each to HSL, and remaps
     * lightness so light colors go dark while already-dark colors stay put (`mapBgL`/`mapFgL` are
     * piecewise, hinged at a threshold below which the input passes through unchanged). Hue is always
     * preserved; extreme saturation is gently compressed. Transparent colors are left untouched so a
     * darkened ancestor shows through. Media (`img`/`video`/`canvas`/…) and SVG-namespaced nodes are
     * skipped entirely — with no inversion, photos and logos are already correct.
     *
     * The light-vs-dark verdict is taken **once** and latched in `window.__lumDynActive`: after the
     * first pass our own darkening would make `getComputedStyle` report a dark page, so a re-decide
     * would wrongly bail. Later `apply()` passes only catch up newly-added nodes.
     *
     * ### The walk (rewritten for coverage + cost)
     * - **Resumable, frame-budgeted** ([DYN_FRAME_BUDGET_MS], backstopped by [DYN_MAX_ELEMENTS])
     *   over static `querySelectorAll` lists. The previous fixed per-pass cap restarted at index 0
     *   every time, so everything past element 8000 was *never* reached — the main reason long pages
     *   kept light patches. Now a chunk stops on the budget and the next frame resumes at the same
     *   index until the page is finished.
     * - **Read/write split.** `visit()` only reads and appends (element, property, value) triples;
     *   `flush()` applies them after the chunk. Interleaving reads and writes forced a style recalc
     *   per element — this is the biggest single cost reduction in the tier.
     * - **Color memoization.** `modify()` caches by `kind|color`; a page has dozens of distinct
     *   colors across thousands of elements.
     * - **Inherited text color is skipped.** A child whose computed `color` equals its parent's
     *   original inherits our remapped value anyway, so writing it again is wasted work and a wasted
     *   inline style.
     * - **Shadow roots and same-origin iframes** are adopted as their own walks + observers; open
     *   shadow trees are invisible to `querySelectorAll`, so component-heavy sites used to keep whole
     *   light subtrees.
     * - **Attribute mutations** (`class`/`style`) clear the per-element done latch and re-queue it.
     *   Without this a site that swaps a class after load kept our stale recolor forever. Our own
     *   writes are counted (`__lumDynPend`) so the observer can tell them from the page's and not
     *   loop.
     * - **`::before`/`::after`** can't take an inline style, so matching pseudos get a generated rule
     *   in our stylesheet, keyed by a tagging attribute, capped at [DYN_PSEUDO_CAP].
     * - **`url()` backgrounds** get a [DYN_BG_SCRIM] scrim composited into the same background stack
     *   (children drawn on top are unaffected), for boxes at least [DYN_BG_IMAGE_MIN] on both sides.
     *
     * Every write records the element's prior inline value for an exact teardown — see
     * [buildRemoveScript].
     */
    internal fun buildDynamicScript(): String {
        return """
            javascript:(function(){
              var STYLE_ID='${DYN_STYLE_ID}';
              var CLS='${DYN_CLASS}';
              var BASE_BG='${DYN_BASE_BG}';
              var MAXEL=${DYN_MAX_ELEMENTS};
              var BUDGET=${DYN_FRAME_BUDGET_MS};
              var PSEUDO_CAP=${DYN_PSEUDO_CAP};
              var BGMIN=${DYN_BG_IMAGE_MIN};
              var SCRIM='${DYN_BG_SCRIM}';
              var DONE='__lumDynDone';
              var ORIG='__lumDynOrig';
              var PEND='__lumDynPend';
              var OFG='__lumDynOfg';
              /* All mutable state hangs off one object so the teardown script can find exactly what
                 we touched (including inside shadow roots and iframes) and undo precisely that. */
              var st=window.__lumDyn;
              if(!st)st=window.__lumDyn={q:[],touched:[],cache:{},count:0,pseudo:0,pseudoSeq:0,
                rules:[],rulesDirty:0,pending:0,obs:[],base:''};
              /* The document_start pre-paint extension (darkmode.js) always applies the FILTER tier
                 (invert) to kill the white flash, before it knows the user picked the dynamic tier.
                 Strip that filter here so the page isn't both inverted AND recolored. */
              try{document.documentElement.classList.remove('${FALLBACK_CLASS}');}catch(e){}
              try{var _fs=document.getElementById('${STYLE_ID}');if(_fs&&_fs.parentNode)_fs.parentNode.removeChild(_fs);}catch(e){}
              function baseStyle(){
                var css='html.'+CLS+'{background-color:'+BASE_BG+' !important;color-scheme:dark;}'
                  +'html.'+CLS+' img,html.'+CLS+' video,html.'+CLS+' canvas,html.'+CLS+' picture,html.'+CLS+' iframe,html.'+CLS+' embed,html.'+CLS+' object{filter:none !important;}';
                st.base=css;
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
              function modifyRaw(c,kind){
                var p=parseColor(c);if(!p)return null;
                if(p[3]===0)return null;
                var hsl=rgbToHsl(p[0],p[1],p[2]);
                var nl=kind===0?mapBgL(hsl[2]):(kind===1?mapFgL(hsl[2]):mapBorderL(hsl[2]));
                var ns=hsl[1];if(ns>0.6)ns=0.6+(ns-0.6)*0.5;
                var rgb=hslToRgb(hsl[0],ns,nl);
                if(p[3]<1)return 'rgba('+rgb[0]+','+rgb[1]+','+rgb[2]+','+p[3]+')';
                return 'rgb('+rgb[0]+','+rgb[1]+','+rgb[2]+')';
              }
              /* A page uses dozens of distinct colors across thousands of elements, so the parse +
                 HSL round-trip is almost entirely repeat work. Memoizing by color string collapses
                 it to one computation per distinct color — this is the part a WASM port would have
                 targeted, and caching removes far more of it than a port could. */
              function modify(c,kind){
                if(!c)return null;
                var k=kind+'|'+c,v=st.cache[k];
                if(v!==undefined)return v;
                v=modifyRaw(c,kind);
                st.cache[k]=v;
                return v;
              }
              function isMedia(t){return t==='IMG'||t==='VIDEO'||t==='CANVAS'||t==='IFRAME'||t==='EMBED'||t==='OBJECT'||t==='PICTURE'||t==='SVG';}
              /* READ phase only: never writes. Every change is pushed onto `ops` as an
                 (element, property, value) triple and applied by flush() after the whole chunk has
                 been read, so a style write can't force the next element's read to recalc. */
              function visit(el,ops){
                if(!el||el.nodeType!==1||el[DONE])return;
                el[DONE]=1;
                if(st.count>=MAXEL)return;
                st.count++;
                var tag=el.tagName;
                if(el.shadowRoot)adoptRoot(el.shadowRoot);
                if(tag==='IFRAME'){adoptFrame(el);return;}
                if(isMedia(tag))return;
                /* Skip anything in the SVG namespace (icons/logos) — recoloring their fills mangles art. */
                if(el.namespaceURI&&el.namespaceURI.indexOf('svg')>=0)return;
                var cs;try{cs=getComputedStyle(el);}catch(e){return;}
                var wrote=0;
                var nb=modify(cs.backgroundColor,0);
                if(nb){ops.push(el,'background-color',nb);wrote=1;}
                /* Text color is inherited, so a child whose computed color equals its parent's
                   ORIGINAL color will inherit our remapped value for free. Skipping those writes
                   removes most of the inline styles this tier used to create on text-heavy pages. */
                var fg=cs.color,pe=el.parentElement;
                var inherited=pe&&pe[OFG]===fg;
                el[OFG]=fg;
                if(!inherited){var nf=modify(fg,1);if(nf){ops.push(el,'color',nf);wrote=1;}}
                if(cs.borderTopWidth!=='0px'||cs.borderBottomWidth!=='0px'||cs.borderLeftWidth!=='0px'||cs.borderRightWidth!=='0px'){
                  var sides=['borderTopColor','borderRightColor','borderBottomColor','borderLeftColor'];
                  var props=['border-top-color','border-right-color','border-bottom-color','border-left-color'];
                  for(var k=0;k<4;k++){var nc=modify(cs[sides[k]],2);if(nc){ops.push(el,props[k],nc);wrote=1;}}
                }
                var bi=cs.backgroundImage;
                if(bi&&bi!=='none'){
                  if(bi.indexOf('url(')>=0){
                    /* Can't recolor into an image, so composite a scrim over it in the same
                       background stack — children drawn on top are unaffected. */
                    if(el.offsetWidth>=BGMIN&&el.offsetHeight>=BGMIN){
                      ops.push(el,'background-image','linear-gradient(rgba(0,0,0,'+SCRIM+'),rgba(0,0,0,'+SCRIM+')),'+bi);
                      wrote=1;
                    }
                  }else if(bi.indexOf('gradient')>=0){
                    var ng=bi.replace(/rgba?\([^)]+\)/g,function(m){var r=modify(m,0);return r||m;});
                    if(ng!==bi){ops.push(el,'background-image',ng);wrote=1;}
                  }
                }
                if(wrote&&st.pseudo<PSEUDO_CAP){pseudo(el,'::before');pseudo(el,'::after');}
              }
              /* Enqueue a subtree. querySelectorAll returns a STATIC list, which is what makes the
                 walk resumable: pump() can stop mid-list on a frame budget and pick up at the same
                 index next frame instead of restarting (the old fixed cap restarted at 0 every pass,
                 so a long page's tail was never reached). */
              function pushTree(n){
                if(!n||n.nodeType!==1)return;
                st.q.push({l:[n],i:0});
                try{var d=n.querySelectorAll('*');if(d.length)st.q.push({l:d,i:0});}catch(e){}
              }
              function schedule(){if(st.pending)return;st.pending=1;requestAnimationFrame(pump);}
              /* Read every element's computed style first, then write in one batch. Interleaving the
                 two thrashes layout: each style write invalidates, so the next getComputedStyle forces
                 a fresh recalc. Splitting the phases is the single biggest win in this walk. */
              function pump(){
                st.pending=0;
                var t0=Date.now(),ops=[],n=0,over=0;
                while(st.q.length&&!over){
                  var e=st.q[0];
                  while(e.i<e.l.length){
                    visit(e.l[e.i++],ops);
                    if((++n&127)===0&&Date.now()-t0>BUDGET){over=1;break;}
                  }
                  if(e.i>=e.l.length)st.q.shift();
                }
                flush(ops);
                flushRules();
                if(st.q.length)schedule();
              }
              function record(el,p){
                var o=el[ORIG];
                if(!o){o=el[ORIG]={};st.touched.push(el);}
                /* Remember the page's OWN inline value once, so turning dark mode off restores it
                   exactly instead of blanket-removing the property (which used to wipe colors the
                   site itself set inline, leaving the page wrong until a reload). */
                if(!(p in o))o[p]=[el.style.getPropertyValue(p),el.style.getPropertyPriority(p)];
              }
              function flush(ops){
                for(var i=0;i<ops.length;i+=3){
                  var el=ops[i];
                  try{
                    record(el,ops[i+1]);
                    /* Count our own style mutations so the observer can tell them from the page's. */
                    el[PEND]=(el[PEND]||0)+1;
                    el.style.setProperty(ops[i+1],ops[i+2],'important');
                  }catch(e){}
                }
              }
              function flushRules(){
                if(!st.rulesDirty)return;
                st.rulesDirty=0;
                var s=document.getElementById(STYLE_ID);
                if(s)s.textContent=st.base+st.rules.join('');
              }
              /* Pseudo-elements can't take an inline style, so a light ::before/::after survived the
                 whole recolor. Probe only elements we already recolored (decorative pseudos hang off
                 styled containers) and emit a generated rule keyed by a tagging attribute. */
              function pseudo(el,which){
                var cs;try{cs=getComputedStyle(el,which);}catch(e){return;}
                var c=cs.content;
                if(!c||c==='none'||c==='normal')return;
                var nb=modify(cs.backgroundColor,0),nf=modify(cs.color,1);
                if(!nb&&!nf)return;
                st.pseudo++;
                var id=el.getAttribute('data-tangent-dyn-p');
                if(!id){id='p'+(++st.pseudoSeq);try{el.setAttribute('data-tangent-dyn-p',id);}catch(e){return;}}
                var d='';
                if(nb)d+='background-color:'+nb+' !important;';
                if(nf)d+='color:'+nf+' !important;';
                st.rules.push('[data-tangent-dyn-p="'+id+'"]'+which+'{'+d+'}');
                st.rulesDirty=1;
              }
              /* Open shadow roots are invisible to querySelectorAll, so whole web-component subtrees
                 used to stay light. Adopt each root as its own walk + observer. */
              function adoptRoot(r){
                if(!r||r.__lumDynAdopted)return;
                r.__lumDynAdopted=1;
                try{var k=r.children;for(var i=0;i<k.length;i++)pushTree(k[i]);}catch(e){}
                observe(r);
                schedule();
              }
              /* Same-origin iframes are separate documents; cross-origin ones throw and are skipped. */
              function adoptFrame(el){
                try{
                  var d=el.contentDocument;
                  if(d&&d.documentElement&&!d.__lumDynAdopted){
                    d.__lumDynAdopted=1;
                    pushTree(d.documentElement);
                    observe(d);
                    schedule();
                  }
                }catch(e){}
                if(!el.__lumDynFrameHook){
                  el.__lumDynFrameHook=1;
                  try{el.addEventListener('load',function(){try{el.contentDocument.__lumDynAdopted=0;}catch(e2){}adoptFrame(el);});}catch(e){}
                }
              }
              function observe(target){
                try{
                  var o=new MutationObserver(onMut);
                  o.observe(target.nodeType===9?target.documentElement:target,
                    {childList:true,subtree:true,attributes:true,attributeFilter:['class','style']});
                  st.obs.push(o);
                  if(!window.__tangentDynObserver)window.__tangentDynObserver=o;
                }catch(e){}
              }
              /* Attribute mutations matter as much as added nodes: a site that swaps a class (theme
                 switch, a lazy `.loaded` class, hover state) used to keep our stale recolor forever,
                 because every element was latched done on first visit and never re-read. */
              function onMut(recs){
                for(var i=0;i<recs.length;i++){
                  var r=recs[i],t=r.target;
                  if(r.type==='attributes'){
                    if(r.attributeName==='style'&&t[PEND]>0){t[PEND]--;continue;}
                    t[DONE]=0;
                    st.q.push({l:[t],i:0});
                    continue;
                  }
                  var a=r.addedNodes;
                  for(var j=0;j<a.length;j++){if(a[j].nodeType===1){a[j][DONE]=0;pushTree(a[j]);}}
                }
                schedule();
              }
              $darkDetectJs
              function apply(){
                var root=document.documentElement;if(!root)return;
                /* Already engaged: only catch up newly-added nodes. Re-deciding here would be wrong —
                   our own recoloring has since darkened the page. */
                if(window.__lumDynActive){pushTree(document.body||root);schedule();return;}
                if(pageIsDark(true)||isMediaDoc())return;
                window.__lumDynActive=1;
                baseStyle();
                root.classList.add(CLS);
                /* Walk from documentElement, not body: html-level backgrounds count too. */
                pushTree(root);
                observe(document);
                schedule();
              }
              apply();
              setTimeout(apply,300);
              setTimeout(apply,1200);
              /* Late stylesheets change what getComputedStyle reports; re-run once the page settles
                 so elements read before their CSS arrived are not left latched with default colors. */
              try{if(document.readyState!=='complete')window.addEventListener('load',function(){apply();},{once:true});}catch(e){}
            })();
        """.trimIndent().replace("\n", " ").trim()
    }

    /* ---- WASM image-cutout tuning (see tools/dark-cutout-wasm; kernel = src/lib.rs) ---- */
    /** Min on-screen side (px) an image must have before it's worth a cutout pass (skips icons). */
    private const val CUT_MIN_SIZE = 100
    /** Max long side the image is downscaled to before keying — bounds getImageData + kernel cost.
     *  Matches the kernel's 1536px arena so full-bleed hero/product images key at native 2×-DPR
     *  resolution instead of a soft 768px downscale that looked pixelated on large slots. */
    private const val CUT_MAX_DIM = 1536
    /** Max long side for the **main-thread** fallback (worker-blocked CSP), smaller than [CUT_MAX_DIM]
     *  so the synchronous flood fill on the UI thread stays a short task; the mask is upscaled anyway. */
    private const val CUT_MAX_DIM_MAIN = 768
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
        val params = "{minSize:$CUT_MIN_SIZE,maxDim:$CUT_MAX_DIM,maxDimMain:$CUT_MAX_DIM_MAIN,lSeed:$CUT_L_SEED,lFloor:$CUT_L_FLOOR," +
            "stepMax:$CUT_STEP_MAX,minAlpha:$CUT_MIN_ALPHA,colorBand:$CUT_COLOR_BAND," +
            "lEdge:$CUT_L_EDGE,defringeIters:$CUT_DEFRINGE_ITERS," +
            "minFrac:$CUT_MIN_FRAC,maxFrac:$CUT_MAX_FRAC,mlReady:$mlReady,bridge:\"$bridge\"}"
        return "javascript:(function(){${DarkCutoutAssets.DRIVER_JS} try{lumCutSetup(" +
            "\"${DarkCutoutAssets.WASM_B64}\",\"${DarkCutoutAssets.WORKER_B64}\",$params);}catch(e){}})();"
    }

    /**
     * A self-contained `javascript:` that applies a finished alpha mask (a `data:image/png` URI) to the
     * `<img>` the ML path tagged `data-tangent-cut-id="[id]"`. Injected by the host when
     * [DarkImageSegmenter] returns a segmentation result. Sizes/positions the mask to the displayed
     * content rect so `object-fit` still lines up — same geometry as the driver's WASM applyMask.
     * Self-contained (doesn't depend on the driver still being present) and fail-safe.
     */
    internal fun buildApplyMaskScript(id: String, maskDataUri: String, natW: Int, natH: Int): String {
        return """
            javascript:(function(){
              try{
                var el=document.querySelector('[data-tangent-cut-id="$id"]');
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
     * dynamic tier (class + `<style>` + observers + the per-element inline recolors it set).
     *
     * The dynamic teardown is **exact**: the walk records each element's original inline value the
     * first time it overwrites a property (`__lumDynOrig`) and registers the element in
     * `window.__lumDyn.touched`, so this restores precisely what we changed and nothing else. The
     * old version blanket-removed `background-color`/`color`/border/`background-image` from every
     * element in the document, which also wiped colors the *page* had set inline — the page stayed
     * visibly wrong until a reload, which is what made dark mode look like it couldn't be turned off
     * live. Working from the touched list also reaches elements inside shadow roots and same-origin
     * iframes, which a `document.getElementsByTagName('*')` sweep never saw.
     *
     * The brute-force sweep survives only as a fallback for a page that was injected by an older
     * build (no `__lumDyn` state to consult).
     */
    internal fun buildRemoveScript(): String {
        return """
            javascript:(function(){
              try{document.documentElement.classList.remove('${FALLBACK_CLASS}');}catch(e){}
              try{var s=document.getElementById('${STYLE_ID}');if(s&&s.parentNode)s.parentNode.removeChild(s);}catch(e){}
              try{if(window.__tangentDarkObserver){window.__tangentDarkObserver.disconnect();window.__tangentDarkObserver=null;}}catch(e){}
              try{document.documentElement.classList.remove('${DYN_CLASS}');}catch(e){}
              try{var ds=document.getElementById('${DYN_STYLE_ID}');if(ds&&ds.parentNode)ds.parentNode.removeChild(ds);}catch(e){}
              try{if(window.__tangentDynObserver){window.__tangentDynObserver.disconnect();window.__tangentDynObserver=null;}}catch(e){}
              /* Tear down the image-cutout worker and observer. */
              try{if(window.__lumCut&&window.__lumCut.worker)window.__lumCut.worker.terminate();window.__lumCut=null;}catch(e){}
              try{if(window.__lumCutObserver){window.__lumCutObserver.disconnect();window.__lumCutObserver=null;}}catch(e){}
              window.__lumDynActive=0;
              var MASKS=['mask-image','-webkit-mask-image','mask-size','-webkit-mask-size','mask-position','-webkit-mask-position','mask-repeat','-webkit-mask-repeat','mask-mode'];
              /* Drop the cutout's CSS mask, but only from images the cutout actually tagged, so a
                 page that uses masks of its own keeps them. */
              try{var im=document.getElementsByTagName('img');for(var q=0;q<im.length;q++){if(!im[q].__lumCut)continue;var ms=im[q].style;for(var w=0;w<MASKS.length;w++)ms.removeProperty(MASKS[w]);im[q].__lumCut=0;try{im[q].removeAttribute('data-tangent-cut-id');}catch(e2){}}}catch(e){}
              var dyn=window.__lumDyn;
              if(dyn){
                try{for(var o=0;o<dyn.obs.length;o++){try{dyn.obs[o].disconnect();}catch(e2){}}}catch(e){}
                /* Exact restore: put back the page's own inline value, or remove the property if it
                   had none. Covers shadow-root and iframe elements, which a document-wide sweep misses. */
                try{
                  var t=dyn.touched;
                  for(var i=0;i<t.length;i++){
                    var el=t[i],orig=el.__lumDynOrig;
                    if(orig){
                      for(var p in orig){
                        var v=orig[p];
                        try{if(v&&v[0])el.style.setProperty(p,v[0],v[1]);else el.style.removeProperty(p);}catch(e2){}
                      }
                    }
                    el.__lumDynOrig=null;el.__lumDynDone=0;el.__lumDynPend=0;el.__lumDynOfg=null;
                    try{el.removeAttribute('data-tangent-dyn-p');}catch(e2){}
                  }
                }catch(e){}
                window.__lumDyn=null;
              }else{
                /* Legacy fallback: page was injected by an older build with no recorded originals. */
                try{var el2=document.getElementsByTagName('*');for(var i2=0;i2<el2.length;i2++){var s2=el2[i2].style;if(s2){s2.removeProperty('background-color');s2.removeProperty('color');s2.removeProperty('border-top-color');s2.removeProperty('border-right-color');s2.removeProperty('border-bottom-color');s2.removeProperty('border-left-color');s2.removeProperty('background-image');for(var w2=0;w2<MASKS.length;w2++)s2.removeProperty(MASKS[w2]);}el2[i2].__lumDynDone=0;el2[i2].__lumCut=0;}}catch(e){}
              }
            })();
        """.trimIndent().replace("\n", " ")
    }
}
