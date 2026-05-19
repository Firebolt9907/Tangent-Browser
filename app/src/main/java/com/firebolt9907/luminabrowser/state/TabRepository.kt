package com.firebolt9907.luminabrowser.state

import kotlinx.coroutines.flow.StateFlow

interface TabRepository {
    val tabs: StateFlow<List<LuminaTab>>
    val selectedTabId: StateFlow<String?>

    fun selectTab(id: String)

    /**
     * Move the selected tab by [delta] positions. +1 selects the next tab,
     * -1 the previous. The new index is clamped to [0, lastIndex] — there is
     * no wraparound. No-op when there are no tabs or no current selection.
     */
    fun selectAdjacent(delta: Int)

    fun loadUrl(url: String, tabId: String? = selectedTabId.value)

    /**
     * Add a new tab with the given [url] and [title]. Returns the id of the
     * newly created tab.
     */
    fun addTab(url: String, title: String): String

    fun removeTab(id: String)
}
