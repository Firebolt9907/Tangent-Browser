package com.firebolt9907.tangentbrowser.ui.reader

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
            is ReaderBlock.Figure -> null
            is ReaderBlock.SocialPost -> listOfNotNull(block.title, block.text).joinToString(" ")
            is ReaderBlock.SocialComment -> block.text
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

/**
 * Tidy a social-post timestamp for display. Platforms hand back either a human string ("2h", "Jul 3")
 * or a raw ISO-8601 instant ("2026-07-03T14:39:00.000Z"); for the latter we show just the date part so
 * the header stays compact. Null/blank in → null out (the line is then omitted).
 */
internal fun formatSocialTimestamp(ts: String?): String? {
    val t = ts?.trim()?.takeIf { it.isNotEmpty() && it != "null" } ?: return null
    val tIndex = t.indexOf('T')
    // ISO instant: keep the date (YYYY-MM-DD) only when it really looks like one.
    if (tIndex == 10 && t.getOrNull(4) == '-' && t.getOrNull(7) == '-') return t.substring(0, 10)
    return t
}

/**
 * Apollo-style nested comment "threadline" colours, one per indent level (cycled beyond the last).
 * Apollo's signature is a rainbow of thin vertical rails down the left of nested replies, so a
 * conversation's shape is readable at a glance; [ReaderSocialCard] draws rail level `i` in
 * `commentRailColors[i % size]`.
 */
internal val commentRailColors: List<androidx.compose.ui.graphics.Color> = listOf(
    androidx.compose.ui.graphics.Color(0xFF2D9CDB), // blue
    androidx.compose.ui.graphics.Color(0xFF27AE60), // green
    androidx.compose.ui.graphics.Color(0xFFF2C94C), // yellow
    androidx.compose.ui.graphics.Color(0xFFF2994A), // orange
    androidx.compose.ui.graphics.Color(0xFFEB5757), // red
    androidx.compose.ui.graphics.Color(0xFFBB6BD9), // purple
    androidx.compose.ui.graphics.Color(0xFF56CCF2), // cyan
)

/** A deterministic accent colour for an author's letter-tile avatar, seeded by their name. */
internal fun avatarColorFor(seed: String): androidx.compose.ui.graphics.Color {
    val palette = longArrayOf(
        0xFF5C6BC0, 0xFF26A69A, 0xFFEF5350, 0xFFAB47BC, 0xFF42A5F5,
        0xFF66BB6A, 0xFFFFA726, 0xFFEC407A, 0xFF7E57C2, 0xFF29B6F6,
    )
    val h = seed.fold(0) { acc, c -> acc * 31 + c.code }
    val idx = ((h % palette.size) + palette.size) % palette.size
    return androidx.compose.ui.graphics.Color(palette[idx])
}

internal fun getFontFamily(fontName: String): FontFamily {
    return when (fontName) {
        "Georgia" -> FontFamily(Typeface.create("georgia", Typeface.NORMAL))
        "Charter" -> FontFamily(Typeface.create("charter", Typeface.NORMAL))
        "Athelas" -> FontFamily(Typeface.create("athelas", Typeface.NORMAL))
        else -> FontFamily.Default
    }
}
