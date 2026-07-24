package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.state.hostFromUrl
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `hostFromUrl` derives the key used for every per-site preference (zoom, reader mode,
 * top-padding-fix). A regression here silently mis-keys or loses those preferences, so the
 * production-relevant cases are pinned here. Inputs are always real page URLs from the engine.
 */
class HostFromUrlTest {

    @Test fun emptyString_returnsEmpty() =
        assertEquals("", hostFromUrl(""))

    @Test fun blank_returnsEmpty() =
        assertEquals("", hostFromUrl("   "))

    @Test fun aboutBlank_returnsEmpty() =
        assertEquals("", hostFromUrl("about:blank"))

    @Test fun httpsUrl_returnsHost() =
        assertEquals("apple.com", hostFromUrl("https://apple.com/iphone"))

    @Test fun wwwPrefix_isStripped() =
        assertEquals("nytimes.com", hostFromUrl("https://www.nytimes.com/section/world"))

    @Test fun subdomain_isPreserved() =
        assertEquals("en.wikipedia.org", hostFromUrl("https://en.wikipedia.org/wiki/Main_Page"))

    // Same host across different paths/schemes must produce the same key, so a site's
    // preferences apply consistently as the user navigates within it.
    @Test fun sameHostDifferentPaths_produceSameKey() {
        assertEquals(
            hostFromUrl("https://example.com/a"),
            hostFromUrl("https://example.com/b?q=1"),
        )
    }
}
