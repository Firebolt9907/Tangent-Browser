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
        get() = bodyTextLength >= MIN_AUTO_READER_BODY_CHARS

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
}

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

    fun setReaderEligible(tabId: String, article: ReaderArticle) {
        android.util.Log.d("ReaderDbg", "setReaderEligible tab=$tabId pending=${_pendingActivationTabs.value.contains(tabId)}")
        _eligibleReaderTabs.update { it + tabId }
        _tabArticles.update { it + (tabId to article) }

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

