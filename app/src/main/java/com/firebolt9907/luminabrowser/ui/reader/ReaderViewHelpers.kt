package com.firebolt9907.luminabrowser.ui.reader

import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import coil.request.ImageRequest

/**
 * Non-composable helpers for [ReaderView] — image-request building, read-time estimation, host
 * formatting, and font resolution. Split out of `ReaderView.kt` so the composable there stays
 * focused on layout and these stay unit-testable plumbing.
 */

/**
 * Builds the Coil request for an article image. Sends a Referer (the article page) and a browser
 * User-Agent so CDNs with hotlink protection — which reject requests carrying no/foreign referer or
 * a non-browser UA — still serve the picture, and crossfades the result in. Shared by the inline
 * image and the fullscreen zoom viewer so both use identical headers/caching.
 */
internal fun readerImageRequest(
    context: android.content.Context,
    src: String,
    sourceUrl: String?,
): ImageRequest =
    ImageRequest.Builder(context)
        .data(src)
        .crossfade(true)
        .setHeader("User-Agent", READER_IMAGE_USER_AGENT)
        .apply { refererFor(sourceUrl, src)?.let { setHeader("Referer", it) } }
        .build()

/** A desktop-Chrome UA. Some CDNs 403 non-browser user agents; a realistic one avoids that. */
private const val READER_IMAGE_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/122.0.0.0 Safari/537.36"

/**
 * The Referer to send when fetching an article image. Prefers the page the article came from (what
 * most hotlink checks allowlist); falls back to the image's own origin so we still send *something*
 * plausible when the source URL is unknown. Returns null only if neither yields a usable origin.
 */
private fun refererFor(sourceUrl: String?, imageSrc: String): String? {
    sourceUrl?.let { if (it.startsWith("http")) return it }
    return runCatching {
        val uri = android.net.Uri.parse(imageSrc)
        val scheme = uri.scheme
        val host = uri.host
        if (scheme != null && host != null) "$scheme://$host/" else null
    }.getOrNull()
}

/**
 * Estimated minutes to read the article at ~220 wpm (a common adult prose reading rate). Counts
 * words in prose blocks (paragraphs, headings, blockquotes, list items); skips images and code,
 * which aren't "read" at prose speed. Returns 0 for trivially short extractions so the header line
 * is suppressed rather than showing a meaningless "1 min read" on a stub.
 */
// Compiled once — the word split runs per prose block, so a long article would otherwise
// recompile this for every paragraph.
private val whitespaceRegex = Regex("\\s+")

internal fun estimateReadMinutes(article: ReaderArticle): Int {
    var words = 0
    for (block in article.blocks) {
        val text = when (block) {
            is ReaderBlock.Paragraph -> block.text
            is ReaderBlock.Heading -> block.text
            is ReaderBlock.Blockquote -> block.text
            is ReaderBlock.ListItem -> block.text
            is ReaderBlock.Table -> block.rows.joinToString(" ") { it.joinToString(" ") }
            is ReaderBlock.Code -> null
            is ReaderBlock.Divider -> null
            is ReaderBlock.Image -> null
        } ?: continue
        if (text.isNotBlank()) words += text.trim().split(whitespaceRegex).size
    }
    if (words < 60) return 0
    return Math.max(1, Math.round(words / 220f))
}

/**
 * The display host for the reader header (e.g. "nytimes.com"), stripped of a leading "www." and
 * lowercased. Null when [sourceUrl] is absent or has no host, so the meta line omits it cleanly.
 */
internal fun readerHost(sourceUrl: String?): String? {
    val url = sourceUrl?.takeIf { it.isNotBlank() } ?: return null
    // java.net.URI (not android.net.Uri) so this is testable on the JVM without Robolectric.
    return runCatching {
        java.net.URI(url).host
            ?.removePrefix("www.")
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull()
}

internal fun getFontFamily(fontName: String): FontFamily {
    return when (fontName) {
        "Georgia" -> FontFamily(Typeface.create("georgia", Typeface.NORMAL))
        "Charter" -> FontFamily(Typeface.create("charter", Typeface.NORMAL))
        "Athelas" -> FontFamily(Typeface.create("athelas", Typeface.NORMAL))
        else -> FontFamily.Default
    }
}
