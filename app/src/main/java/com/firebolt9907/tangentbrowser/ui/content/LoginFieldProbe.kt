package com.firebolt9907.tangentbrowser.ui.content

/**
 * Proactive login-field detection, so the saved-password picker can appear **before** the user taps a
 * username/password field — which means the soft keyboard never has to come up when a credential is
 * already saved for the site.
 *
 * Gecko's own login autofill only surfaces its `SelectLoginPrompt` (handled in `ui/PromptHandler.kt`)
 * once a login field is *focused*, and focusing a web input raises the keyboard. This probe closes that
 * gap: a content script ([buildDetectScript], injected per-load in `ContentHost` alongside the OTP
 * detector) scans for a visible, empty password field on load and reports over the shared
 * `window.prompt` bridge (the same `tangent-*` channel [OtpAutofill] / [ThemeColorProbe] use; routed in
 * `ReaderExtractor`'s prompt delegate to [onPayload]). The host then looks up saved logins for the
 * origin and, if any exist, shows the picker proactively (see `ui/ProactiveLoginAutofill.kt`).
 *
 * Filling goes through JS ([buildFillScript]) rather than Gecko's `onConfirm`, because we are bypassing
 * the focus-driven Gecko prompt entirely — same approach the OTP filler and `buildLoginSubmitScript`
 * already use. Selecting a credential is still biometric-gated in the Compose layer.
 */
object LoginFieldProbe {

    const val BRIDGE_MESSAGE = "tangent-login"

    /**
     * Which half of an identifier-first sign-in the page is currently showing.
     *
     * Enterprise IdPs (Entra ID above all) are two documents' worth of form in one document: you get
     * an email box, press Next, and the *same* document swaps in a password box with no navigation.
     * A detector that only knows about password fields therefore sees nothing on step one and — since
     * nothing reloads — never gets re-injected for step two. Reporting the stage is what lets the host
     * treat those as two separate offers on one page.
     */
    object Stage {
        /** A visible, empty identifier (email/username) field, with no visible password field yet. */
        const val User = "user"

        /** A visible, empty password field — the classic case. */
        const val Password = "pw"
    }

    /**
     * Set by `ui/ProactiveLoginAutofill.kt`. Invoked (on the GeckoView UI thread) with the detected
     * [Stage] and the browser-validated tab/origin when a visible, empty login field is detected, so the host can query
     * saved logins and offer the picker. Global like the other probe listeners — only one browser
     * activity is foreground at a time.
     */
    @Volatile
    internal var onFieldDetected: ((stage: String, target: LoginAutofillTarget) -> Unit)? = null

    /**
     * Called by the prompt delegate when the page reports a login field. Payload is
     * `field|<stage>|<url>`. The page-supplied URL is deliberately ignored for credential lookup.
     */
    fun onPayload(tabId: String, payload: String) {
        val parts = payload.split('|', limit = 3)
        if (parts.size < 3 || parts[0] != "field") return
        val stage = parts[1]
        if (stage != Stage.User && stage != Stage.Password) return
        val origin = loginAutofillOrigin(BridgeGuard.trustedOrigin(tabId)) ?: return
        onFieldDetected?.invoke(stage, LoginAutofillTarget(tabId, origin))
    }

    /**
     * Shared JS: `tangentBridge` (prompt-bridge post), `tangentVisible`, and `tangentLoginFields` (the
     * heuristic that locates the visible password field + its best-guess username field). Inlined into
     * both scripts so detection and fill agree. Flattened to one line at the call sites, so `/* */`
     * comments only.
     */
    private val sharedJs = """
        function tangentBridge(msg, val) {
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
        function tangentVisible(el) {
            if (!el) return false;
            try {
                var r = el.getBoundingClientRect();
                if (r.width <= 0 || r.height <= 0) return false;
                var s = window.getComputedStyle(el);
                if (s.visibility === 'hidden' || s.display === 'none' || parseFloat(s.opacity || '1') < 0.05) return false;
            } catch (e) { return false; }
            return true;
        }
        /* Does this text input look like the identity half of a login form? Used only to recognise an
           identifier-first step (Entra's email box), where there is no password field to anchor on. */
        function tangentLooksLikeUserField(el) {
            try {
                var ac = (el.getAttribute('autocomplete') || '').toLowerCase();
                if (ac.indexOf('username') >= 0 || ac.indexOf('email') >= 0) return true;
                if ((el.type || '').toLowerCase() === 'email') return true;
                var hay = ((el.name || '') + ' ' + (el.id || '') + ' ' + (el.placeholder || '') + ' ' +
                    (el.getAttribute('aria-label') || '')).toLowerCase();
                return /user|email|login|account|upn|ident/.test(hay);
            } catch (e) { return false; }
        }
        /* Returns { pw, user, stage } or null.
           stage 'pw'   — a visible password field exists; `user` is the best-guess username field for
                          it (nearest preceding visible text/email/tel input in the same form, an
                          autocomplete=username/email hint winning), possibly null.
           stage 'user' — no visible password field, but a visible identity-looking text field does
                          exist: the first step of an identifier-first sign-in. */
        function tangentLoginFields() {
            var pws = document.querySelectorAll('input[type=password]');
            var pw = null;
            for (var i = 0; i < pws.length; i++) {
                if (!pws[i].disabled && !pws[i].readOnly && tangentVisible(pws[i])) { pw = pws[i]; break; }
            }
            var scope = (pw && pw.form) || document;
            var cands = scope.querySelectorAll('input');
            var texty = [];
            for (var j = 0; j < cands.length; j++) {
                var el = cands[j];
                if (pw && el === pw) break;
                var t = (el.type || 'text').toLowerCase();
                if (['text','email','tel'].indexOf(t) < 0) continue;
                if (el.disabled || el.readOnly || !tangentVisible(el)) continue;
                texty.push(el);
            }
            var user = null;
            for (var k = 0; k < texty.length; k++) {
                if (tangentLooksLikeUserField(texty[k])) { user = texty[k]; break; }
            }
            if (pw) {
                if (!user && texty.length) user = texty[texty.length - 1];
                return { pw: pw, user: user, stage: 'pw' };
            }
            if (!user) return null;
            return { pw: null, user: user, stage: 'user' };
        }
    """

    /** How long the mutation watcher stays armed on a sign-in page before disconnecting itself. */
    private const val ObserveWindowMs = 120_000

    /** Debounce between a relevant DOM mutation and the re-scan it triggers. */
    private const val ObserveDebounceMs = 250

    /**
     * Detector: reports the page URL and [Stage] whenever a visible, *empty* login field appears, so
     * the host can offer saved logins before the field is tapped. Empty-only so we don't re-prompt
     * over an already-filled form.
     *
     * Three trigger points, and the third is the one that makes SSO work:
     *  - the initial scan at injection,
     *  - `focusin` (the initial scan can land before a slow form renders),
     *  - a **mutation watcher**, because an identifier-first sign-in swaps the password field in
     *    without navigating. No load callback fires for that transition, so nothing re-injects this
     *    script and the password step would otherwise never be reported at all — leaving the user to
     *    tap the field by hand, which is exactly the keyboard-free path this probe exists to avoid.
     *
     * The watcher is only armed on pages that already look like a sign-in (a password field anywhere
     * in the DOM, visible or not — Entra keeps a hidden one on step one — or an identity-looking text
     * field), only re-scans for mutations that touched an input/form, debounces, dedupes by field
     * signature so a churning page can't spam the bridge, and disconnects after [ObserveWindowMs].
     * Ordinary browsing therefore never pays for it.
     */
    fun buildDetectScript(): String {
        return ("""
            javascript:(function() {
                $sharedJs
                var lastSig = null;
                function report() {
                    try {
                        var f = tangentLoginFields();
                        if (!f) return;
                        var target = f.stage === 'pw' ? f.pw : f.user;
                        if (!target || target.value) return;
                        var sig = f.stage + ':' + (target.name || target.id || target.type || '?');
                        if (sig === lastSig) return;
                        lastSig = sig;
                        tangentBridge('${BridgeGuard.tag(BRIDGE_MESSAGE)}', 'field|' + f.stage + '|' + location.href);
                    } catch (e) {}
                }
                function relevant(muts) {
                    for (var i = 0; i < muts.length; i++) {
                        var m = muts[i];
                        if (m.type === 'attributes') return true;
                        for (var j = 0; j < m.addedNodes.length; j++) {
                            var n = m.addedNodes[j];
                            if (!n || n.nodeType !== 1) continue;
                            if (n.tagName === 'INPUT' || n.tagName === 'FORM') return true;
                            if (n.querySelector && n.querySelector('input')) return true;
                        }
                    }
                    return false;
                }
                report();
                try {
                    if (!window.__tangentLoginBound) {
                        window.__tangentLoginBound = true;
                        document.addEventListener('focusin', function(ev) {
                            try {
                                var t = ev.target;
                                if (t && t.tagName === 'INPUT') {
                                    var ty = (t.type || '').toLowerCase();
                                    if (ty === 'password' || ty === 'text' || ty === 'email' || ty === 'tel') report();
                                }
                            } catch (e) {}
                        }, true);
                        var signin = !!document.querySelector('input[type=password]') || !!tangentLoginFields();
                        if (signin && typeof MutationObserver === 'function') {
                            var timer = null;
                            var obs = new MutationObserver(function(muts) {
                                if (!relevant(muts)) return;
                                if (timer) clearTimeout(timer);
                                timer = setTimeout(report, $ObserveDebounceMs);
                            });
                            obs.observe(document.documentElement, {
                                childList: true, subtree: true, attributes: true,
                                attributeFilter: ['type', 'style', 'class', 'hidden', 'aria-hidden', 'disabled']
                            });
                            setTimeout(function() { try { obs.disconnect(); } catch (e) {} }, $ObserveWindowMs);
                        }
                    }
                } catch (e) {}
            })();
        """).trimIndent().replace("\n", " ").trim()
    }

    /**
     * Filler: writes [username] (if a username field was found) and, on [Stage.Password], [password]
     * into the detected login fields. Uses the native value setter so React-controlled inputs pick the
     * change up, and dispatches input/change/keyup so validators fire. The password is interpolated as
     * a JS string literal, so it is JSON-escaped defensively.
     *
     * On [Stage.User] only the identifier is written — the page has no password field yet, and writing
     * one into whatever text box is present would be both useless and a disclosure.
     *
     * The write is verified and re-applied twice over the following second. A framework-driven form
     * (Entra, and any React sign-in) can re-render between our detection and our write and drop the
     * value on the floor; a fill that silently lands nowhere is indistinguishable to the user from
     * autofill being broken.
     */
    fun buildFillScript(
        username: String,
        password: String,
        stage: String = Stage.Password,
        expectedOrigin: String,
    ): String {
        val originGuard = originGuard(expectedOrigin)
        val wantPassword = stage == Stage.Password
        val u = jsString(username)
        // Never emit the secret into a script that has no password field to put it in: at the
        // identifier step the page holds no place for it, so it would be a disclosure with no purpose.
        val p = jsString(if (wantPassword) password else "")
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
                function fill() {
                    $originGuard
                    var f = tangentLoginFields();
                    if (!f) return false;
                    var wrote = false;
                    if (${wantPassword}) {
                        if (!f.pw) return false;
                        if (f.user && $u && f.user.value !== $u) { setValue(f.user, $u); wrote = true; }
                        if (f.pw.value !== $p) { setValue(f.pw, $p); wrote = true; }
                        try { f.pw.blur(); } catch (e) {}
                    } else {
                        if (!f.user || !$u) return false;
                        if (f.user.value !== $u) { setValue(f.user, $u); wrote = true; }
                    }
                    return wrote;
                }
                try { fill(); } catch (e) {}
                /* Re-assert against a re-render that wiped the value. No-ops when it stuck. */
                setTimeout(function() { try { fill(); } catch (e) {} }, 350);
                setTimeout(function() { try { fill(); } catch (e) {} }, 900);
            })();
        """).trimIndent().replace("\n", " ").trim()
    }

    /**
     * Presses the form's submit control after a fill — "Sign in" on [Stage.Password], "Next" on
     * [Stage.User].
     *
     * Conservative on purpose: it only acts on a form whose *visible* login field already holds a
     * value, so it can never submit a stray search box. Visibility matters here specifically because
     * an identifier-first IdP keeps a hidden password input in the DOM during step one — a plain
     * `querySelector('input[type=password]')` finds that decoy, reads it as empty, and gives up,
     * which is why the auto-submit used to stall on exactly the pages it was written for.
     *
     * Retries for a couple of seconds: the fill is asynchronous (Gecko's own `onConfirm` path even
     * more so), and the button is frequently disabled until the framework has processed the input.
     */
    fun buildSubmitScript(stage: String = Stage.Password, expectedOrigin: String? = null): String {
        val originGuard = originGuard(expectedOrigin)
        val wantPassword = stage == Stage.Password
        val labels = if (wantPassword) """/log\s?in|sign\s?in|submit|continue|verify/""" else """/next|continue|sign\s?in|log\s?in|submit/"""
        return ("""
            javascript:(function() {
                $sharedJs
                function trySubmit() {
                    $originGuard
                    var f = tangentLoginFields();
                    if (!f) return false;
                    var anchor = ${wantPassword} ? f.pw : f.user;
                    if (!anchor || !anchor.value) return false;
                    var form = anchor.form;
                    var scope = form || document;
                    var btn = null;
                    var explicit = scope.querySelectorAll('button[type=submit], input[type=submit]');
                    for (var i = 0; i < explicit.length; i++) {
                        if (!explicit[i].disabled && tangentVisible(explicit[i])) { btn = explicit[i]; break; }
                    }
                    if (!btn) {
                        var cands = scope.querySelectorAll('button, input[type=button], [role=button]');
                        for (var j = 0; j < cands.length; j++) {
                            var c = cands[j];
                            if (c.disabled || !tangentVisible(c)) continue;
                            var t = ((c.innerText || c.value || c.getAttribute('aria-label') || '')).toLowerCase();
                            if ($labels.test(t)) { btn = c; break; }
                        }
                    }
                    if (btn) { btn.click(); return true; }
                    if (!form) return false;
                    if (form.requestSubmit) { form.requestSubmit(); return true; }
                    form.submit();
                    return true;
                }
                var tries = 0;
                var iv = setInterval(function() {
                    tries++;
                    var done = false;
                    try { done = trySubmit(); } catch (e) {}
                    if (done || tries > 8) { clearInterval(iv); }
                }, 250);
            })();
        """).trimIndent().replace("\n", " ").trim()
    }

    // Check in every retry as well as the first execution: navigation may race native dispatch.
    private fun originGuard(expectedOrigin: String?): String {
        if (expectedOrigin == null) return ""
        val origin = loginAutofillOrigin(expectedOrigin) ?: return "return false;"
        return "if (location.origin !== ${jsString(origin)}) return false;"
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
