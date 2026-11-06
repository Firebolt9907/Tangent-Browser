package com.firebolt9907.tangentbrowser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ContinueOnTest {
    @Test fun preservesPortableUrlIncludingQueryAndFragment() {
        for (url in listOf("https://example.com/article?q=a%20b#section", "http://example.com:8080/")) {
            assertEquals(url, ContinueOn.webUrl(url, private = false))
        }
    }

    @Test fun privatePagesAreNeverOffered() {
        assertNull(ContinueOn.webUrl("https://example.com/", private = true))
    }

    @Test fun localSchemesMalformedUrlsAndCredentialsAreNotOffered() {
        for (url in listOf(
            null, "", "example.com", "about:blank", "about:reader?url=https://example.com",
            "file:///sdcard/page.html", "content://files/1", "blob:https://example.com/id",
            "data:text/html,hello", "javascript:alert(1)", "https:///missing-host",
            "https://example.com/a\nb", "https://user:password@example.com/",
        )) {
            assertNull(url, ContinueOn.webUrl(url, private = false))
        }
    }
}
