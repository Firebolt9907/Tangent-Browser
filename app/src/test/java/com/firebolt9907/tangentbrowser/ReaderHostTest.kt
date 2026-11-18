package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.ui.archive.WaybackManager
import com.firebolt9907.tangentbrowser.ui.archive.siteHostFromUrl
import com.firebolt9907.tangentbrowser.ui.reader.readerHost
import com.firebolt9907.tangentbrowser.ui.reader.refererFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The reader header's source-domain display helper. */
class ReaderHostTest {

    @Test
    fun `strips leading www and lowercases`() {
        assertEquals("nytimes.com", readerHost("https://www.NYTimes.com/2026/07/06/story.html"))
    }

    @Test
    fun `keeps subdomains other than www`() {
        assertEquals("blog.example.co.uk", readerHost("https://blog.example.co.uk/post"))
    }

    @Test
    fun `null or blank or hostless input yields null`() {
        assertNull(readerHost(null))
        assertNull(readerHost(""))
        assertNull(readerHost("   "))
        assertNull(readerHost("not a url"))
    }

    @Test
    fun `refererFor trims article URLs down to origin only`() {
        assertEquals(
            "https://example.com/",
            refererFor("https://example.com/secret/path?token=123#frag", "https://cdn.other.com/img.jpg"),
        )
        assertEquals(
            "https://example.com:8443/",
            refererFor("https://example.com:8443/article?id=1", "https://cdn.other.com/img.jpg"),
        )
        assertEquals(
            "https://cdn.other.com/",
            refererFor(null, "https://cdn.other.com/assets/img.jpg?sig=abc"),
        )
    }

    @Test
    fun `wayback URL matching rejects embedded query spoofs and userinfo`() {
        val wb = WaybackManager.instance
        assertTrue(wb.isArchiveUrl("https://web.archive.org/web/20260101000000if_/https://nytimes.com/a"))
        assertEquals(
            "https://nytimes.com/a",
            wb.originalFromArchiveUrl("https://web.archive.org/web/20260101000000if_/https://nytimes.com/a"),
        )

        val spoof = "https://evil.com/phish?u=https://web.archive.org/web/20260101000000/https://bank.com"
        assertFalse(wb.isArchiveUrl(spoof))
        assertNull(wb.originalFromArchiveUrl(spoof))
        assertEquals("evil.com", siteHostFromUrl(spoof))

        val userInfoSpoof = "https://web.archive.org/web/20260101000000/https://bank.com@evil.com/"
        assertNull(wb.originalFromArchiveUrl(userInfoSpoof))
    }
}
