package com.firebolt9907.luminabrowser.state

import kotlinx.coroutines.flow.StateFlow

/** A reopenable recently-closed tab (URL + title only; no session history is retained). */
data class ClosedTabInfo(val id: String, val url: String, val title: String)

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

    /** Number of recently-closed tabs available to reopen; drives the "Reopen Closed Tab" UI. */
    val reopenableCount: StateFlow<Int>

    /**
     * Reopen (and select) the most-recently-closed tab. Returns the new tab id, or null when there
     * is nothing to reopen.
     */
    fun reopenLastClosedTab(): String?

    /** Recently-closed tabs (most-recent first) for the "Recently closed" list. */
    val recentlyClosedTabs: StateFlow<List<ClosedTabInfo>>

    /** Reopen a specific recently-closed tab by id; returns the new tab id, or null if gone. */
    fun reopenClosedTab(id: String): String?
}
