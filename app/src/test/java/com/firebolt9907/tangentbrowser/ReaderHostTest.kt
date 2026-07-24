package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.ui.reader.readerHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
