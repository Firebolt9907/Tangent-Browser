package com.firebolt9907.tangentbrowser.addons

import androidx.core.net.toUri

/**
 * Pure URL helpers for resolving add-on install sources — split out of `AddonController.kt` so the
 * controller holds runtime/install logic and these hold the AMO/`.xpi` URL parsing (unit-testable,
 * no engine dependencies). An install input can be an AMO slug, an AMO page URL, or a direct
 * `.xpi` link; these normalize it to a downloadable `.xpi` URL and recognize such URLs.
 */

internal fun String.toAddonInstallUrl(): String? {
    val trimmed = trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
        val uri = trimmed.toUri()
        val path = uri.path.orEmpty()
        if (path.endsWith(".xpi")) return trimmed
        val segments = uri.pathSegments
        val addonIndex = segments.indexOf("addon")
        // Guard against indexOf returning -1: addonIndex + 1 would then be 0, silently picking the
        // first path segment as the slug and building a bogus install URL for a non-/addon/ page.
        val slug = if (addonIndex >= 0) segments.getOrNull(addonIndex + 1) else null
        if (uri.host?.endsWith("addons.mozilla.org") == true && !slug.isNullOrBlank()) {
            return slug.toAmoLatestXpiUrl()
        }
        return trimmed
    }
    return trimmed.trim('/').takeIf { it.isNotBlank() }?.toAmoLatestXpiUrl()
}

internal fun String.toAmoLatestXpiUrl(): String =
    "https://addons.mozilla.org/firefox/downloads/latest/$this/latest.xpi"

/**
 * True only for an add-on download hosted on `addons.mozilla.org`. This gates the **automatic**
 * page-navigation install interception (`AddonController.installFromPageRequest`, wired to
 * `onLoadRequest` / `onExternalResource`), so it must stay AMO-only: a page-initiated navigation to
 * a `.xpi` on any other host (`location.href = "https://evil.com/x.xpi"`) must NOT be able to kick
 * off a high-privilege WebExtension install. The manual AddonsSheet path (`toAddonInstallUrl`, where
 * the user explicitly types/pastes a URL) is intentionally more permissive and is not gated by this.
 */
internal fun String.isAddonDownloadUrl(): Boolean {
    // Parse with java.net.URI (not android.net.Uri) so the host check is a real, JVM-testable
    // authority parse and a lookalike host like "addons.mozilla.org.evil.com" can't slip through.
    val uri = runCatching { java.net.URI(trim()) }.getOrNull() ?: return false
    // An AMO-shaped authority alone does not authenticate the download source.
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    if (uri.rawUserInfo != null || uri.port !in listOf(-1, 443)) return false
    val host = uri.host?.lowercase() ?: return false
    if (host != "addons.mozilla.org" && !host.endsWith(".addons.mozilla.org")) return false
    val path = uri.path.orEmpty()
    return path.endsWith(".xpi", ignoreCase = true) ||
        path.contains("/firefox/downloads/latest/")
}
