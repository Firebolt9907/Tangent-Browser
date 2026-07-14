package com.firebolt9907.luminabrowser.ui.content

import android.util.Log
import androidx.compose.ui.geometry.Rect

/**
 * Reads the on-screen rectangle of a long-pressed link out of the page so the [LinkPeekCard] can
 * perform a true shared-element morph *from that element* (not from the bare finger point).
 *
 * ## How it works (the `prompt()` bridge, same channel the reader/video use)
 * GeckoView only offers one-way `javascript:` injection — no return value. So, like
 * [com.firebolt9907.luminabrowser.ui.reader.ReaderExtractor] and the video controller, we smuggle
 * the result back through a native `window.prompt(message, payload)`: the injected script hit-tests
 * the pressed element, computes its viewport rect, and hands it back via `prompt('lumina-linkrect',
 * "x|y|w|h")`. The wrapping `ReaderPromptDelegate` intercepts that message and calls [onPayload].
 *
 * The script converts the rect into **device pixels relative to the top-left of the browser
 * viewport**, folding in `devicePixelRatio` and `visualViewport` scale/pan so pinch-zoom and
 * scroll are handled. Because the single [mozilla.components.concept.engine.EngineView] fills the
 * window, those coordinates line up with Compose's root-layout pixel space, which is what the morph
 * animation is authored in.
 *
 * ## Coordinate hand-off
 * [pending] is a one-shot callback set by `LuminaRoot` right before it injects the probe. The
 * delegate runs [onPayload] on Gecko's thread, so the callback must marshal back to the main thread
 * itself (it does — it writes Compose state via the UI-dispatcher scope).
 */
object LinkRectProbe {

    private const val TAG = "LinkRectProbe"
    const val BRIDGE_MESSAGE = "lumina-linkrect"

    /**
     * One-shot result sink for the in-flight probe. Set by the caller before injecting; cleared the
     * moment a payload arrives so a stale page callback can't fire it twice. `null` Rect = the link
     * wasn't found (fall back to the finger point).
     */
    @Volatile
    var pending: ((Rect?) -> Unit)? = null

    /** Called by the prompt delegate with the raw `"x|y|w|h"` (or `"none"`) payload. */
    fun onPayload(payload: String) {
        val cb = pending
        pending = null
        if (cb == null) return
        cb(parse(payload))
    }

    private fun parse(payload: String): Rect? {
        if (payload.isBlank() || payload == "none") return null
        return try {
            val parts = payload.split('|')
            if (parts.size < 4) return null
            val x = parts[0].toFloat()
            val y = parts[1].toFloat()
            val w = parts[2].toFloat()
            val h = parts[3].toFloat()
            if (w <= 0f || h <= 0f) null else Rect(x, y, x + w, y + h)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse link-rect payload: $payload", e)
            null
        }
    }

    /**
     * Builds the `javascript:` probe. [touchX]/[touchY] are the finger-down point in device pixels
     * (Compose root px). The script finds the anchor under that point (falling back to the anchor
     * whose href matches [targetUrl], then to the nearest such anchor), measures it, and posts the
     * rect back over the prompt bridge. Flattened to one line — use only `/* */` comments.
     */
    fun buildScript(targetUrl: String, touchX: Float, touchY: Float): String {
        /* Target URL is embedded as a JSON string literal so quotes/backslashes can't break out. */
        val urlLiteral = org.json.JSONObject.quote(targetUrl)
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
                try {
                    var vv = window.visualViewport;
                    var scale = vv ? vv.scale : 1;
                    var ox = vv ? vv.offsetLeft : 0;
                    var oy = vv ? vv.offsetTop : 0;
                    var dpr = window.devicePixelRatio || 1;
                    /* Finger-down point (device px) back into CSS client coords for hit-testing. */
                    var clientX = ox + ($touchX / dpr) / scale;
                    var clientY = oy + ($touchY / dpr) / scale;
                    var target = $urlLiteral;
                    var anchor = null;
                    var atPoint = document.elementFromPoint(clientX, clientY);
                    if (atPoint && atPoint.closest) { anchor = atPoint.closest('a[href], area[href]'); }
                    /* Fall back to the anchor whose resolved href matches the pressed link; if there
                       are several (repeated nav links), pick the one nearest the finger. */
                    if (!anchor || anchor.href !== target) {
                        var links = document.querySelectorAll('a[href], area[href]');
                        var best = null;
                        var bestDist = Infinity;
                        for (var i = 0; i < links.length; i++) {
                            if (links[i].href !== target) continue;
                            var r = links[i].getBoundingClientRect();
                            if (r.width <= 0 || r.height <= 0) continue;
                            var cx = r.left + r.width / 2;
                            var cy = r.top + r.height / 2;
                            var d = (cx - clientX) * (cx - clientX) + (cy - clientY) * (cy - clientY);
                            if (d < bestDist) { bestDist = d; best = links[i]; }
                        }
                        if (best) { anchor = best; }
                    }
                    if (!anchor) { luminaBridge('$BRIDGE_MESSAGE', 'none'); return; }
                    var rect = anchor.getBoundingClientRect();
                    var x = (rect.left - ox) * scale * dpr;
                    var y = (rect.top - oy) * scale * dpr;
                    var wpx = rect.width * scale * dpr;
                    var hpx = rect.height * scale * dpr;
                    luminaBridge('$BRIDGE_MESSAGE', x + '|' + y + '|' + wpx + '|' + hpx);
                } catch (e) {
                    try { luminaBridge('$BRIDGE_MESSAGE', 'none'); } catch (e2) {}
                }
            })();
        """.trimIndent().replace("\n", " ")
    }
}
