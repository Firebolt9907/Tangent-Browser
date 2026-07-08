package com.firebolt9907.luminabrowser

import com.firebolt9907.luminabrowser.ui.reader.getGoogleCacheUrl
import com.firebolt9907.luminabrowser.ui.reader.shouldRedirectToGoogleCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleCacheReaderTest {

    @Test
    fun testShouldRedirectToGoogleCache() {
        // HTTP/HTTPS web links should be redirected
        assertTrue(shouldRedirectToGoogleCache("https://example.com/article"))
        assertTrue(shouldRedirectToGoogleCache("http://example.com/article"))
        assertTrue(shouldRedirectToGoogleCache("HTTPS://EXAMPLE.COM/ARTICLE"))

        // Already cached URLs should not be redirected
        assertFalse(shouldRedirectToGoogleCache("https://webcache.googleusercontent.com/search?q=cache:https://example.com/article"))

        // Non-web links or about/file schemes should not be redirected
        assertFalse(shouldRedirectToGoogleCache("about:blank"))
        assertFalse(shouldRedirectToGoogleCache("file:///android_asset/page.html"))
        assertFalse(shouldRedirectToGoogleCache("javascript:alert(1)"))
    }

    @Test
    fun testGetGoogleCacheUrl() {
        val originalUrl = "https://example.com/article?query=1&val=2"
        val expected = "https://webcache.googleusercontent.com/search?q=cache:https%3A%2F%2Fexample.com%2Farticle%3Fquery%3D1%26val%3D2"
        assertEquals(expected, getGoogleCacheUrl(originalUrl))

        // If it's already a cache URL, return it as-is
        val cachedUrl = "https://webcache.googleusercontent.com/search?q=cache:https://example.com"
        assertEquals(cachedUrl, getGoogleCacheUrl(cachedUrl))
    }
}
