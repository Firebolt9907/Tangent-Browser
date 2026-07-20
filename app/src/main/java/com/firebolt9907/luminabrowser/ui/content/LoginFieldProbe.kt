package com.firebolt9907.luminabrowser.ui.content

/**
 * Proactive login-field detection, so the saved-password picker can appear **before** the user taps a
 * username/password field — which means the soft keyboard never has to come up when a credential is
 * already saved for the site.
 *
 * Gecko's own login autofill only surfaces its `SelectLoginPrompt` (handled in `ui/PromptHandler.kt`)
 * once a login field is *focused*, and focusing a web input raises the keyboard. This probe closes that
 * gap: a content script ([buildDetectScript], injected per-load in `ContentHost` alongside the OTP
 * detector) scans for a visible, empty password field on load and reports over the shared
 * `window.prompt` bridge (the same `lumina-*` channel [OtpAutofill] / [ThemeColorProbe] use; routed in
 * `ReaderExtractor`'s prompt delegate to [onPayload]). The host then looks up saved logins for the
 * origin and, if any exist, shows the picker proactively (see `ui/ProactiveLoginAutofill.kt`).
 *
 * Filling goes through JS ([buildFillScript]) rather than Gecko's `onConfirm`, because we are bypassing
 * the focus-driven Gecko prompt entirely — same approach the OTP filler and `buildLoginSubmitScript`
 * already use. Selecting a credential is still biometric-gated in the Compose layer.
 */
object LoginFieldProbe {

    const val BRIDGE_MESSAGE = "lumina-login"

    /**
     * Set by `ui/ProactiveLoginAutofill.kt`. Invoked (on the GeckoView UI thread) with the page URL
     * when a visible, empty login field is detected, so the host can query saved logins and offer the
     * picker. Global like the other probe listeners — only one browser activity is foreground at a time.
     */
    @Volatile
    var onFieldDetected: ((pageUrl: String) -> Unit)? = null

    /** Called by the prompt delegate when the page reports a login field. Payload is the page URL. */
    fun onPayload(@Suppress("UNUSED_PARAMETER") tabId: String, payload: String) {
        val url = payload.substringAfter('|', payload)
        onFieldDetected?.invoke(url)
    }

    /**
     * Shared JS: `luminaBridge` (prompt-bridge post), `luminaVisible`, and `luminaLoginFields` (the
     * heuristic that locates the visible password field + its best-guess username field). Inlined into
     * both scripts so detection and fill agree. Flattened to one line at the call sites, so `/* */`
     * comments only.
     */
    private val sharedJs = """
        function luminaBridge(msg, val) {
            var ifr = null;
            try {
                ifr = document.createElement('iframe');
                ifr.setAttribute('aria-hidden', 'true');
                ifr.style.cssText = 'display:none!important;width:0;height:0;border:0;position:absolute;left:-9999px;';
                (document.body || document.documentElement).appendChild(ifr);
                var w = ifr.contentWindow;
                if (w && typeof w.prompt === 'function') { w.prompt(msg, val); }
                else { window.prompt(msg, val); }
            } catch (e) {
                try { window.prompt(msg, val); } catch (e2) {}
            } finally {
                if (ifr && ifr.parentNode) { try { ifr.parentNode.removeChild(ifr); } catch (e3) {} }
            }
        }
        function luminaVisible(el) {
            if (!el) return false;
            try {
                var r = el.getBoundingClientRect();
                if (r.width <= 0 || r.height <= 0) return false;
                var s = window.getComputedStyle(el);
                if (s.visibility === 'hidden' || s.display === 'none' || parseFloat(s.opacity || '1') < 0.05) return false;
            } catch (e) { return false; }
            return true;
        }
        /* Returns { pw: <passwordInput>, user: <usernameInput|null> } for the first visible password
           field, or null. Username guess: the nearest preceding visible text/email/tel input in the
           same form (falling back to document order), preferring an autocomplete=username/email hint. */
        function luminaLoginFields() {
            var pws = document.querySelectorAll('input[type=password]');
            var pw = null;
            for (var i = 0; i < pws.length; i++) {
                if (!pws[i].disabled && !pws[i].readOnly && luminaVisible(pws[i])) { pw = pws[i]; break; }
            }
            if (!pw) return null;
            var scope = pw.form || document;
            var cands = scope.querySelectorAll('input');
            var texty = [];
            for (var j = 0; j < cands.length; j++) {
                var el = cands[j];
                if (el === pw) break;
                var t = (el.type || 'text').toLowerCase();
                if (['text','email','tel'].indexOf(t) < 0) continue;
                if (el.disabled || el.readOnly || !luminaVisible(el)) continue;
                texty.push(el);
            }
            var user = null;
            for (var k = 0; k < texty.length; k++) {
                var ac = (texty[k].getAttribute('autocomplete') || '').toLowerCase();
                if (ac.indexOf('username') >= 0 || ac.indexOf('email') >= 0) { user = texty[k]; break; }
            }
            if (!user && texty.length) user = texty[texty.length - 1];
            return { pw: pw, user: user };
        }
    """

    /**
     * Detector: reports (once per load, plus on focus) the page URL when a visible, *empty* password
     * field exists, so the host can offer saved logins before the field is tapped. Empty-only so we
     * don't re-prompt over an already-filled form. Guarded against stacking listeners.
     */
    fun buildDetectScript(): String {
        return ("""
            javascript:(function() {
                $sharedJs
                function report() {
                    try {
                        var f = luminaLoginFields();
                        if (f && f.pw && !f.pw.value) { luminaBridge('${BridgeGuard.tag(BRIDGE_MESSAGE)}', 'field|' + location.href); }
                    } catch (e) {}
                }
                report();
                try {
                    if (!window.__luminaLoginBound) {
                        window.__luminaLoginBound = true;
                        document.addEventListener('focusin', function(ev) {
                            try {
                                var t = ev.target;
                                if (t && t.tagName === 'INPUT') {
                                    var ty = (t.type || '').toLowerCase();
                                    if (ty === 'password' || ty === 'text' || ty === 'email' || ty === 'tel') report();
                                }
                            } catch (e) {}
                        }, true);
                    }
                } catch (e) {}
            })();
        """).trimIndent().replace("\n", " ")
    }

    /**
     * Filler: writes [username] (if a username field was found) and [password] into the detected login
     * fields. Uses the native value setter so React-controlled inputs pick the change up, and dispatches
     * input/change/keyup so validators fire. The password is interpolated as a JS string literal, so it
     * is JSON-escaped defensively.
     */
    fun buildFillScript(username: String, password: String): String {
        val u = jsString(username)
        val p = jsString(password)
        return ("""
            javascript:(function() {
                $sharedJs
                function setValue(el, val) {
                    if (!el) return;
                    try {
                        el.focus();
                        var proto = (typeof HTMLInputElement !== 'undefined') ? HTMLInputElement.prototype : null;
                        var desc = proto ? Object.getOwnPropertyDescriptor(proto, 'value') : null;
                        if (desc && desc.set) { desc.set.call(el, val); } else { el.value = val; }
                        el.dispatchEvent(new Event('input', { bubbles: true }));
                        el.dispatchEvent(new Event('change', { bubbles: true }));
                        try { el.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true })); } catch (e) {}
                    } catch (e) { try { el.value = val; } catch (e2) {} }
                }
                try {
                    var f = luminaLoginFields();
                    if (!f || !f.pw) return;
                    if (f.user && $u) setValue(f.user, $u);
                    setValue(f.pw, $p);
                    try { f.pw.blur(); } catch (e) {}
                } catch (e) {}
            })();
        """).trimIndent().replace("\n", " ")
    }

    /** JSON-encode [s] into a safe double-quoted JS string literal (handles quotes, backslashes, etc.). */
    private fun jsString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '<' -> sb.append("\\u003c")
                '>' -> sb.append("\\u003e")
                ' ' -> sb.append("\\u2028")
                ' ' -> sb.append("\\u2029")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append("\"")
        return sb.toString()
    }
}
