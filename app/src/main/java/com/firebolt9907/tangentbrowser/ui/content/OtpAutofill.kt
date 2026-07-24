package com.firebolt9907.tangentbrowser.ui.content

/**
 * SMS one-time-code (2FA) autofill. Two halves:
 *
 *  1. **Detection** ([buildDetectScript]) — an injected content script that flags OTP input fields
 *     using far broader heuristics than GBoard's (which only reacts to `autocomplete="one-time-code"`).
 *     When such a field is present/focused it posts back over the shared `window.prompt` bridge (the
 *     same channel [ThemeColorProbe] / [LinkRectProbe] use; routed in `ReaderExtractor`'s prompt
 *     delegate to [onPayload]). That tells the host to arm Play Services' SMS User Consent listener.
 *  2. **Fill** ([buildFillScript]) — once a code has been pulled from an incoming SMS (with the user's
 *     one-tap consent), this script finds the OTP field(s) and types the code in, handling both a
 *     single input and the split one-digit-per-box UIs that GBoard fills poorly.
 *
 * The field heuristics live in JS and are shared (inlined) by both scripts so detection and fill
 * agree on what counts as an OTP field.
 */
object OtpAutofill {

    const val BRIDGE_MESSAGE = "tangent-otp"

    /**
     * Coordination hooks, wired by the two owners (globals like the other probe listeners — only one
     * browser activity is foreground at a time):
     *  - [smsListenStarter] is set by the Activity, which owns Play Services SMS User Consent; the
     *    page reporting an OTP field ([onPayload]) invokes it to arm the SMS listener.
     *  - [codeFiller] is set by `TangentRoot`, which holds the selected tab's engine session; the
     *    Activity calls [deliverCode] once it has a code, and that injects the fill script.
     */
    @Volatile
    var smsListenStarter: (() -> Unit)? = null

    @Volatile
    var codeFiller: ((code: String) -> Unit)? = null

    /**
     * Set by `TangentRoot`. Invoked (on the GeckoView UI thread) when the page reports an OTP field,
     * so the UI can surface the manual "paste code" fallback banner alongside arming SMS listening.
     */
    @Volatile
    var onFieldDetected: (() -> Unit)? = null

    /** Called by the prompt delegate when the page reports an OTP field. */
    fun onPayload(tabId: String, payload: String) {
        smsListenStarter?.invoke()
        onFieldDetected?.invoke()
    }

    /** Called with a code (from an incoming SMS, or pasted from the clipboard) — fill it. */
    fun deliverCode(code: String) {
        codeFiller?.invoke(code)
    }

    /** First 4–8 digit run in [text] (word-bounded first, then anywhere), or null. */
    fun extractCode(text: String): String? =
        Regex("""\b(\d{4,8})\b""").find(text)?.groupValues?.get(1)
            ?: Regex("""(\d{4,8})""").find(text)?.groupValues?.get(1)

    /**
     * The shared JS helpers: `tangentBridge` (prompt-bridge post), `tangentVisible`, and
     * `tangentIsOtpField` (the heuristic). Inlined into both scripts. Flattened to one line at the call
     * sites, so `/* */` comments only.
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
        function tangentIsOtpField(el) {
            if (!el || el.tagName !== 'INPUT') return false;
            var type = (el.type || 'text').toLowerCase();
            if (['password','checkbox','radio','submit','button','file','hidden','email','url','search','color','range','date','datetime-local','month','week','time'].indexOf(type) >= 0) return false;
            if (el.disabled || el.readOnly) return false;
            var ac = (el.getAttribute('autocomplete') || '').toLowerCase();
            /* Strong, explicit signal — the one thing GBoard keys off. */
            if (ac.indexOf('one-time-code') >= 0) return true;
            var maxlen = parseInt(el.getAttribute('maxlength') || '0', 10);
            var inputmode = (el.getAttribute('inputmode') || '').toLowerCase();
            var pattern = el.getAttribute('pattern') || '';
            var numeric = type === 'tel' || type === 'number' || inputmode === 'numeric' || inputmode === 'tel' || /\d/.test(pattern) || /[0-9]/.test(pattern);
            /* Split OTP UIs: a row of single-character numeric boxes. */
            if (maxlen === 1 && numeric) return true;
            /* Keyword match across every label-ish attribute. */
            var lbl = '';
            try { if (el.labels && el.labels.length) lbl = el.labels[0].textContent || ''; } catch (e) {}
            var hay = [el.name, el.id, el.getAttribute('placeholder'), el.getAttribute('aria-label'), el.getAttribute('title'), el.className, lbl].join(' ').toLowerCase();
            var re = /(one[\s_-]*time|\botp\b|2fa|mfa|verif|passcode|pass[\s_-]*code|security[\s_-]*code|auth[a-z]*[\s_-]*code|sms[\s_-]*code|confirm[a-z]*[\s_-]*code|\bcode\b|token)/;
            if (re.test(hay)) return true;
            /* A short numeric field is a strong OTP signal even without keywords. */
            if (numeric && maxlen >= 4 && maxlen <= 8) return true;
            return false;
        }
        function tangentOtpFields() {
            var out = [];
            var inputs = document.querySelectorAll('input');
            for (var i = 0; i < inputs.length; i++) {
                if (tangentIsOtpField(inputs[i]) && tangentVisible(inputs[i])) out.push(inputs[i]);
            }
            return out;
        }
    """

    /**
     * Detector: reports (once per load, plus on focus) when a visible OTP field exists, so the host
     * can arm the SMS listener. Guarded against stacking listeners across repeated injections.
     */
    fun buildDetectScript(): String {
        return ("""
            javascript:(function() {
                $sharedJs
                function report() { tangentBridge('${BridgeGuard.tag(BRIDGE_MESSAGE)}', 'field|' + location.href); }
                try { if (tangentOtpFields().length > 0) report(); } catch (e) {}
                try {
                    if (!window.__tangentOtpBound) {
                        window.__tangentOtpBound = true;
                        document.addEventListener('focusin', function(ev) {
                            try { if (tangentIsOtpField(ev.target)) report(); } catch (e) {}
                        }, true);
                    }
                } catch (e) {}
            })();
        """).trimIndent().replace("\n", " ")
    }

    /**
     * Filler: writes [code] into the OTP field(s). Split single-char boxes get one digit each; a
     * single field gets the whole code. Uses the native value setter so React-controlled inputs pick
     * the change up, and dispatches input/change/keyup so validators fire.
     */
    fun buildFillScript(code: String): String {
        // Keep only digits and cap length defensively; the value is interpolated into JS as a string.
        val safe = code.filter { it.isDigit() }.take(12)
        return ("""
            javascript:(function() {
                $sharedJs
                var code = '$safe';
                if (!code) return;
                function setValue(el, val) {
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
                    var fields = tangentOtpFields();
                    if (!fields.length) return;
                    var singles = [];
                    for (var i = 0; i < fields.length; i++) {
                        if (parseInt(fields[i].getAttribute('maxlength') || '0', 10) === 1) singles.push(fields[i]);
                    }
                    if (singles.length >= code.length) {
                        for (var j = 0; j < code.length; j++) setValue(singles[j], code.charAt(j));
                        return;
                    }
                    var active = document.activeElement;
                    var target = (fields.indexOf(active) >= 0) ? active : fields[0];
                    setValue(target, code);
                } catch (e) {}
            })();
        """).trimIndent().replace("\n", " ")
    }
}
