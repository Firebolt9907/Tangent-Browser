package com.firebolt9907.luminabrowser

import com.firebolt9907.luminabrowser.state.compactHost
import com.firebolt9907.luminabrowser.state.compactHostWithPort
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

    // ── Local/private hosts default to http (not https) so they stay reachable ────

    // A Pi-hole / LAN entry whose ending (`.lab`) is not a real IANA TLD → http.
    @Test fun privateTld_prependsHttp() =
        assertEquals("http://immich.lab", normalizeUrl("immich.lab"))

    @Test fun privateTldWithPath_prependsHttp() =
        assertEquals("http://immich.lab/albums", normalizeUrl("immich.lab/albums"))

    @Test fun dotLocalMdns_prependsHttp() =
        assertEquals("http://nas.local", normalizeUrl("nas.local"))

    @Test fun homeLabTld_prependsHttp() =
        assertEquals("http://pi.hole", normalizeUrl("pi.hole"))

    // A recognized public TLD still gets https even with a novel subdomain.
    @Test fun publicTld_prependsHttps() =
        assertEquals("https://grafana.example.com", normalizeUrl("grafana.example.com"))

    // localhost is a LAN host → http, not https (it rarely serves publicly-trusted TLS).
    @Test fun localhost_prependsHttp() =
        assertEquals("http://localhost", normalizeUrl("localhost"))

    // "localhost:3000" matches the scheme regex (alpha chars before ':') and therefore passes
    // through unchanged rather than getting a scheme prepended. This is the actual behavior.
    @Test fun localhostWithPort_matchesSchemeRegex_passesThrough() =
        assertEquals("localhost:3000", normalizeUrl("localhost:3000"))

    // A private-range IP with a port is a LAN host → http, so it stays reachable.
    @Test fun privateIpWithPort_prependsHttp() =
        assertEquals("http://192.168.1.1:8080", normalizeUrl("192.168.1.1:8080"))

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

    // A bare domain (no scheme) is normalized to https:// first, then www is stripped.
    @Test fun compactHost_bareWwwDomain_stripsWww() =
        assertEquals("example.com", compactHost("www.example.com"))

    // An explicit scheme whose host java.net.URI cannot parse (here an illegal '^') must not crash
    // compactHost; the runCatching fallback returns the raw input. Guards the host-parse error path.
    @Test fun compactHost_malformedUri_returnsFallback() =
        assertEquals("https://exa^mple.com", compactHost("https://exa^mple.com"))

    // ── compactHostWithPort ───────────────────────────────────────────────────

    @Test fun compactHostWithPort_keepsExplicitPort() =
        assertEquals("localhost:3000", compactHostWithPort("http://localhost:3000"))

    @Test fun compactHostWithPort_keepsPortOnPrivateIp() =
        assertEquals("192.168.1.5:8080", compactHostWithPort("http://192.168.1.5:8080/admin"))

    @Test fun compactHostWithPort_stripsWwwKeepsPort() =
        assertEquals("example.com:8443", compactHostWithPort("https://www.example.com:8443/x"))

    // A port matching the scheme default is omitted so ordinary pages read like compactHost.
    @Test fun compactHostWithPort_omitsDefaultHttpsPort() =
        assertEquals("apple.com", compactHostWithPort("https://apple.com:443"))

    @Test fun compactHostWithPort_omitsDefaultHttpPort() =
        assertEquals("example.com", compactHostWithPort("http://example.com:80"))

    @Test fun compactHostWithPort_noPort_matchesCompactHost() =
        assertEquals("nytimes.com", compactHostWithPort("https://www.nytimes.com/article"))

    // ── normalizeUrl: additional robustness cases ─────────────────────────────

    // The scheme regex is case-insensitive in its first char class, so an uppercased scheme is
    // still recognized as explicit and passes through untouched rather than being searched.
    @Test fun uppercaseScheme_passesThrough() =
        assertEquals("HTTPS://example.com", normalizeUrl("HTTPS://example.com"))

    // A bare IPv4 address has dots but its final label (a number) is not an IANA TLD, so it is
    // treated as a LAN host and gets http:// — raw IPs rarely serve publicly-trusted TLS.
    @Test fun bareIpAddress_prependsHttp() =
        assertEquals("http://127.0.0.1", normalizeUrl("127.0.0.1"))

    // mailto: is an explicit scheme and must pass through unchanged (not become a web search).
    @Test fun mailtoScheme_passesThrough() =
        assertEquals("mailto:test@example.com", normalizeUrl("mailto:test@example.com"))
}
