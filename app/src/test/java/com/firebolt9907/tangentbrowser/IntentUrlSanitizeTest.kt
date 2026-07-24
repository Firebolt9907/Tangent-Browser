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

    // --- Hardening: leading/surrounding whitespace must not smuggle a dangerous scheme past the
    // boundary. The trim() in sanitizeExternalUrl is what makes these safe; lock it in so a future
    // refactor that drops the trim is caught here rather than in the field. ---

    @Test fun leadingWhitespace_javascript_blocked() =
        assertNull(IntentUrls.sanitizeExternalUrl("   javascript:alert(1)"))

    @Test fun leadingTab_javascript_blocked() =
        assertNull(IntentUrls.sanitizeExternalUrl("\tjavascript:alert(1)"))

    @Test fun leadingNewline_file_blocked() =
        assertNull(IntentUrls.sanitizeExternalUrl("\nfile:///etc/hosts"))

    @Test fun surroundingWhitespace_https_allowedAndTrimmed() =
        assertEquals("https://example.com", IntentUrls.sanitizeExternalUrl("  https://example.com  "))

    // Other privileged/local schemes named in the docstring but not yet asserted.
    @Test fun blobScheme_blocked() =
        assertNull(IntentUrls.sanitizeExternalUrl("blob:https://example.com/uuid"))

    @Test fun vbscriptScheme_blocked() =
        assertNull(IntentUrls.sanitizeExternalUrl("vbscript:msgbox(1)"))

    @Test fun mixedCaseDataScheme_blocked() =
        assertNull(IntentUrls.sanitizeExternalUrl("DaTa:text/html,<script>alert(1)</script>"))

    @Test fun aboutScheme_blocked() =
        assertNull(IntentUrls.sanitizeExternalUrl("about:config"))

    // A token broken by an interior control char is NOT a valid scheme, so it falls through as
    // schemeless (becomes an https/search string downstream) — it never loads as javascript.
    @Test fun interiorTabInScheme_treatedAsSchemeless() =
        assertEquals("java\tscript:alert(1)", IntentUrls.sanitizeExternalUrl("java\tscript:alert(1)"))
}
