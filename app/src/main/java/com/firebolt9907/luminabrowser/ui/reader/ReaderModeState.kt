package com.firebolt9907.luminabrowser.ui.reader

import com.firebolt9907.luminabrowser.state.GlobalPreferencesStore
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
)

data class ReaderLinkSpan(
    val text: String,
    val href: String,
)

sealed class ReaderBlock {
    data class Heading(val level: Int, val text: String) : ReaderBlock()
    data class Paragraph(
        val text: String,
        val links: List<ReaderLinkSpan> = emptyList(),
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

    private val _useGoogleCache = MutableStateFlow(false)
    val useGoogleCache: StateFlow<Boolean> = _useGoogleCache.asStateFlow()

    private val _originalUrls = MutableStateFlow<Map<String, String>>(emptyMap())
    val originalUrls: StateFlow<Map<String, String>> = _originalUrls.asStateFlow()

    private val _restoringTabs = MutableStateFlow<Set<String>>(emptySet())
    val restoringTabs: StateFlow<Set<String>> = _restoringTabs.asStateFlow()

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

        if (_pendingActivationTabs.value.contains(tabId)) {
            _pendingActivationTabs.update { it - tabId }
            _activeReaderTabs.update { it + tabId }
        }
    }

    fun clearReaderState(tabId: String) {
        _activeReaderTabs.update { it - tabId }
        _eligibleReaderTabs.update { it - tabId }
        _tabArticles.update { it - tabId }
        _originalUrls.update { it - tabId }
        _restoringTabs.update { it - tabId }
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

    fun setUseGoogleCache(enabled: Boolean) {
        _useGoogleCache.value = enabled
    }

    fun setOriginalUrl(tabId: String, url: String?) {
        _originalUrls.update { current ->
            if (url == null) current - tabId else current + (tabId to url)
        }
    }

    fun getOriginalUrl(tabId: String): String? = _originalUrls.value[tabId]

    fun setRestoring(tabId: String, restoring: Boolean) {
        _restoringTabs.update { if (restoring) it + tabId else it - tabId }
    }

    fun isRestoring(tabId: String): Boolean = _restoringTabs.value.contains(tabId)

    companion object {
        val instance = ReaderManager()
    }
}

/**
 * Loads the persisted reader appearance (theme / font size / font / Google-cache toggle) into the
 * Context-less [ReaderManager] singleton. Both browser surfaces call this on launch — the main
 * browser (which also mirrors changes back to the store) and Custom Tabs, which may start in their
 * own task without MainActivity ever having hydrated the manager.
 */
fun ReaderManager.hydrateAppearanceFromPrefs(prefs: GlobalPreferencesStore) {
    prefs.readerThemeName
        ?.let { name -> runCatching { ReaderTheme.valueOf(name) }.getOrNull() }
        ?.let { setTheme(it) }
    setFontSize(prefs.readerFontSize)
    setFontName(prefs.readerFontName)
    setUseGoogleCache(prefs.useGoogleCache)
}

fun shouldRedirectToGoogleCache(url: String): Boolean {
    return (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) &&
            !url.startsWith("https://webcache.googleusercontent.com/", ignoreCase = true)
}

fun getGoogleCacheUrl(originalUrl: String): String {
    if (originalUrl.startsWith("https://webcache.googleusercontent.com/")) {
        return originalUrl
    }
    val encoded = runCatching { java.net.URLEncoder.encode(originalUrl, "UTF-8") }.getOrDefault(originalUrl)
    return "https://webcache.googleusercontent.com/search?q=cache:$encoded"
}

