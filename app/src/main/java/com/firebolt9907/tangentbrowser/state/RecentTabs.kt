package com.firebolt9907.tangentbrowser.state

import mozilla.components.browser.state.action.TabListAction
import mozilla.components.browser.state.state.BrowserState

internal const val TAB_RECENCY_DWELL_MS = 15_000L

/** Tangent's stack is oldest-first: the last tab is the top/newest card. */
internal fun recentTabMove(state: BrowserState, viewedTabId: String): TabListAction.MoveTabsAction? {
    // A delayed promotion must never affect a tab the user has since left or closed.
    if (state.selectedTabId != viewedTabId) return null
    val last = state.tabs.lastOrNull()?.id ?: return null
    if (last == viewedTabId || state.tabs.none { it.id == viewedTabId }) return null
    return TabListAction.MoveTabsAction(listOf(viewedTabId), last, placeAfter = true)
}
