package com.firebolt9907.luminabrowser

import android.content.Intent

/**
 * Shared URL extraction for incoming intents. Used by both [CustomTabActivity] (external
 * `ACTION_VIEW` / `ACTION_SEND` links, which open in the Custom-Tab surface) and [MainActivity]
 * (its own "Add to Home Screen" shortcut intents). Keeping this in one place means the two
 * activities can't drift on how a shared-text URL is parsed.
 */
internal object IntentUrls {

    /** The URL an incoming intent wants to open, or null if none. */
    fun extractUrl(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString?.takeIf { it.isNotBlank() }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
                if (text.isEmpty()) null else firstUrlIn(text) ?: text
            }
            else -> null
        }
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
