package com.firebolt9907.tangentbrowser

import android.content.Intent

/**
 * Shared URL extraction for incoming intents. Used by both [CustomTabActivity] (external
 * `ACTION_VIEW` / `ACTION_SEND` links, which open in the Custom-Tab surface) and [MainActivity]
 * (its own "Add to Home Screen" shortcut intents). Keeping this in one place means the two
 * activities can't drift on how a shared-text URL is parsed.
 */
internal object IntentUrls {

    /**
     * Schemes we are willing to load from an **external** caller (another app's intent). Everything
     * that arrives from outside the app is untrusted, so we only accept ordinary web schemes plus a
     * schemeless string (which [normalizeUrl] turns into an https/search URL). This is the boundary
     * that stops a hostile app from making the browser open a privileged local scheme
     * (`file:`, `content:`, `javascript:`, `data:`, `blob:`, …) via an exported entry point — see
     * [sanitizeExternalUrl]. Note this is deliberately *not* enforced in [normalizeUrl]: a URL the
     * **user** types (e.g. `file:///sdcard/page.html`) is trusted and must still load.
     */
    private val SAFE_EXTERNAL_SCHEMES = setOf("http", "https")

    // Scheme = leading token before the first ':' (RFC 3986 shape). Mirrors GeckoTabRepository's
    // explicitSchemeRegex so "has a scheme" means the same thing on both sides of the boundary.
    private val schemeRegex = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")

    /** The URL an incoming intent wants to open, or null if none / not safe to open externally. */
    fun extractUrl(intent: Intent?): String? {
        if (intent == null) return null
        val raw = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString?.takeIf { it.isNotBlank() }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
                if (text.isEmpty()) null else firstUrlIn(text) ?: text
            }
            else -> null
        }
        return sanitizeExternalUrl(raw)
    }

    /**
     * Returns [raw] if it is safe to open on behalf of an untrusted external caller, else null.
     * A string with no explicit scheme is allowed (it becomes an https/search URL downstream); a
     * string *with* a scheme is allowed only if that scheme is in [SAFE_EXTERNAL_SCHEMES]. Blocks
     * `javascript:`, `file:`, `content:`, `data:`, `intent:` and every other privileged/local scheme
     * an attacker might use to read app-private data or inject script.
     */
    fun sanitizeExternalUrl(raw: String?): String? {
        val url = raw?.trim()?.takeIf { it.isNotBlank() } ?: return null
        val scheme = schemeRegex.find(url)?.groupValues?.get(1)
            ?: return url // no scheme → normalizeUrl makes it https/search
        return url.takeIf { scheme.lowercase() in SAFE_EXTERNAL_SCHEMES }
    }

    private fun firstUrlIn(text: String): String? {
        val match = Regex("https?://\\S+").find(text) ?: return null
        // \S+ greedily swallows trailing sentence punctuation when a URL is shared inside prose
        // (e.g. "Read this https://example.com!"). Those terminators aren't part of the URL and
        // would otherwise be loaded literally, so trim the common ones.
        return match.value.trimEnd('.', ',', '!', '?', ';', ':', ')', ']', '}', '"', '\'')
            .takeIf { it.isNotBlank() }
    }
}
