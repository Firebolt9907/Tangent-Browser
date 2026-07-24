package com.firebolt9907.tangentbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Security boundary: [IntentUrls.sanitizeExternalUrl] must reject privileged/local schemes that an
 * untrusted external caller could use to make the browser read app-private data or inject script,
 * while still letting ordinary web URLs and schemeless (search/domain) input through.
 */
class IntentUrlSanitizeTest {

    @Test fun httpsUrl_allowed() =
        assertEquals("https://example.com", IntentUrls.sanitizeExternalUrl("https://example.com"))

    @Test fun httpUrl_allowed() =
        assertEquals("http://example.com", IntentUrls.sanitizeExternalUrl("http://example.com"))

    @Test fun schemelessDomain_allowed() =
        assertEquals("example.com", IntentUrls.sanitizeExternalUrl("example.com"))

    @Test fun searchText_allowed() =
        assertEquals("hello world", IntentUrls.sanitizeExternalUrl("hello world"))

    @Test fun uppercaseScheme_allowed() =
        assertEquals("HTTPS://example.com", IntentUrls.sanitizeExternalUrl("HTTPS://example.com"))

    @Test fun javascriptScheme_blocked() =
        assertNull(IntentUrls.sanitizeExternalUrl("javascript:alert(document.cookie)"))

    @Test fun fileScheme_blocked() =
        assertNull(IntentUrls.sanitizeExternalUrl("file:///data/data/com.firebolt9907.tangentbrowser/prefs.xml"))

    @Test fun contentScheme_blocked() =
        assertNull(IntentUrls.sanitizeExternalUrl("content://com.android.providers.media/external"))

    @Test fun dataScheme_blocked() =
        assertNull(IntentUrls.sanitizeExternalUrl("data:text/html,<script>alert(1)</script>"))

    @Test fun intentScheme_blocked() =
        assertNull(IntentUrls.sanitizeExternalUrl("intent://scan/#Intent;scheme=zxing;end"))

    @Test fun uppercaseJavascriptScheme_blocked() =
        assertNull(IntentUrls.sanitizeExternalUrl("JavaScript:alert(1)"))

    @Test fun blank_returnsNull() =
        assertNull(IntentUrls.sanitizeExternalUrl("   "))

    @Test fun null_returnsNull() =
        assertNull(IntentUrls.sanitizeExternalUrl(null))
}
