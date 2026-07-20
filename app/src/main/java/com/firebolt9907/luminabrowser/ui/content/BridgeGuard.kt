package com.firebolt9907.luminabrowser.ui.content

import android.util.Base64
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Security guard for the shared `window.prompt` bridge — the `lumina-*` channel the autofill / UI
 * probes ([OtpAutofill], [LoginFieldProbe], [FormFieldMemory], [ThemeColorProbe],
 * [com.firebolt9907.luminabrowser.ui.content.LinkRectProbe]) use to smuggle data to the host through
 * `ReaderExtractor`'s prompt delegate.
 *
 * The bridge is intercepted by matching the prompt *message*. Without protection **any** script in
 * the page realm (including a third-party ad/analytics script) can call
 * `window.prompt("lumina-formfill-get", "victim.com email")` and either drive our probes or read back
 * stored per-site data. Two defenses:
 *
 *  1. **Unforgeable channel (nonce).** Every message our injected scripts emit is tagged
 *     `name~<nonce>`, where `<nonce>` is a per-process `SecureRandom` token embedded only inside the
 *     injected-script closures — it is never written to the DOM and page scripts can't read an
 *     already-executed `javascript:` URL's source. The delegate rejects any `lumina-*~…` whose nonce
 *     doesn't match, so page JS can't address the bridge. (The scripts already fetch a pristine
 *     `prompt` from a throwaway same-origin iframe, so a page that patched `window.prompt` can't
 *     observe the nonce either.)
 *  2. **Trusted-origin binding.** Handlers that read/write stored per-site data ([FormFieldMemory])
 *     MUST NOT trust a page-supplied origin — a page could claim another site's host and read its
 *     data. `ContentHost` records the real current URL per tab here; the handler keys storage by that
 *     origin, ignoring whatever host the page's payload claims.
 */
object BridgeGuard {
    /** Separator between a bridge message name and the nonce. URL/JS-safe and absent from names. */
    private const val SEP = '~'

    /** Per-process, unguessable channel token. Regenerated each app process. */
    val nonce: String = run {
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
            .trim()
            .replace(SEP, '_')
    }

    /** Wrap a bridge message [name] with the process nonce, for embedding in an injected script. */
    fun tag(name: String): String = "$name$SEP$nonce"

    /**
     * Resolve a received prompt [message] against the bridge protocol:
     *  - `Foreign` — not one of ours (no separator, or the name isn't in the `lumina-` namespace);
     *    the caller should fall through to legacy handling / forward to the wrapped delegate.
     *  - `Rejected` — a `lumina-*~…` message whose nonce is wrong/missing: a forged call. The caller
     *    should swallow it silently (do nothing).
     *  - `Accepted(name)` — an authentic call; dispatch by [Resolved.Accepted.name].
     */
    fun resolve(message: String?): Resolved {
        val msg = message ?: return Resolved.Foreign
        val sep = msg.indexOf(SEP)
        if (sep <= 0) return Resolved.Foreign
        val name = msg.substring(0, sep)
        if (!name.startsWith("lumina-")) return Resolved.Foreign
        return if (msg.substring(sep + 1) == nonce) Resolved.Accepted(name) else Resolved.Rejected
    }

    sealed interface Resolved {
        object Foreign : Resolved
        object Rejected : Resolved
        data class Accepted(val name: String) : Resolved
    }

    // ---- Trusted-origin registry -------------------------------------------------------------

    private val originByTab = ConcurrentHashMap<String, String>()

    /** Record the real current URL for [tabId]; a non-web/blank URL clears any prior origin. */
    fun setTrustedOrigin(tabId: String?, url: String?) {
        if (tabId.isNullOrBlank()) return
        val origin = originOf(url)
        if (origin != null) originByTab[tabId] = origin else originByTab.remove(tabId)
    }

    /** The trusted origin for [tabId], or null if none recorded (handlers must fail closed). */
    fun trustedOrigin(tabId: String): String? = originByTab[tabId]

    fun forget(tabId: String?) {
        if (!tabId.isNullOrBlank()) originByTab.remove(tabId)
    }

    /**
     * Full origin (`scheme://host[:port]`) of a web [url], or null for opaque/blank/non-http(s) URLs.
     * Keying stored per-site data by the *full* origin (not the bare host) keeps `https://bank.com`
     * and a downgraded/MITM'd `http://bank.com` in separate buckets — a plaintext origin must never
     * read values captured over TLS. Uses `java.net.URI` (not `android.net.Uri`) so it's a real
     * authority parse and JVM-testable, mirroring `sitePermissionOriginKey`.
     */
    fun originOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val parsed = runCatching { URI(url) }.getOrNull() ?: return null
        val scheme = parsed.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
        val host = parsed.host?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        val port = parsed.port
        return if (port >= 0) "$scheme://$host:$port" else "$scheme://$host"
    }
}
