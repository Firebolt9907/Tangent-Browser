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
 * per-element analysis this design deliberately avoids. Modern sites that have this problem almost
 * always also ship a real dark theme, so Tier 1 covers them and the fallback never engages.
 *
 * Injected exactly like the viewport spacers / [SiteTint] (via [loadJavascriptUrl]); AGENTS.md
 * invariant #11 applies at the call site (set `suppressJavascriptLoadCallback` when injecting from a
 * load callback). One-way only — posts nothing back over the prompt bridge, so it needs no
 * [BridgeGuard] nonce.
 */
object WebDarkMode {

    private const val STYLE_ID = "lumina-dark-style"
    private const val FALLBACK_CLASS = "lumina-dark-fallback"

    /** Applies (or refreshes) the fallback tier on the current page. */
    fun EngineSession.injectWebDarkMode() {
        loadJavascriptUrl(buildApplyScript())
    }

    /** Removes the fallback tier's style + class (used when dark mode is toggled off). */
    fun EngineSession.removeWebDarkMode() {
        loadJavascriptUrl(buildRemoveScript())
    }

    /**
     * The apply script as a flattened one-line `javascript:` URL (pure; JVM-testable). Use only block
     * comments — the source is flattened to one line so a `//` would eat the rest. Idempotent: safe to
     * run repeatedly (per load, on the re-check timers).
     */
    internal fun buildApplyScript(): String {
        return """
            javascript:(function(){
              var STYLE_ID='${STYLE_ID}';
              var CLS='${FALLBACK_CLASS}';
              function ensureStyle(){
                var css='html.'+CLS+'{filter:invert(1) hue-rotate(180deg)!important;background-color:#fafafa!important;color-scheme:dark;}'
                  +'html.'+CLS+' img,html.'+CLS+' video,html.'+CLS+' picture,html.'+CLS+' canvas,html.'+CLS+' iframe,html.'+CLS+' embed,html.'+CLS+' object,html.'+CLS+' [style*="background-image"],html.'+CLS+' [style*="background-image" i]{filter:invert(1) hue-rotate(180deg)!important;}';
                var s=document.getElementById(STYLE_ID);
                if(!s){s=document.createElement('style');s.id=STYLE_ID;(document.head||document.documentElement).appendChild(s);}
                if(s.textContent!==css){s.textContent=css;}
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
              function apply(){
                ensureStyle();
                var root=document.documentElement;
                if(!root)return;
                if(pageIsDark()){root.classList.remove(CLS);}
                else{root.classList.add(CLS);}
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
            })();
        """.trimIndent().replace("\n", " ")
    }
}
