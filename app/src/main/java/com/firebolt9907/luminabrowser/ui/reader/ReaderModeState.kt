package com.firebolt9907.luminabrowser.ui.reader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ReaderArticle(
    val title: String,
    val byline: String?,
    val blocks: List<ReaderBlock>,
)

sealed class ReaderBlock {
    data class Heading(val level: Int, val text: String) : ReaderBlock()
    data class Paragraph(val text: String) : ReaderBlock()
    data class ListItem(val text: String, val ordered: Boolean, val index: Int = 0) : ReaderBlock()
    data class Blockquote(val text: String) : ReaderBlock()
}

enum class ReaderTheme { White, Sepia, Dark }

class ReaderManager {
    private val _activeReaderTabs = MutableStateFlow<Set<String>>(emptySet())
    val activeReaderTabs: StateFlow<Set<String>> = _activeReaderTabs.asStateFlow()

    private val _eligibleReaderTabs = MutableStateFlow<Set<String>>(emptySet())
    val eligibleReaderTabs: StateFlow<Set<String>> = _eligibleReaderTabs.asStateFlow()

    private val _tabArticles = MutableStateFlow<Map<String, ReaderArticle>>(emptyMap())
    val tabArticles: StateFlow<Map<String, ReaderArticle>> = _tabArticles.asStateFlow()

    private val _theme = MutableStateFlow(ReaderTheme.White)
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

    fun requestReaderActivation(tabId: String) {
        _pendingActivationTabs.update { it + tabId }
    }

    fun setReaderEligible(tabId: String, article: ReaderArticle) {
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

