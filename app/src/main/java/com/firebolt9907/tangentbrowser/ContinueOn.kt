package com.firebolt9907.tangentbrowser

import java.net.URI

/** Portable web addresses only; this validates an existing URL without normalizing user input. */
internal object ContinueOn {
    fun webUrl(url: String?, private: Boolean): String? {
        if (private || url.isNullOrBlank()) return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.host.isNullOrBlank()) return null
        // Embedded HTTP credentials must never travel to another device.
        if (uri.rawUserInfo != null) return null
        return url
    }
}
