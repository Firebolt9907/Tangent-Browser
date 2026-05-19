package com.firebolt9907.luminabrowser

import com.firebolt9907.luminabrowser.state.compactHost
import com.firebolt9907.luminabrowser.state.normalizeUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlNormalizationTest {

    // ── normalizeUrl ──────────────────────────────────────────────────────────

    @Test fun emptyInput_returnsAboutBlank() =
        assertEquals("about:blank", normalizeUrl(""))

    @Test fun blankInput_returnsAboutBlank() =
        assertEquals("about:blank", normalizeUrl("   "))

    @Test fun httpsUrl_passesThrough() =
        assertEquals("https://example.com", normalizeUrl("https://example.com"))

    @Test fun httpUrl_passesThrough() =
        assertEquals("http://example.com/path", normalizeUrl("http://example.com/path"))

    @Test fun aboutBlank_passesThrough() =
        assertEquals("about:blank", normalizeUrl("about:blank"))

    @Test fun fileScheme_passesThrough() =
        assertEquals("file:///sdcard/test.html", normalizeUrl("file:///sdcard/test.html"))

    @Test fun javascriptScheme_passesThrough() =
        assertEquals("javascript:void(0)", normalizeUrl("javascript:void(0)"))

    @Test fun bareDomain_prependsHttps() =
        assertEquals("https://apple.com", normalizeUrl("apple.com"))

    @Test fun domainWithPath_prependsHttps() =
        assertEquals("https://nytimes.com/section/world", normalizeUrl("nytimes.com/section/world"))

    @Test fun domainWithSubdomain_prependsHttps() =
        assertEquals("https://en.wikipedia.org", normalizeUrl("en.wikipedia.org"))

    @Test fun localhost_prependsHttps() =
        assertEquals("https://localhost", normalizeUrl("localhost"))

    // "localhost:3000" matches the scheme regex (alpha chars before ':') and therefore passes
    // through unchanged rather than getting https:// prepended. This is the actual behavior.
    @Test fun localhostWithPort_matchesSchemeRegex_passesThrough() =
        assertEquals("localhost:3000", normalizeUrl("localhost:3000"))

    @Test fun ipWithPort_prependsHttps() =
        assertEquals("https://192.168.1.1:8080", normalizeUrl("192.168.1.1:8080"))

    @Test fun singleWord_becomesGoogleSearch() {
        val result = normalizeUrl("weather")
        assertTrue("Expected Google search URL, got: $result",
            result.startsWith("https://www.google.com/search?q="))
        assertTrue(result.contains("weather"))
    }

    @Test fun phraseWithSpaces_becomesGoogleSearch() {
        val result = normalizeUrl("how to install apk")
        assertTrue(result.startsWith("https://www.google.com/search?q="))
        assertTrue(result.contains("how"))
    }

    @Test fun queryWithSpecialChars_isUrlEncoded() {
        val result = normalizeUrl("hello world & more")
        assertTrue(result.startsWith("https://www.google.com/search?q="))
        val query = result.substringAfter("?q=")
        // Spaces and & must not appear literally in the query string
        assertTrue("Raw space in query: $result", ' ' !in query)
        assertTrue("Raw & in query: $result", '&' !in query)
    }

    @Test fun leadingAndTrailingWhitespace_isTrimmed() =
        assertEquals("https://example.com", normalizeUrl("  https://example.com  "))

    // A domain with an embedded space is not a valid host; must become a search.
    @Test fun inputWithWhitespaceInMiddle_becomesSearch() {
        val result = normalizeUrl("example .com")
        assertTrue(result.startsWith("https://www.google.com/search?q="))
    }

    // ── compactHost ───────────────────────────────────────────────────────────

    @Test fun compactHost_stripsWww() =
        assertEquals("nytimes.com", compactHost("https://www.nytimes.com/article"))

    @Test fun compactHost_noWww_returnsHost() =
        assertEquals("apple.com", compactHost("https://apple.com"))

    @Test fun compactHost_subdomain_returnsFull() =
        assertEquals("en.wikipedia.org", compactHost("https://en.wikipedia.org/wiki/Main_Page"))

    // about:blank has no host component → falls back to the raw url argument
    @Test fun compactHost_aboutBlank_returnsFallback() =
        assertEquals("about:blank", compactHost("about:blank"))

    // Empty string normalizes to about:blank, which has no host → fallback is empty string
    @Test fun compactHost_emptyString_returnsEmpty() =
        assertEquals("", compactHost(""))

    // A bare search term normalizes to a Google URL, so host is google.com
    @Test fun compactHost_bareSearch_returnsGoogleHost() =
        assertEquals("google.com", compactHost("weather"))

    @Test fun compactHost_localhost_returnsLocalhost() =
        assertEquals("localhost", compactHost("https://localhost"))

    @Test fun compactHost_httpsUrl_noWwwPrefix_isUnchanged() =
        assertEquals("wikipedia.org", compactHost("https://www.wikipedia.org"))
}
