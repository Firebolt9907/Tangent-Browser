package com.firebolt9907.luminabrowser.ui.content

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
 * [com.firebolt9907.luminabrowser.ui.reader.ReaderExtractor] and [LinkRectProbe] — the script
 * smuggles its result back through a native `window.prompt(message, payload)` taken from a throwaway
 * same-origin iframe (a pristine realm the page can't have patched). The wrapping
 * `ReaderPromptDelegate` intercepts `lumina-themecolor` and calls [onPayload] with the tab id it was
 * constructed for plus the `"<computed-color>|<url>"` payload.
 *
 * The script honours the meta tag's `media` attribute (e.g. a separate dark-mode theme-color) via
 * `matchMedia`, and re-samples when the color scheme changes, so the tint tracks the page — again
 * matching Safari.
 */
object ThemeColorProbe {

    private const val TAG = "ThemeColorProbe"
    const val BRIDGE_MESSAGE = "lumina-themecolor"

    /**
     * Sink for extracted colors, registered by `LuminaRoot` while it is composed. Global (like the
     * video / link-rect bridges) because only one browser activity is ever foreground at a time.
     * `color` is an opaque ARGB int, or `null` when the page declares no usable theme color.
     */
    @Volatile
    var listener: ((tabId: String, url: String, color: Int?) -> Unit)? = null

    /** Called by the prompt delegate with the raw `"<color>|<url>"` payload for [tabId]. */
    fun onPayload(tabId: String, payload: String) {
        val cb = listener ?: return
        val sep = payload.indexOf('|')
        val colorStr = if (sep >= 0) payload.substring(0, sep) else payload
        val url = if (sep >= 0) payload.substring(sep + 1) else ""
        cb(tabId, url, parseColor(colorStr))
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
            Log.w(TAG, "Failed to parse theme-color payload: $raw", e)
            null
        }
    }

    /**
     * Builds the `javascript:` probe. Flattened to one line before injection — use only `/* */`
     * comments. Reads the applicable `<meta name="theme-color">` (honouring its `media` query),
     * resolves it to a computed color via a throwaway element, and posts `"<color>|<url>"` back over
     * the prompt bridge. Re-samples once on the next color-scheme change so a dark/light switch
     * re-tints without a reload.
     */
    fun buildScript(): String {
        return """
            javascript:(function() {
                function luminaBridge(msg, val) {
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
                function resolve() {
                    var meta = pickMeta();
                    var raw = meta ? (meta.getAttribute('content') || '') : '';
                    raw = raw.trim();
                    if (!raw) { luminaBridge('$BRIDGE_MESSAGE', 'none|' + location.href); return; }
                    var probe = document.createElement('span');
                    probe.style.cssText = 'display:none!important;color:transparent;';
                    probe.style.color = raw;
                    (document.body || document.documentElement).appendChild(probe);
                    var computed = '';
                    try { computed = window.getComputedStyle(probe).color || ''; } catch (e) {}
                    if (probe.parentNode) { try { probe.parentNode.removeChild(probe); } catch (e2) {} }
                    if (!computed) computed = 'none';
                    luminaBridge('$BRIDGE_MESSAGE', computed + '|' + location.href);
                }
                try { resolve(); } catch (e) {}
                /* Re-sample once when the color scheme flips so a dark/light theme-color swaps in
                   without a reload. Guarded so repeated injections don't stack listeners. */
                try {
                    if (!window.__luminaThemeColorBound && window.matchMedia) {
                        window.__luminaThemeColorBound = true;
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
