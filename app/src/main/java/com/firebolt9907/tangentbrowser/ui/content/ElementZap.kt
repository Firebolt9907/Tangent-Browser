package com.firebolt9907.tangentbrowser.ui.content

import org.json.JSONObject

/**
 * **Element zap** — tap any piece of a page (a cookie bar, a sticky newsletter prompt, an ad slot)
 * and hide it, permanently, for that site.
 *
 * Three parts:
 *  1. **Picker** ([buildPickerScript]) — an in-page overlay mode. It can't be a Compose overlay: only
 *     the page can hit-test its own DOM, so the picker lives in page JS, highlights the element under
 *     the finger, and swallows the touch so the page doesn't navigate under it. It reports the
 *     selection back over the nonce-guarded `tangent-*` prompt bridge (see [BridgeGuard]) as
 *     [BRIDGE_MESSAGE]; the host renders the confirm/widen/cancel affordances as a normal banner.
 *  2. **Host round-trips** ([buildWidenScript] / [buildConfirmScript] / [buildCancelScript]) — the
 *     banner's buttons drive the still-running picker. They reach it through a *nonce-named* global
 *     (`__tangentZap_<nonce>`), not a fixed name: the picker's controls would otherwise be callable by
 *     any script in the page realm, which is the same exposure the bridge nonce exists to close.
 *  3. **Apply** ([buildApplyScript]) — a `<style>` of `display:none!important` rules rebuilt from
 *     `ZappedElementsStore` on every load of the host (and immediately after a zap, so the element
 *     disappears without a reload). The picker deliberately does **not** hide the element itself: with
 *     the stylesheet as the single source of truth, "Restore hidden elements" is just a re-apply, and
 *     nothing can be left hidden by an inline style the store no longer knows about.
 *
 * Selector generation lives in the page script (it needs the live DOM to test uniqueness). It prefers
 * the **shortest suffix of the ancestor path that uniquely matches** — so a distinctive `#promo-bar`
 * or `.newsletter-modal` generalises to every page on the site, while an anonymous `<div>` still gets
 * a precise `:nth-of-type` path. Selectors are re-validated host-side by
 * `com.firebolt9907.tangentbrowser.state.isSafeZapSelector` before being stored or spliced into CSS.
 */
object ElementZap {

    const val BRIDGE_MESSAGE = "tangent-zap"

    /** The element currently under the picker's crosshair, as reported by the page. */
    data class Selection(
        val selector: String,
        val label: String,
        /** True when the selection has a parent worth widening to (i.e. not `body`/`html`). */
        val canWiden: Boolean,
    )

    sealed interface Event {
        /** The user moved/tapped onto an element; the banner shows it and offers Hide/Widen. */
        data class Selected(val selection: Selection) : Event

        /** The user committed; the host must persist [selection] and re-apply the stylesheet. */
        data class Zapped(val selection: Selection) : Event

        /** Picker torn down in the page (user cancelled). */
        object Cancelled : Event
    }

    /**
     * Sink for picker events, set by the host while the picker is active and cleared on teardown.
     * Invoked on Gecko's thread — the callback marshals to the main thread itself.
     */
    @Volatile
    var onEvent: ((Event) -> Unit)? = null

    /** Called by the prompt delegate with the picker's JSON payload. */
    fun onPayload(payload: String) {
        val handler = onEvent ?: return
        val event = parse(payload) ?: return
        handler(event)
    }

    internal fun parse(payload: String): Event? = runCatching {
        val obj = JSONObject(payload)
        when (obj.optString("state")) {
            "selected" -> selectionOf(obj)?.let { Event.Selected(it) }
            "zapped" -> selectionOf(obj)?.let { Event.Zapped(it) }
            "cancel" -> Event.Cancelled
            else -> null
        }
    }.getOrNull()

    private fun selectionOf(obj: JSONObject): Selection? {
        val selector = obj.optString("sel").takeIf { it.isNotBlank() } ?: return null
        return Selection(
            selector = selector,
            label = obj.optString("label").ifBlank { selector },
            canWiden = obj.optBoolean("canWiden", false),
        )
    }

    // ---- Apply ---------------------------------------------------------------------------------

    /** Id of the injected stylesheet, shared by the apply script and its self-heal observer. */
    private const val StyleId = "tangent-zap-style"

    /**
     * The stylesheet text for [selectors]. One rule per selector rather than a single grouped rule:
     * an invalid selector only invalidates *its own* rule, so one bad entry can't stop the rest of a
     * site's zaps from applying. Pure, so the JVM test can pin the escaping/shape.
     */
    internal fun buildHideCss(selectors: List<String>): String =
        selectors.joinToString("\n") { "$it{display:none!important}" }

    /**
     * Installs (or updates, or with an empty [selectors] list removes) the hide stylesheet. Safe to
     * run repeatedly — it reuses the existing `<style>` and only writes when the text changed.
     *
     * Self-heals against SPAs that swap `<head>` contents on a client-side route change, via a
     * `childList`-only observer on `<head>` (no subtree — this must stay off the page's hot path).
     */
    fun buildApplyScript(selectors: List<String>): String {
        val css = JSONObject.quote(buildHideCss(selectors))
        val key = globalKey()
        return """
            javascript:(function() {
                try {
                    var css = $css;
                    var K = '$key';
                    function ensure() {
                        var root = document.documentElement;
                        if (!root) return;
                        var head = document.head || root;
                        var el = document.getElementById('$StyleId');
                        if (!css) { if (el && el.parentNode) { el.parentNode.removeChild(el); } return; }
                        if (!el) {
                            el = document.createElement('style');
                            el.id = '$StyleId';
                            el.setAttribute('type', 'text/css');
                            /* The top-inset walk (see PageBottomSpacer) must skip our own nodes. */
                            el.setAttribute('data-tangentIgnoreSafeTop', '1');
                        }
                        if (el.textContent !== css) { el.textContent = css; }
                        if (el.parentNode !== head) { head.appendChild(el); }
                    }
                    ensure();
                    var state = window[K + '_apply'];
                    if (state && state.observer) { state.css = css; return; }
                    var obs = new MutationObserver(function() { ensure(); });
                    try { obs.observe(document.head || document.documentElement, { childList: true }); } catch (e) {}
                    window[K + '_apply'] = { observer: obs, css: css };
                } catch (e) {}
            })();
        """.trimIndent().replace("\n", " ")
    }

    // ---- Picker --------------------------------------------------------------------------------

    /** Page-realm name for the picker's control surface. Nonce-derived — see the class docs. */
    private fun globalKey(): String = "__tangentZap_" + BridgeGuard.nonce.replace(Regex("[^A-Za-z0-9_]"), "_")

    /** Steps the current selection up to its parent element and re-reports it. */
    fun buildWidenScript(): String = callPicker("widen")

    /** Commits the current selection: reports it as [Event.Zapped] and tears the picker down. */
    fun buildConfirmScript(): String = callPicker("confirm")

    /** Tears the picker down without zapping anything. */
    fun buildCancelScript(): String = callPicker("cancel")

    private fun callPicker(method: String): String {
        val key = globalKey()
        return "javascript:(function(){try{var a=window['$key'];if(a&&a.$method){a.$method();}}catch(e){}})();"
    }

    /**
     * Enters picker mode. Idempotent: re-injecting while a picker is live just clears the current
     * selection rather than stacking a second overlay and a second set of capture listeners.
     */
    fun buildPickerScript(): String {
        val key = globalKey()
        val message = BridgeGuard.tag(BRIDGE_MESSAGE)
        return """
            javascript:(function() {
                var K = '$key';
                if (window[K]) { try { window[K].restart(); } catch (e) {} return; }
                /* Same pristine-prompt bridge the other probes use: a throwaway same-origin iframe, so
                   a page that has monkey-patched window.prompt can neither block us nor read the nonce. */
                function bridge(msg, val) {
                    var ifr = null;
                    try {
                        ifr = document.createElement('iframe');
                        ifr.setAttribute('aria-hidden', 'true');
                        ifr.style.cssText = 'display:none!important;width:0;height:0;border:0;position:absolute;left:-9999px;';
                        (document.body || document.documentElement).appendChild(ifr);
                        var w = ifr.contentWindow;
                        if (w && typeof w.prompt === 'function') { w.prompt(msg, val); } else { window.prompt(msg, val); }
                    } catch (e) {
                        try { window.prompt(msg, val); } catch (e2) {}
                    } finally {
                        if (ifr && ifr.parentNode) { try { ifr.parentNode.removeChild(ifr); } catch (e3) {} }
                    }
                }
                var box = null;
                var cur = null;
                function ensureBox() {
                    if (box && box.parentNode) return;
                    box = document.createElement('div');
                    box.setAttribute('aria-hidden', 'true');
                    box.setAttribute('data-tangentIgnoreSafeTop', '1');
                    /* The huge spread shadow dims the rest of the page in one cheap composited layer —
                       no full-screen scrim element competing with the page for hit-testing. */
                    box.style.cssText = 'position:fixed;left:0;top:0;width:0;height:0;display:none;z-index:2147483646;pointer-events:none;border:2px solid #7aa7ff;border-radius:6px;background:rgba(122,167,255,0.25);box-shadow:0 0 0 4000px rgba(0,0,0,0.28);transition:left .08s linear,top .08s linear,width .08s linear,height .08s linear;';
                    (document.documentElement || document.body).appendChild(box);
                }
                function place(el) {
                    ensureBox();
                    if (!el) { box.style.display = 'none'; return; }
                    var r = el.getBoundingClientRect();
                    box.style.display = 'block';
                    box.style.left = r.left + 'px';
                    box.style.top = r.top + 'px';
                    box.style.width = Math.max(r.width, 3) + 'px';
                    box.style.height = Math.max(r.height, 3) + 'px';
                }
                function nameOk(s) { return /^[A-Za-z_][A-Za-z0-9_-]*${'$'}/.test(s); }
                /* Skip framework-generated class names (`css-1x9df`, `sc-bdVaJa`, hash suffixes): they
                   change on the next deploy, so a selector built on them silently stops matching. */
                function classesOf(el) {
                    var out = [];
                    var cl = el.classList;
                    if (!cl) return out;
                    for (var i = 0; i < cl.length && out.length < 2; i++) {
                        var c = cl[i];
                        if (!nameOk(c) || c.length > 28) continue;
                        if ((c.match(/[0-9]/g) || []).length >= 4) continue;
                        if (/^(css|sc|jsx|emotion|svelte)-/.test(c)) continue;
                        out.push(c);
                    }
                    return out;
                }
                function segment(el) {
                    var tag = el.tagName.toLowerCase();
                    var seg = tag;
                    var cls = classesOf(el);
                    for (var i = 0; i < cls.length; i++) { seg += '.' + cls[i]; }
                    var parent = el.parentElement;
                    if (parent) {
                        var same = 0, idx = 0, ambiguous = 0;
                        var ch = parent.children;
                        for (var j = 0; j < ch.length; j++) {
                            if (ch[j].tagName !== el.tagName) continue;
                            same++;
                            if (ch[j] === el) { idx = same; }
                            try { if (ch[j].matches(seg)) ambiguous++; } catch (e) { ambiguous++; }
                        }
                        if (same > 1 && ambiguous > 1) { seg += ':nth-of-type(' + idx + ')'; }
                    }
                    return seg;
                }
                function selectorFor(el) {
                    var parts = [];
                    var node = el;
                    var depth = 0;
                    while (node && node.nodeType === 1 && depth < 8) {
                        var tag = node.tagName.toLowerCase();
                        if (tag === 'html' || tag === 'body') break;
                        var id = node.getAttribute && node.getAttribute('id');
                        if (id && nameOk(id)) {
                            var uniq = false;
                            try { uniq = document.querySelectorAll('#' + id).length === 1; } catch (e) {}
                            if (uniq) { parts.unshift('#' + id); break; }
                        }
                        parts.unshift(segment(node));
                        node = node.parentElement;
                        depth++;
                    }
                    if (!parts.length) return '';
                    /* Shortest uniquely-matching suffix: `.cookie-bar` beats a 6-deep path, and keeps
                       matching on the site's other pages where the ancestor chain differs. */
                    for (var i = parts.length - 1; i >= 0; i--) {
                        var candidate = parts.slice(i).join(' > ');
                        try {
                            var m = document.querySelectorAll(candidate);
                            if (m.length === 1 && m[0] === el) return candidate;
                        } catch (e) {}
                    }
                    return parts.join(' > ');
                }
                function labelFor(el) {
                    var tag = el.tagName.toLowerCase();
                    var id = el.getAttribute && el.getAttribute('id');
                    if (id && nameOk(id)) return tag + '#' + id;
                    var cls = classesOf(el);
                    if (cls.length) return tag + '.' + cls.join('.');
                    var txt = (el.textContent || '').replace(/\s+/g, ' ').trim();
                    if (txt) return tag + ': ' + txt.slice(0, 40);
                    return tag;
                }
                function widenable(el) {
                    var p = el && el.parentElement;
                    return !!(p && p !== document.body && p !== document.documentElement);
                }
                /* One tap fires several of the events we listen to (touchend → pointerup → mouseup →
                   click). Each post() is a synchronous native prompt, so re-report only when the
                   selection actually moved. */
                var posted = null;
                function post(state) {
                    var payload = { state: state };
                    if (cur && state !== 'cancel') {
                        if (posted === cur) return;
                        payload.sel = selectorFor(cur);
                        payload.label = labelFor(cur);
                        payload.canWiden = widenable(cur);
                        if (!payload.sel) return;
                        posted = cur;
                    }
                    bridge('$message', JSON.stringify(payload));
                }
                function pointAt(ev) {
                    var t = (ev.touches && ev.touches[0]) || (ev.changedTouches && ev.changedTouches[0]) || ev;
                    if (typeof t.clientX !== 'number') return null;
                    var el = document.elementFromPoint(t.clientX, t.clientY);
                    if (!el || el === box || el === document.documentElement || el === document.body) return null;
                    return el;
                }
                /* Hide the tap from the page's own handlers WITHOUT preventDefault: cancelling
                   touchstart/touchmove also cancels the native scroll gesture, and the picker has to
                   stay scrollable — the thing you want to zap is often further down the page.
                   Navigation is instead stopped at the synthesized `click` (hard()), which is what
                   actually follows a link. */
                function soft(ev) {
                    try { ev.stopPropagation(); } catch (e) {}
                    try { ev.stopImmediatePropagation(); } catch (e) {}
                }
                function hard(ev) {
                    try { ev.preventDefault(); } catch (e) {}
                    soft(ev);
                }
                function onDown(ev) {
                    var el = pointAt(ev);
                    if (el) { cur = el; place(cur); }
                    soft(ev);
                }
                function onUp(ev) {
                    soft(ev);
                    if (cur) { post('selected'); }
                }
                function onClick(ev) {
                    var el = pointAt(ev);
                    if (el) { cur = el; place(cur); }
                    hard(ev);
                    if (cur) { post('selected'); }
                }
                /* The overlay is position:fixed over a scrolling document, so it has to be re-placed
                   as the page moves under it. */
                function onScroll() { if (cur) { place(cur); } }
                var DOWN = ['touchstart', 'pointerdown', 'mousedown'];
                var UP = ['touchend', 'pointerup', 'mouseup'];
                var HARD = ['click', 'contextmenu', 'auxclick'];
                function bind(add) {
                    var fn = add ? 'addEventListener' : 'removeEventListener';
                    var opts = add ? { capture: true, passive: false } : true;
                    for (var i = 0; i < DOWN.length; i++) { document[fn](DOWN[i], onDown, opts); }
                    for (var j = 0; j < UP.length; j++) { document[fn](UP[j], onUp, opts); }
                    for (var k = 0; k < HARD.length; k++) { document[fn](HARD[k], onClick, opts); }
                    window[fn]('scroll', onScroll, add ? { capture: true, passive: true } : true);
                    window[fn]('resize', onScroll, add ? { capture: true, passive: true } : true);
                }
                function teardown() {
                    bind(false);
                    if (box && box.parentNode) { try { box.parentNode.removeChild(box); } catch (e) {} }
                    box = null;
                    cur = null;
                    try { delete window[K]; } catch (e) { window[K] = null; }
                }
                window[K] = {
                    widen: function() {
                        if (cur && widenable(cur)) { cur = cur.parentElement; place(cur); }
                        post('selected');
                    },
                    confirm: function() {
                        if (!cur) { teardown(); bridge('$message', JSON.stringify({ state: 'cancel' })); return; }
                        var payload = { state: 'zapped', sel: selectorFor(cur), label: labelFor(cur) };
                        teardown();
                        if (payload.sel) { bridge('$message', JSON.stringify(payload)); }
                        else { bridge('$message', JSON.stringify({ state: 'cancel' })); }
                    },
                    cancel: function() { teardown(); bridge('$message', JSON.stringify({ state: 'cancel' })); },
                    restart: function() { cur = null; posted = null; place(null); }
                };
                bind(true);
                ensureBox();
            })();
        """.trimIndent().replace("\n", " ")
    }
}
