package com.firebolt9907.tangentbrowser.ui.reader

import com.firebolt9907.tangentbrowser.state.GlobalPreferencesStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ReaderArticle(
    val title: String,
    val byline: String?,
    val blocks: List<ReaderBlock>,
    /**
     * The page the article was extracted from. Used as the `Referer` when loading article images so
     * CDNs with hotlink protection (which 403 requests that carry no/foreign referer) serve them.
     */
    val sourceUrl: String? = null,
    /**
     * True when this "article" is really a social-media thread (Reddit/X/Mastodon/Bluesky) extracted
     * by [SocialExtractor] rather than the prose extractor. Social threads carry [ReaderBlock.SocialPost]
     * / [ReaderBlock.SocialComment] blocks and are rendered as attributed cards. A short post is still
     * worth showing, so [hasSubstantialBody] is unconditionally true for social threads (the article
     * prose floor doesn't apply — a two-line viral tweet is a legitimate reader target).
     */
    val isSocial: Boolean = false,
) {
    /**
     * Total length of the extracted *prose* — paragraphs, list items, blockquotes, code, and table
     * cells. Headings and images are excluded: a page can produce a title + a hero image + a couple
     * of nav headings and still be extracted as an "article" with essentially no body to read.
     */
    val bodyTextLength: Int by lazy {
        blocks.sumOf { block ->
            when (block) {
                is ReaderBlock.Paragraph -> block.text.length
                is ReaderBlock.ListItem -> block.text.length
                is ReaderBlock.Blockquote -> block.text.length
                is ReaderBlock.Code -> block.text.length
                is ReaderBlock.Table -> block.rows.sumOf { row -> row.sumOf { it.length } }
                is ReaderBlock.SocialPost -> block.text.length + (block.title?.length ?: 0)
                is ReaderBlock.SocialComment -> block.text.length
                else -> 0
            }
        }
    }

    /**
     * Whether the article carries enough body text to be worth auto-opening into reader view. Guards
     * against auto-activating on pages the extractor technically "parsed" but that have little real
     * prose (landing pages, image galleries, link hubs). Manual reader activation is *not* gated on
     * this — the user can still force reader mode on a thin page from the aA sheet.
     */
    val hasSubstantialBody: Boolean
        get() = isSocial || bodyTextLength >= MIN_AUTO_READER_BODY_CHARS

    companion object {
        /**
         * Minimum extracted prose length (characters) for reader mode to engage *automatically*.
         * Mirrors Readability's own `charThreshold` default of 500 — below this a page reads more
         * like a stub than an article.
         */
        const val MIN_AUTO_READER_BODY_CHARS = 500
    }
}

data class ReaderLinkSpan(
    val text: String,
    val href: String,
)

/**
 * A rendered inline-math glyph (LaTeX flowing within a line of prose). [src] is a data:image/svg+xml
 * URI (MathJax inline output) or a remote SVG/PNG render (e.g. Wikipedia's math endpoint). Each
 * occurrence is marked in the paragraph's [ReaderBlock.Paragraph.text] by the private-use sentinel
 * [MATH_SENTINEL], matched to this list in order and drawn via Compose inlineContent so the equation
 * sits on the baseline instead of being dropped (imgs carry no text) or shown as a separate block.
 */
data class ReaderInlineMath(
    val src: String,
    val width: Int? = null,
    val height: Int? = null,
)

/** Private-use codepoint standing in for an inline-math glyph in paragraph text. Kept in sync with
 *  the extractor's `MATH_SENTINEL`. */
const val MATH_SENTINEL: Char = '\uE000'

sealed class ReaderBlock {
    data class Heading(val level: Int, val text: String) : ReaderBlock()
    data class Paragraph(
        val text: String,
        val links: List<ReaderLinkSpan> = emptyList(),
        /** Inline math glyphs, in order, each marked by a [MATH_SENTINEL] in [text]. */
        val inlineMath: List<ReaderInlineMath> = emptyList(),
    ) : ReaderBlock()
    data class ListItem(val text: String, val ordered: Boolean, val index: Int = 0, val depth: Int = 0) : ReaderBlock()
    data class Blockquote(val text: String, val pullQuote: Boolean = false) : ReaderBlock()
    /** A `<pre>`/code block. [text] preserves the original line breaks and indentation. */
    data class Code(val text: String) : ReaderBlock()
    /** A data table. [rows] is row-major cell text; [hasHeader] marks the first row as headers. */
    data class Table(val rows: List<List<String>>, val hasHeader: Boolean) : ReaderBlock()
    /** An `<hr>` section divider. */
    object Divider : ReaderBlock()
    /**
     * Article image. [src] is an absolute URL; [caption] is the figure caption or alt text, if any.
     * [width]/[height] are the intrinsic pixel dimensions when known, used to reserve aspect-ratio
     * space so the article doesn't reflow as images stream in; null when unknown (e.g. og:image).
     */
    data class Image(
        val src: String,
        val caption: String?,
        val width: Int? = null,
        val height: Int? = null,
    ) : ReaderBlock()

    /**
     * A rich graphical element the plain text/[Image] model can't represent faithfully — an inline
     * `<svg>` chart or diagram, or a rendered math equation (sites like Wikipedia serve these as SVG
     * renders). [src] is either a `data:image/svg+xml` URI (serialized inline SVG) or a remote
     * SVG/PNG URL (e.g. Wikimedia's `.../math/render/svg/…`). These are dropped by the plain walk
     * because they carry no `<p>` text; capturing them keeps charts, diagrams and equations in the
     * reader. Rendered as a crisp vector on a light card so black-stroke vectors stay visible on the
     * dark reader theme. [equation] display math is centred and sized to content rather than
     * full-bleed.
     */
    data class Figure(
        val src: String,
        val caption: String?,
        val equation: Boolean = false,
        val width: Int? = null,
        val height: Int? = null,
    ) : ReaderBlock()

    /**
     * The root post of a social-media thread (Reddit submission, tweet/X post, Mastodon status,
     * Bluesky post). Rendered as an attributed card at the top of the social reader. [handle] is the
     * secondary identity line (`u/…`, `@handle`, community/instance); [meta] is a pre-formatted stats
     * line (score/points, likes, timestamp). [title] is present only where the platform has a distinct
     * post title (Reddit); tweets/statuses have none. [media] carries inline images (videos are
     * dropped — the reader is a text-first surface). Populated by [SocialExtractor]; see [ReaderMedia].
     */
    data class SocialPost(
        val author: String,
        val handle: String?,
        val avatarUrl: String?,
        val timestamp: String?,
        val meta: String?,
        val title: String?,
        val text: String,
        val links: List<ReaderLinkSpan> = emptyList(),
        val media: List<ReaderMedia> = emptyList(),
    ) : ReaderBlock()

    /**
     * A reply/comment in a social-media thread. [depth] nests threaded replies (0 = top level), used
     * to indent the card and draw a hierarchy rail — mirrors [ListItem.depth]. [isOp] marks a reply
     * written by the thread's original poster (highlighted, "OP" tag), matched by handle in
     * [SocialExtractor]. Platforms whose web UI is a flat thread (X, Mastodon, Bluesky) emit depth 0.
     */
    data class SocialComment(
        val author: String,
        val handle: String?,
        val avatarUrl: String?,
        val timestamp: String?,
        val meta: String?,
        val text: String,
        val links: List<ReaderLinkSpan> = emptyList(),
        val media: List<ReaderMedia> = emptyList(),
        val depth: Int = 0,
        val isOp: Boolean = false,
    ) : ReaderBlock()
}

/**
 * An inline image attached to a social post/comment. [src] is an absolute URL; [width]/[height] are
 * intrinsic pixel dimensions when known (to reserve aspect-ratio space and avoid reflow), null
 * otherwise.
 */
data class ReaderMedia(
    val src: String,
    val width: Int? = null,
    val height: Int? = null,
    /** True when [src] is an animated GIF — the reader animates it and shows a small "GIF" badge. */
    val gif: Boolean = false,
)

enum class ReaderTheme { System, White, Sepia, Dark }

class ReaderManager {
    private val _activeReaderTabs = MutableStateFlow<Set<String>>(emptySet())
    val activeReaderTabs: StateFlow<Set<String>> = _activeReaderTabs.asStateFlow()

    private val _eligibleReaderTabs = MutableStateFlow<Set<String>>(emptySet())
    val eligibleReaderTabs: StateFlow<Set<String>> = _eligibleReaderTabs.asStateFlow()

    private val _tabArticles = MutableStateFlow<Map<String, ReaderArticle>>(emptyMap())
    val tabArticles: StateFlow<Map<String, ReaderArticle>> = _tabArticles.asStateFlow()

    private val _theme = MutableStateFlow(ReaderTheme.System)
    val theme: StateFlow<ReaderTheme> = _theme.asStateFlow()

    private val _fontSize = MutableStateFlow(100) // Font size percentage: e.g. 100%, 115%
    val fontSize: StateFlow<Int> = _fontSize.asStateFlow()

    private val _fontName = MutableStateFlow("System") // System, Georgia, Charter, Athelas
    val fontName: StateFlow<String> = _fontName.asStateFlow()

    /**
     * Notified of **every** extraction outcome for [tabId] — including the silent auto-extract pass
     * that [reportReaderUnavailable] deliberately keeps quiet for UI purposes. `extracted` is true
     * only when the page yielded an article with real prose ([ReaderArticle.hasSubstantialBody]), so
     * a technically-parsed stub counts as a failure.
     *
     * Set by the DI graph to feed [com.firebolt9907.tangentbrowser.state.ReaderHostEvidence], which
     * is how the data saver learns which hosts it can strip first-party script/CSS from without
     * loading them first. The listener resolves the tab's URL itself — this manager is Context- and
     * store-less by design.
     */
    @Volatile
    var outcomeListener: ((tabId: String, extracted: Boolean) -> Unit)? = null

    fun isReaderActive(tabId: String): Boolean = _activeReaderTabs.value.contains(tabId)
    
    fun isReaderEligible(tabId: String): Boolean = _eligibleReaderTabs.value.contains(tabId)

    fun getArticle(tabId: String): ReaderArticle? = _tabArticles.value[tabId]

    fun setReaderActive(tabId: String, active: Boolean) {
        // update {} is atomic (compare-and-set); this state is touched from both Gecko delegate
        // callbacks and Compose, so a read-then-set could otherwise lose a concurrent write.
        _activeReaderTabs.update { current ->
            when {
                active && isReaderEligible(tabId) -> current + tabId
                active -> current
                else -> current - tabId
            }
        }
    }

    private val _pendingActivationTabs = MutableStateFlow<Set<String>>(emptySet())
    val pendingActivationTabs: StateFlow<Set<String>> = _pendingActivationTabs.asStateFlow()

    fun isReaderPending(tabId: String): Boolean = _pendingActivationTabs.value.contains(tabId)

    fun requestReaderActivation(tabId: String) {
        _pendingActivationTabs.update { it + tabId }
    }

    // A one-shot "reader isn't available for this page" signal, carrying the tab it applies to. Set
    // only when extraction reports no article *while a manual activation is pending*, so it never
    // fires on the silent auto-extract pass. UI (TangentRoot) shows a transient banner and clears it.
    private val _readerUnavailableTab = MutableStateFlow<String?>(null)
    val readerUnavailableTab: StateFlow<String?> = _readerUnavailableTab.asStateFlow()

    /**
     * Report that extraction found no readable article for [tabId]. No-op unless the tab has a
     * pending (user-initiated) activation — an auto-extract pass that comes up empty stays silent.
     * Consumes the pending flag and raises [readerUnavailableTab] so the manual attempt gets visible
     * feedback instead of appearing to do nothing.
     */
    fun reportReaderUnavailable(tabId: String) {
        // Ahead of the pending-activation gate below: the auto pass staying silent is a *UI* rule,
        // but a host that can't be extracted is exactly what the data saver needs to hear about.
        outcomeListener?.invoke(tabId, false)
        if (!_pendingActivationTabs.value.contains(tabId)) return
        _pendingActivationTabs.update { it - tabId }
        _readerUnavailableTab.value = tabId
    }

    /** Clear the unavailable signal once the banner has been shown/dismissed. */
    fun clearReaderUnavailable(tabId: String? = null) {
        _readerUnavailableTab.update { current -> if (tabId == null || tabId == current) null else current }
    }

    fun setReaderEligible(tabId: String, article: ReaderArticle) {
        com.firebolt9907.tangentbrowser.util.TangentLog.debug("ReaderDbg") { "setReaderEligible tab=$tabId pending=${_pendingActivationTabs.value.contains(tabId)}" }
        _eligibleReaderTabs.update { it + tabId }
        _tabArticles.update { it + (tabId to article) }
        // Only a page with real prose counts as evidence the host is reader-readable — the same bar
        // auto-activation uses. A thin page stays eligible for manual reader but must not license the
        // data saver to strip that host's script and CSS on the next visit.
        outcomeListener?.invoke(tabId, article.hasSubstantialBody)

        // Only fulfill a pending auto-activation when the page actually has enough prose to read;
        // a thin page stays eligible (reader can be turned on by hand) but won't pop into reader
        // view on its own.
        if (_pendingActivationTabs.value.contains(tabId) && article.hasSubstantialBody) {
            _pendingActivationTabs.update { it - tabId }
            _activeReaderTabs.update { it + tabId }
        }
    }

    fun clearReaderState(tabId: String) {
        _activeReaderTabs.update { it - tabId }
        _eligibleReaderTabs.update { it - tabId }
        _tabArticles.update { it - tabId }
        // If this tab was being read aloud, its content is now stale — stop TTS.
        PageSpeaker.stopForTab(tabId)
    }

    fun setTheme(newTheme: ReaderTheme) {
        _theme.value = newTheme
    }

    fun setFontSize(newSize: Int) {
        _fontSize.value = newSize.coerceIn(50, 200)
    }

    fun setFontName(newFont: String) {
        _fontName.value = newFont
    }

    companion object {
        val instance = ReaderManager()
    }
}

/**
 * Loads the persisted reader appearance (theme / font size / font) into the Context-less
 * [ReaderManager] singleton. Both browser surfaces call this on launch — the main browser (which
 * also mirrors changes back to the store) and Custom Tabs, which may start in their own task without
 * MainActivity ever having hydrated the manager.
 */
fun ReaderManager.hydrateAppearanceFromPrefs(prefs: GlobalPreferencesStore) {
    prefs.readerThemeName
        ?.let { name -> runCatching { ReaderTheme.valueOf(name) }.getOrNull() }
        ?.let { setTheme(it) }
    setFontSize(prefs.readerFontSize)
    setFontName(prefs.readerFontName)
}

