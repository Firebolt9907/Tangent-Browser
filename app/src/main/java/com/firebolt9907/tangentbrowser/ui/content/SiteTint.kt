package com.firebolt9907.tangentbrowser.ui.content

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import mozilla.components.concept.engine.EngineSession

/**
 * The active system-tint color, or null when the "Tint pages to my system color" boost is off.
 * Provided once near the root of the browser UI so every place that draws a page snapshot (grid /
 * deck cards, tab-switch covers, the scaled page backdrop) can overlay the tint at draw time via
 * [siteTintOverlay] without threading a color through each signature. Tracks the toggle live, so
 * snapshots never disagree with the live page and nothing needs to be re-captured.
 */
internal val LocalSiteTint = compositionLocalOf<Color?> { null }

/**
 * Strength of the system-tint boost, shared by the live-page DOM overlay ([buildSiteTintScript], as
 * `opacity`), the snapshot draw-time overlay ([siteTintOverlay], as the blend layer's alpha), and
 * the solid-color flatten ([tintedForSite]) so the three never drift. A `color` blend at full
 * strength hard-recolors the page; scaling it down turns the hue/saturation swap into a gentle wash.
 * See [buildSiteTintScript] for the blend model.
 */
internal const val SiteTintOpacity = 0.45f

/**
 * Flattens the system-tint boost onto a single solid [this] color — the same `color` blend the page
 * overlay applies, but for one color rather than a whole layer. Used to tint GeckoView's compositor
 * clear color (the fill shown in not-yet-painted regions during a fast fling) so those flashes match
 * the tinted page instead of the raw, brighter backdrop. Returns the receiver unchanged when the
 * boost is off ([tint] null).
 *
 * Implements the W3C non-separable `color` blend: the source's (tint) hue+saturation with the
 * backdrop's (this) luminosity, mixed at [SiteTintOpacity]. This is the flat-color equivalent of the
 * `mix-blend-mode: color` the DOM overlay and [siteTintOverlay] use.
 */
internal fun Color.tintedForSite(tint: Color?): Color {
    if (tint == null) return this
    return lerp(this, tint.withLuminosityOf(this), SiteTintOpacity)
}

/** `SetLum(source, Lum(backdrop))` from the W3C compositing spec, operating on linear-ish sRGB. */
private fun Color.withLuminosityOf(backdrop: Color): Color {
    val target = backdrop.blendLum()
    val delta = target - blendLum()
    return clipToGamut(red + delta, green + delta, blue + delta, target, alpha)
}

private fun Color.blendLum(): Float = 0.3f * red + 0.59f * green + 0.11f * blue

/** `ClipColor` from the spec: pulls any out-of-[0,1] channel back toward the luminosity [l]. */
private fun clipToGamut(r: Float, g: Float, b: Float, l: Float, alpha: Float): Color {
    var rr = r; var gg = g; var bb = b
    val n = minOf(rr, gg, bb)
    val x = maxOf(rr, gg, bb)
    if (n < 0f && l - n != 0f) {
        rr = l + (rr - l) * l / (l - n); gg = l + (gg - l) * l / (l - n); bb = l + (bb - l) * l / (l - n)
    }
    if (x > 1f && x - l != 0f) {
        rr = l + (rr - l) * (1f - l) / (x - l); gg = l + (gg - l) * (1f - l) / (x - l); bb = l + (bb - l) * (1f - l) / (x - l)
    }
    return Color(rr.coerceIn(0f, 1f), gg.coerceIn(0f, 1f), bb.coerceIn(0f, 1f), alpha)
}

/**
 * The "Tint pages to my system color" boost (Arc-style). Overlays a full-viewport, non-interactive
 * `<div>` filled with the user's system accent color and composites it with `mix-blend-mode: color`,
 * which replaces every pixel's hue + saturation with the accent's while preserving its lightness.
 *
 * The lightness preservation is what keeps pages readable: pure white backgrounds stay white and
 * black/near-black text stays dark (lightness 0/1 are colorless under `color` blend), while colored
 * chrome — buttons, links, headers, images — takes on the system hue. The effect reads as "this site
 * now matches my accent" without the contrast damage a flat opaque tint would cause.
 *
 * Intensity is dialed by the overlay's `opacity` (0.45): with a blend mode, lowering the layer's
 * opacity proportionally reduces how much of the hue/saturation swap composites through, so a
 * full-strength `color` blend reads as a gentle wash rather than a hard recolor.
 *
 * Injected exactly like the viewport spacers (via [loadJavascriptUrl]); AGENTS.md invariant #11
 * applies at the call site (set `suppressJavascriptLoadCallback` when injecting from a load callback).
 */
internal fun EngineSession.injectSiteTint(color: Color) {
    loadJavascriptUrl(buildSiteTintScript(color.toCssHex()))
}

/** Removes the boost overlay and its self-heal observer (used when the option is toggled off). */
internal fun EngineSession.removeSiteTint() {
    loadJavascriptUrl(buildSiteTintRemovalScript())
}

/**
 * Overlays the same tint the live page shows onto a snapshot bitmap at draw time, so tab-overview
 * cards and the tab-switch covers match what the page looks like. Snapshot bitmaps are the raw,
 * untinted page (`EngineView.captureThumbnail` reads Gecko's compositor output, which omits our DOM
 * overlay); applying the tint here rather than baking it in means it tracks the toggle live and
 * never goes stale in the cache.
 *
 * Uses [BlendMode.Color] — the exact equivalent of CSS `mix-blend-mode: color`: the source's
 * hue+saturation with the destination's luminosity. The overlay draws at [SiteTintOpacity],
 * mirroring the DOM overlay's `opacity`. An offscreen compositing layer isolates the blend to the
 * decorated content (the snapshot) so it doesn't sample whatever is painted behind the card. A null
 * [color] (boost off) leaves the content untouched.
 */
internal fun Modifier.siteTintOverlay(color: Color?): Modifier {
    if (color == null) return this
    val tint = color.copy(alpha = SiteTintOpacity)
    return this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(color = tint, blendMode = BlendMode.Color)
        }
}

/** ARGB [Color] → `#rrggbb` (alpha dropped; the overlay's strength comes from the blend, not alpha). */
internal fun Color.toCssHex(): String {
    val argb = toArgb()
    return String.format("#%06X", 0xFFFFFF and argb)
}

/**
 * Builds the boost-overlay `javascript:` URL as a flattened one-line string (pure; JVM-testable).
 * Creates/updates a single `#tangent-site-tint` fixed overlay appended last to `<body>` so it paints
 * over page content, and re-appends it on a bounded MutationObserver for SPAs that rebuild `<body>`.
 * Use only block comments — the source is flattened to one line so a `//` would eat the remainder.
 */
internal fun buildSiteTintScript(colorCss: String): String {
    return """
        javascript:(()=> {
        const id='tangent-site-tint';
        const color='${colorCss}';
        function ensure(){const host=document.body||document.documentElement;if(!host)return;let el=document.getElementById(id);if(!el){el=document.createElement('div');el.id=id;el.setAttribute('aria-hidden','true');el.dataset.tangentIgnoreSafeTop='true';}el.style.cssText='position:fixed!important;left:0!important;top:0!important;right:0!important;bottom:0!important;width:100vw!important;height:100vh!important;margin:0!important;padding:0!important;border:0!important;pointer-events:none!important;z-index:2147483647!important;background:'+color+'!important;mix-blend-mode:color!important;opacity:${SiteTintOpacity}!important;';if(host.lastElementChild!==el)host.appendChild(el);}
        ensure();
        setTimeout(ensure,500);setTimeout(ensure,2000);
        function schedule(){if(window.__tangentTintPending)return;window.__tangentTintPending=true;requestAnimationFrame(function(){window.__tangentTintPending=false;ensure();});}
        if(window.__tangentTintObserver){window.__tangentTintObserver.disconnect();window.__tangentTintObserver=null;}
        window.__tangentTintObserver=new MutationObserver(function(records){const allOurs=records.every(function(r){return r.target&&r.target.id===id});if(allOurs)return;schedule();});
        try{window.__tangentTintObserver.observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
        })()
    """.trimIndent().replace("\n", "")
}

/** Builds the removal `javascript:` URL: drops the overlay node and disconnects its observer. */
internal fun buildSiteTintRemovalScript(): String {
    return """
        javascript:(()=> {
        try{var el=document.getElementById('tangent-site-tint');if(el&&el.parentNode)el.parentNode.removeChild(el);}catch(e){}
        try{if(window.__tangentTintObserver){window.__tangentTintObserver.disconnect();window.__tangentTintObserver=null;}}catch(e){}
        })()
    """.trimIndent().replace("\n", "")
}
