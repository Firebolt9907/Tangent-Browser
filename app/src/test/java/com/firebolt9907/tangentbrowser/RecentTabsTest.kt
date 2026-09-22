package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.state.recentTabMove
import com.firebolt9907.tangentbrowser.state.TAB_RECENCY_DWELL_MS
import mozilla.components.browser.state.state.BrowserState
import mozilla.components.browser.state.state.ContentState
import mozilla.components.browser.state.state.TabSessionState
import org.junit.Assert.*
import org.junit.Test

class RecentTabsTest {
    private val tabs = listOf("a", "b", "c").map {
        TabSessionState(id = it, content = ContentState(url = "https://$it.example"))
    }

    @Test fun `qualified older tab moves to top without changing selection`() {
        val state = BrowserState(tabs = tabs, selectedTabId = "b")
        val move = recentTabMove(state, "b")!!
        assertEquals(listOf("b"), move.tabIds)
        assertEquals("c", move.targetTabId)
        assertTrue(move.placeAfter)
    }

    @Test fun `delayed promotion cannot move a tab after user leaves it`() {
        assertNull(recentTabMove(BrowserState(tabs = tabs, selectedTabId = "c"), "a"))
        assertNull(recentTabMove(BrowserState(tabs = tabs, selectedTabId = "c"), "closed"))
    }

    @Test fun `top tab requires no reorder`() {
        assertNull(recentTabMove(BrowserState(tabs = tabs, selectedTabId = "c"), "c"))
    }

    @Test fun `dwell threshold is fifteen seconds`() {
        assertEquals(15_000L, TAB_RECENCY_DWELL_MS)
    }
}
