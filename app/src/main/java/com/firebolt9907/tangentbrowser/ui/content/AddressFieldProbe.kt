package com.firebolt9907.tangentbrowser.ui.content

import java.util.concurrent.ConcurrentHashMap

/** Presentation hints only: never supplies stored data or an origin. */
internal object AddressFieldProbe {
    const val BRIDGE_MESSAGE = "tangent-address-field"
    private val fields = ConcurrentHashMap<String, Pair<String?, String>>()

    fun onPayload(tabId: String, field: String) {
        fields[tabId] = BridgeGuard.trustedOrigin(tabId) to field.take(40)
    }

    fun field(tabId: String?): String = tabId?.let { id ->
        fields[id]?.takeIf { it.first == BridgeGuard.trustedOrigin(id) }?.second
    }.orEmpty()

    fun forget(tabId: String?) { if (tabId != null) fields.remove(tabId) }

    fun buildDetectScript(): String = """
        (function() {
            if (window.__tangentAddressField) return;
            window.__tangentAddressField = true;
            function report(event) {
                var el = event ? event.target : document.activeElement;
                var hint = '';
                if (el && /^(INPUT|TEXTAREA|SELECT)$/.test(el.tagName)) {
                    var ac = (el.autocomplete || '').toLowerCase().trim().split(/\s+/);
                    hint = ac.filter(function(t) { return /^(tel(?:-.*)?|email|name|given-name|additional-name|family-name|organization|street-address|address-.*|postal-code|country(?:-name)?)$/.test(t); })[0] || '';
                    if (!hint && /^(tel|email)$/.test(el.type)) hint = el.type;
                    if (!hint) {
                        var labels = Array.prototype.map.call(el.labels || [], function(l) { return l.textContent; }).join(' ');
                        var label = [el.name, el.id, el.getAttribute('aria-label'), el.placeholder, labels].join(' ').toLowerCase();
                        if (/phone|mobile|telephone/.test(label)) hint = 'tel';
                        else if (/email|e-mail/.test(label)) hint = 'email';
                        else if (/first.?name|given.?name/.test(label)) hint = 'given-name';
                        else if (/last.?name|family.?name|surname/.test(label)) hint = 'family-name';
                    }
                }
                var frame = document.createElement('iframe');
                frame.style.display = 'none';
                try {
                    (document.body || document.documentElement).appendChild(frame);
                    frame.contentWindow.prompt('${BridgeGuard.tag(BRIDGE_MESSAGE)}', hint);
                } catch (_) {} finally { frame.remove(); }
            }
            document.addEventListener('pointerdown', report, true);
            document.addEventListener('focusin', report, true);
            report(null);
        })();
    """.trimIndent().replace('\n', ' ')
}
