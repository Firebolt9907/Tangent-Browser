package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.ui.content.buildViewportSpacerScript
import com.firebolt9907.tangentbrowser.ui.content.encodeJavascriptUrl
import com.firebolt9907.tangentbrowser.ui.content.OtpAutofill
import java.net.URLDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JavascriptUrlEncodingTest {
    @Test
    fun `shipping OTP detector with leading whitespace does not crash injection`() {
        val script = OtpAutofill.buildDetectScript()
        val url = encodeJavascriptUrl(script)
        assertTrue(url.startsWith("javascript:"))
        assertEquals(script.trimStart().removePrefix("javascript:"), URLDecoder.decode(url.removePrefix("javascript:"), "UTF-8"))
    }

    @Test
    fun `URL scheme is case insensitive and unrelated URLs remain unchanged`() {
        assertEquals("javascript:void%200", encodeJavascriptUrl(" \nJavaScript:void 0"))
        assertEquals("about:blank", encodeJavascriptUrl("about:blank"))
    }

    @Test
    fun `URL decoding recovers the exact shipping top inset source`() {
        val script = buildViewportSpacerScript(50, false)
        val url = encodeJavascriptUrl(script)
        assertTrue(url.startsWith("javascript:"))
        assertEquals(script.removePrefix("javascript:"), URLDecoder.decode(url.removePrefix("javascript:"), "UTF-8"))
    }

    @Test
    fun `modulo operators literal percent escapes and unicode survive transport`() {
        val source = "javascript:void ((450%360)+100+'%20 # + café')"
        val url = encodeJavascriptUrl(source)
        assertTrue(url.contains("%25360"))
        assertTrue(url.contains("%2520"))
        assertEquals(source.removePrefix("javascript:"), URLDecoder.decode(url.removePrefix("javascript:"), "UTF-8"))
    }
}
