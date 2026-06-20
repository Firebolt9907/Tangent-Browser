package com.firebolt9907.luminabrowser.ui.reader

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
        val current = _activeReaderTabs.value.toMutableSet()
        if (active) {
            if (isReaderEligible(tabId)) {
                current.add(tabId)
            }
        } else {
            current.remove(tabId)
        }
        _activeReaderTabs.value = current
    }

    private val _pendingActivationTabs = MutableStateFlow<Set<String>>(emptySet())
    val pendingActivationTabs: StateFlow<Set<String>> = _pendingActivationTabs.asStateFlow()

    fun requestReaderActivation(tabId: String) {
        _pendingActivationTabs.value = _pendingActivationTabs.value + tabId
    }

    fun setReaderEligible(tabId: String, article: ReaderArticle) {
        val currentEligible = _eligibleReaderTabs.value.toMutableSet()
        currentEligible.add(tabId)
        _eligibleReaderTabs.value = currentEligible

        val currentArticles = _tabArticles.value.toMutableMap()
        currentArticles[tabId] = article
        _tabArticles.value = currentArticles

        if (_pendingActivationTabs.value.contains(tabId)) {
            _pendingActivationTabs.value = _pendingActivationTabs.value - tabId
            _activeReaderTabs.value = _activeReaderTabs.value + tabId
        }
    }

    fun clearReaderState(tabId: String) {
        val currentActive = _activeReaderTabs.value.toMutableSet()
        currentActive.remove(tabId)
        _activeReaderTabs.value = currentActive

        val currentEligible = _eligibleReaderTabs.value.toMutableSet()
        currentEligible.remove(tabId)
        _eligibleReaderTabs.value = currentEligible

        val currentArticles = _tabArticles.value.toMutableMap()
        currentArticles.remove(tabId)
        _tabArticles.value = currentArticles
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

