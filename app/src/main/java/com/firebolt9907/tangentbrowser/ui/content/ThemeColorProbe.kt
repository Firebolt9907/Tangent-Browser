package com.firebolt9907.tangentbrowser.ui.content

import android.graphics.Color as AndroidColor
import android.util.Log

/**
 * Extracts a page's declared theme color — the same `<meta name="theme-color">` tag Safari samples
 * to tint its status bar — and hands it back to native so the per-site top-padding strip / status
 * bar can be painted that color. When the page declares no theme color, [onPayload] reports `null`
 * and callers fall back to the system primary color.
 *
 * ## How it works (the `prompt()` bridge, same channel the reader/video/link-rect probes use)
 * GeckoView offers only one-way `javascript:` injection with no return value, so — exactly like
 * [com.firebolt9907.tangentbrowser.ui.reader.ReaderExtractor] and [LinkRectProbe] — the script
 * smuggles its result back through a native `window.prompt(message, payload)` taken from a throwaway
 * same-origin iframe (a pristine realm the page can't have patched). The wrapping
 * `ReaderPromptDelegate` intercepts `tangent-themecolor` and calls [onPayload] with the tab id it was
 * constructed for plus the `"<themeColor>|<bgColor>|<url>"` payload.
 *
 * The script honours the meta tag's `media` attribute (e.g. a separate dark-mode theme-color) via
 * `matchMedia`, and re-samples when the color scheme changes, so the tint tracks the page — again
 * matching Safari.
 */
object ThemeColorProbe {

    private const val TAG = "ThemeColorProbe"
    const val BRIDGE_MESSAGE = "tangent-themecolor"

    /**
     * Sink for extracted colors, registered by `TangentRoot` while it is composed. Global (like the
     * video / link-rect bridges) because only one browser activity is ever foreground at a time.
     *
     * - `themeColor` is the page's declared `<meta name="theme-color">` as an opaque ARGB int, or
     *   `null` when the page declares none. Drives the exposed top-strip fill and the "match website
     *   theme color" chrome accent.
     * - `bgColor` is the *actual* rendered background color sampled from the element behind the status
     *   bar (top-center of the viewport), or `null` when it could not be resolved. Drives the
     *   status-bar icon light/dark choice so it tracks the real content even on sites that declare no
     *   theme color, and re-fires on scroll so a colored header scrolling away flips the icons.
     */
    @Volatile
    var listener: ((tabId: String, url: String, themeColor: Int?, bgColor: Int?) -> Unit)? = null

    /** Called by the prompt delegate with the raw `"<themeColor>|<bgColor>|<url>"` payload for [tabId]. */
    fun onPayload(tabId: String, payload: String) {
        val cb = listener ?: return
        val first = payload.indexOf('|')
        if (first < 0) {
            cb(tabId, "", parseColor(payload), null)
            return
        }
        val second = payload.indexOf('|', first + 1)
        if (second < 0) {
            // Legacy single-color payload: treat the tail as the URL, no background sample.
            cb(tabId, payload.substring(first + 1), parseColor(payload.substring(0, first)), null)
            return
        }
        val themeStr = payload.substring(0, first)
        val bgStr = payload.substring(first + 1, second)
        val url = payload.substring(second + 1)
        cb(tabId, url, parseColor(themeStr), parseColor(bgStr))
    }

    /** Parses the JS computed color (`rgb(r, g, b)` / `rgba(r, g, b, a)`) to an opaque ARGB int. */
    internal fun parseColor(raw: String): Int? {
        val s = raw.trim()
        if (s.isEmpty() || s == "none") return null
        return try {
            val open = s.indexOf('(')
            val close = s.indexOf(')')
            if (open < 0 || close < 0) return null
            val parts = s.substring(open + 1, close).split(',').map { it.trim() }
            if (parts.size < 3) return null
            val r = parts[0].toFloat().toInt().coerceIn(0, 255)
            val g = parts[1].toFloat().toInt().coerceIn(0, 255)
            val b = parts[2].toFloat().toInt().coerceIn(0, 255)
            // A fully transparent theme-color is not a real declaration — treat as absent.
            val a = if (parts.size >= 4) parts[3].toFloat() else 1f
            if (a <= 0.01f) return null
            AndroidColor.rgb(r, g, b)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse theme-color payload")
            null
        }
    }

    /**
     * Builds the `javascript:` probe. Flattened to one line before injection — use only `/* */`
     * comments. Reads the applicable `<meta name="theme-color">` (honouring its `media` query),
     * resolves it to a computed color via a throwaway element, AND samples the actual rendered
     * background color of the element behind the status bar (top-center of the viewport), then posts
     * `"<themeColor>|<bgColor>|<url>"` back over the prompt bridge. The background sample lets the
     * status-bar icons track real content even on sites that declare no theme color. Re-samples once
     * on the next color-scheme change so a dark/light switch re-tints without a reload; no scroll
     * listener (intentionally one-shot per load — the icons match the page's base background).
     */
    fun buildScript(): String {
        return """
            javascript:(function() {
                function tangentBridge(msg, val) {
                    var ifr = null;
                    try {
                        ifr = document.createElement('iframe');
                        ifr.setAttribute('aria-hidden', 'true');
                        ifr.style.cssText = 'display:none!important;width:0;height:0;border:0;position:absolute;left:-9999px;';
                        (document.body || document.documentElement).appendChild(ifr);
                        var w = ifr.contentWindow;
                        if (w && typeof w.prompt === 'function') {
                            w.prompt(msg, val);
                        } else {
                            window.prompt(msg, val);
                        }
                    } catch (e) {
                        try { window.prompt(msg, val); } catch (e2) {}
                    } finally {
                        if (ifr && ifr.parentNode) { try { ifr.parentNode.removeChild(ifr); } catch (e3) {} }
                    }
                }
                function pickMeta() {
                    var metas = document.querySelectorAll('meta[name="theme-color" i]');
                    var chosen = null;
                    for (var i = 0; i < metas.length; i++) {
                        var m = metas[i];
                        var media = m.getAttribute('media');
                        /* A meta scoped to a media query only counts when that query currently
                           matches (e.g. the dark-mode theme-color); the last applicable one wins,
                           mirroring how later declarations override earlier ones. */
                        if (media) {
                            try { if (!window.matchMedia(media).matches) continue; } catch (e) { continue; }
                        }
                        chosen = m;
                    }
                    return chosen;
                }
                function themeColor() {
                    var meta = pickMeta();
                    var raw = meta ? (meta.getAttribute('content') || '') : '';
                    raw = raw.trim();
                    if (!raw) return 'none';
                    var probe = document.createElement('span');
                    probe.style.cssText = 'display:none!important;color:transparent;';
                    probe.style.color = raw;
                    (document.body || document.documentElement).appendChild(probe);
                    var computed = '';
                    try { computed = window.getComputedStyle(probe).color || ''; } catch (e) {}
                    if (probe.parentNode) { try { probe.parentNode.removeChild(probe); } catch (e2) {} }
                    return computed || 'none';
                }
                function isOpaqueBg(c) {
                    /* Reject transparent / near-transparent backgrounds so the walk keeps climbing to
                       the element that actually paints the pixels behind the status bar. */
                    if (!c) return false;
                    if (c === 'transparent') return false;
                    var m = c.match(/rgba?\(([^)]+)\)/);
                    if (!m) return false;
                    var p = m[1].split(',');
                    if (p.length >= 4 && parseFloat(p[3]) <= 0.05) return false;
                    return true;
                }
                function bgColor() {
                    /* Sample the element painted just under the status bar (top-center of the visual
                       viewport), walking up the ancestor chain past transparent backgrounds. Falls
                       back to body/html, then white. Mirrors how Safari tints its bar to the content. */
                    var el = null;
                    try { el = document.elementFromPoint(Math.floor(window.innerWidth / 2), 4); } catch (e) {}
                    var node = el;
                    while (node && node.nodeType === 1) {
                        var c = '';
                        try { c = window.getComputedStyle(node).backgroundColor || ''; } catch (e2) {}
                        if (isOpaqueBg(c)) return c;
                        node = node.parentElement;
                    }
                    var candidates = [document.body, document.documentElement];
                    for (var i = 0; i < candidates.length; i++) {
                        if (!candidates[i]) continue;
                        var cc = '';
                        try { cc = window.getComputedStyle(candidates[i]).backgroundColor || ''; } catch (e3) {}
                        if (isOpaqueBg(cc)) return cc;
                    }
                    return 'rgb(255, 255, 255)';
                }
                function resolve() {
                    var theme = 'none', bg = 'none';
                    try { theme = themeColor(); } catch (e) {}
                    try { bg = bgColor(); } catch (e2) {}
                    tangentBridge('${BridgeGuard.tag(BRIDGE_MESSAGE)}', theme + '|' + bg + '|' + location.href);
                }
                try { resolve(); } catch (e) {}
                /* Re-sample once when the color scheme flips so a dark/light theme-color swaps in
                   without a reload. Guarded so repeated injections don't stack listeners. */
                try {
                    if (!window.__tangentThemeColorBound && window.matchMedia) {
                        window.__tangentThemeColorBound = true;
                        var mq = window.matchMedia('(prefers-color-scheme: dark)');
                        var onChange = function() { try { resolve(); } catch (e) {} };
                        if (mq.addEventListener) mq.addEventListener('change', onChange);
                        else if (mq.addListener) mq.addListener(onChange);
                    }
                } catch (e) {}
            })();
        """.trimIndent().replace("\n", " ")
    }
}
