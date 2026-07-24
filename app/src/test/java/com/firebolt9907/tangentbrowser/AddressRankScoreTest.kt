package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.state.AddressSuggestion
import com.firebolt9907.tangentbrowser.state.AddressSuggestionKind
import com.firebolt9907.tangentbrowser.state.OpenTabRef
import com.firebolt9907.tangentbrowser.state.canonicalUrlKey
import com.firebolt9907.tangentbrowser.state.openTabSuggestions
import com.firebolt9907.tangentbrowser.state.rankScore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the host-aware address-bar ranking. The motivating bug: typing "apple.com" surfaced a stale,
 * frequently-opened App Store deep link above the actual apple.com root. `rankScore` must rank the
 * registrable-domain root above any deep path under it.
 */
class AddressRankScoreTest {
    private fun history(url: String, title: String = url) =
        AddressSuggestion(title = title, url = url, kind = AddressSuggestionKind.History)

    @Test
    fun `domain root outranks a deep link under the same domain`() {
        val root = history("https://apple.com/")
        val deepLink = history("https://apps.apple.com/us/app/some-app/id123456")
        assertTrue(rankScore("apple.com", root) > rankScore("apple.com", deepLink))
    }

    @Test
    fun `exact host beats a substring-only match`() {
        val exact = history("https://apple.com/")
        // A host that merely contains the typed string but not at a boundary.
        val substring = history("https://fakeapple.computer/deals")
        assertTrue(rankScore("apple.com", exact) > rankScore("apple.com", substring))
    }

    @Test
    fun `www prefix does not change the exact-host match`() {
        val bare = rankScore("apple.com", history("https://apple.com/"))
        val www = rankScore("apple.com", history("https://www.apple.com/"))
        assertTrue(bare == www)
    }

    @Test
    fun `shallower page outranks a deeper page on host-prefix query`() {
        val shallow = history("https://apple.com/mac")
        val deep = history("https://apple.com/mac/macbook-pro/specs/2024/config")
        assertTrue(rankScore("apple.com", shallow) > rankScore("apple.com", deep))
    }

    @Test
    fun `bookmark gets a tiebreak boost over history for an equivalent match`() {
        val bookmark = AddressSuggestion("Apple", "https://apple.com/", AddressSuggestionKind.Bookmark)
        val historyRow = history("https://apple.com/")
        assertTrue(rankScore("apple.com", bookmark) > rankScore("apple.com", historyRow))
    }

    @Test
    fun `canonical key collapses scheme www and trailing slash`() {
        val key = canonicalUrlKey("https://apple.com/")
        assertEquals(key, canonicalUrlKey("http://apple.com"))
        assertEquals(key, canonicalUrlKey("https://www.apple.com/"))
        assertEquals(key, canonicalUrlKey("apple.com/"))
    }

    @Test
    fun `canonical key keeps distinct paths separate`() {
        assertNotEquals(canonicalUrlKey("https://apple.com/mac"), canonicalUrlKey("https://apple.com/"))
    }

    @Test
    fun `open tab match excludes the current tab and blank pages`() {
        val tabs = listOf(
            OpenTabRef("1", "https://apple.com/", "Apple"),
            OpenTabRef("2", "https://github.com/", "GitHub"),
            OpenTabRef("3", "about:blank", "New Tab"),
        )
        val matches = openTabSuggestions("apple", tabs, currentTabId = "2")
        assertEquals(1, matches.size)
        assertEquals("1", matches.first().tabId)
        assertEquals(AddressSuggestionKind.Tab, matches.first().kind)
    }

    @Test
    fun `open tab match is empty when the only hit is the current tab`() {
        val tabs = listOf(OpenTabRef("1", "https://apple.com/", "Apple"))
        assertTrue(openTabSuggestions("apple", tabs, currentTabId = "1").isEmpty())
    }

    @Test
    fun `open tab match dedups the same page opened twice`() {
        val tabs = listOf(
            OpenTabRef("1", "https://apple.com/", "Apple"),
            OpenTabRef("2", "http://www.apple.com", "Apple"),
        )
        assertEquals(1, openTabSuggestions("apple", tabs, currentTabId = null).size)
    }
}
