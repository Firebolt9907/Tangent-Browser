package com.firebolt9907.tangentbrowser.ui.content

import com.firebolt9907.tangentbrowser.state.BlobFileStore
import com.firebolt9907.tangentbrowser.state.isBlobUrl
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * Copies the bytes behind a `blob:` document out of the page and into [BlobFileStore].
 *
 * **Why it has to be done from page JS.** A `blob:` URL is a handle into one document's memory, not
 * an address: nothing native can fetch it, and it dies with the document that created it. The only
 * code that can read it is script running in a same-origin document — and the blob document itself
 * qualifies, because it was *loaded from* the blob, so the entry is still alive for as long as we
 * are looking at it. That is the one moment the bytes are reachable, and it is why capture is
 * triggered on arriving at a blob URL rather than lazily when the user asks to save.
 *
 * The bytes travel over the shared nonce-protected `window.prompt` bridge (routed in
 * `ReaderExtractor`'s prompt delegate, like [OtpAutofill] / [FormFieldMemory]) in base64 chunks,
 * because that bridge is the only synchronous page→host channel available to an injected
 * `javascript:` script. Each chunk is written straight to disk, so a large file never sits in
 * memory on either side, and the page yields between chunks so a 20 MB PDF doesn't freeze it.
 *
 * **Only the tab's own current blob URL can be captured** ([arm]): the host refuses a `begin` whose
 * URL isn't the one it just injected into. Without that check a page script that guessed the bridge
 * nonce could stream arbitrary blobs it minted into our cache.
 */
object BlobCapture {

    const val BRIDGE_MESSAGE = "tangent-blob"

    /** Binary bytes per chunk; ~176 KB of base64 per bridge message. */
    private const val CHUNK_BYTES = 131072

    /** The blob URL each tab is currently allowed to capture, set by [arm] at injection time. */
    private val armedByTab = ConcurrentHashMap<String, String>()

    /** Capture ids opened by a tab, so a stale/foreign id can't be written to. */
    private val captureByTab = ConcurrentHashMap<String, String>()

    /** Allow [blobUrl] to be captured from [tabId] — called by `ContentHost` as it injects. */
    fun arm(tabId: String?, blobUrl: String) {
        if (tabId.isNullOrBlank() || !isBlobUrl(blobUrl)) return
        armedByTab[tabId] = blobUrl
    }

    /** Drop a tab's capture permission and abandon any capture still in flight (tab closed/navigated). */
    fun disarm(tabId: String?) {
        if (tabId.isNullOrBlank()) return
        armedByTab.remove(tabId)
        captureByTab.remove(tabId)?.let(BlobFileStore::abortCapture)
    }

    /**
     * Handle one bridge message. Returns the string handed back to the page: the capture id for a
     * `begin`, `"ok"` for an accepted chunk, `""` for anything refused (which tells the page to stop).
     */
    fun onPayload(tabId: String, payload: String): String {
        val obj = runCatching { JSONObject(payload) }.getOrNull() ?: return ""
        return when (obj.optString("t")) {
            "begin" -> begin(tabId, obj)
            "data" -> {
                val id = obj.optString("c").takeIf { it == captureByTab[tabId] } ?: return ""
                if (BlobFileStore.appendChunk(id, obj.optString("d"))) "ok" else {
                    captureByTab.remove(tabId)
                    ""
                }
            }
            "end" -> {
                val id = obj.optString("c").takeIf { it == captureByTab.remove(tabId) } ?: return ""
                if (BlobFileStore.finishCapture(id) != null) "ok" else ""
            }
            "abort" -> {
                val id = obj.optString("c").takeIf { it == captureByTab.remove(tabId) } ?: return ""
                BlobFileStore.abortCapture(id)
                "ok"
            }
            else -> ""
        }
    }

    private fun begin(tabId: String, obj: JSONObject): String {
        val blobUrl = obj.optString("u")
        // The tab may only capture the blob document it is actually showing (see the class doc).
        if (blobUrl.isEmpty() || armedByTab[tabId] != blobUrl) return ""
        // A second begin replaces the first — a page that reloads mid-capture shouldn't leak a
        // half-written file into the cache.
        captureByTab.remove(tabId)?.let(BlobFileStore::abortCapture)
        val id = BlobFileStore.beginCapture(
            blobUrl = blobUrl,
            mimeType = obj.optString("m"),
            fileName = obj.optString("n").takeIf { it.isNotBlank() },
            pageUrl = obj.optString("p").takeIf { it.isNotBlank() } ?: creatorUrl(blobUrl),
            expectedSize = obj.optLong("s"),
        ) ?: return ""
        captureByTab[tabId] = id
        return id
    }

    /**
     * The origin baked into a blob URL (`blob:https://site.com/uuid` → `https://site.com`) — the
     * page that minted it, used as provenance when the document has no referrer to offer.
     */
    private fun creatorUrl(blobUrl: String): String {
        val rest = blobUrl.removePrefix("blob:")
        val schemeEnd = rest.indexOf("//")
        if (schemeEnd < 0) return ""
        val pathStart = rest.indexOf('/', schemeEnd + 2)
        return if (pathStart < 0) rest else rest.substring(0, pathStart)
    }

    /**
     * The capture script, injected into a blob document by `ContentHost`. Flattened to one line at
     * the call site, so `/* */` comments only.
     */
    fun buildScript(): String {
        return ("""
            javascript:(function() {
                if (window.__tangentBlobCaptured) return;
                window.__tangentBlobCaptured = true;
                var url = '';
                try { url = location.href || ''; } catch (e) { return; }
                if (url.lastIndexOf('blob:', 0) !== 0) return;
                function ask(msg, val) {
                    var ifr = null, r = null;
                    try {
                        ifr = document.createElement('iframe');
                        ifr.setAttribute('aria-hidden', 'true');
                        ifr.style.cssText = 'display:none!important;width:0;height:0;border:0;position:absolute;left:-9999px;';
                        (document.body || document.documentElement).appendChild(ifr);
                        var w = ifr.contentWindow;
                        r = (w && typeof w.prompt === 'function') ? w.prompt(msg, val) : window.prompt(msg, val);
                    } catch (e) { try { r = window.prompt(msg, val); } catch (e2) {} }
                    finally { if (ifr && ifr.parentNode) { try { ifr.parentNode.removeChild(ifr); } catch (e3) {} } }
                    return r;
                }
                function post(o) { try { return ask('${BridgeGuard.tag(BRIDGE_MESSAGE)}', JSON.stringify(o)) || ''; } catch (e) { return ''; } }
                /* PDF.js and most viewers set the document title to the real filename — the only name
                   a generated blob ever carries. Anything that looks like a sentence is not one. */
                function guessName() {
                    try {
                        var t = (document.title || '').trim();
                        if (t && t.length < 80 && t.indexOf(' - ') < 0) return t;
                    } catch (e) {}
                    return '';
                }
                function b64(buf) {
                    var bytes = new Uint8Array(buf), out = '', step = 0x8000;
                    for (var i = 0; i < bytes.length; i += step) {
                        out += String.fromCharCode.apply(null, bytes.subarray(i, i + step));
                    }
                    return btoa(out);
                }
                fetch(url).then(function(r) { return r.blob(); }).then(function(blob) {
                    var id = post({ t: 'begin', u: url, m: blob.type || '', n: guessName(), p: document.referrer || '', s: blob.size });
                    if (!id) return;
                    var offset = 0;
                    function step() {
                        if (offset >= blob.size) { post({ t: 'end', c: id }); return; }
                        var end = Math.min(offset + $CHUNK_BYTES, blob.size);
                        var reader = new FileReader();
                        reader.onload = function() {
                            var res = '';
                            try { res = post({ t: 'data', c: id, d: b64(reader.result) }); } catch (e) {}
                            if (res !== 'ok') { post({ t: 'abort', c: id }); return; }
                            offset = end;
                            /* Yield between chunks: the bridge is synchronous, so a tight loop would
                               hold the page's main thread for the whole file. */
                            setTimeout(step, 0);
                        };
                        reader.onerror = function() { post({ t: 'abort', c: id }); };
                        try { reader.readAsArrayBuffer(blob.slice(offset, end)); } catch (e) { post({ t: 'abort', c: id }); }
                    }
                    step();
                }).catch(function() {});
            })();
        """).trimIndent().replace("\n", " ")
    }
}
