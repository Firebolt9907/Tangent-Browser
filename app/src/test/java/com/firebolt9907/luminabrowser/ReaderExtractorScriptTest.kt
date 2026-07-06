package com.firebolt9907.luminabrowser

import com.firebolt9907.luminabrowser.ui.reader.ReaderExtractor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins invariants of the injected reader-extraction script. It runs in Gecko and has no other
 * coverage, and it is flattened to a single line before injection — so a stray `//` line comment
 * would silently comment out the rest of the script (including the prompt() bridge) and disable
 * ALL reader extraction. These assertions catch that class of regression on the JVM.
 */
class ReaderExtractorScriptTest {

    private val script = ReaderExtractor.buildReaderExtractorScript()

    @Test
    fun `is a single-line javascript url`() {
        assertTrue("must be a javascript: URL", script.startsWith("javascript:"))
        assertFalse("flattened to one line", script.contains("\n"))
    }

    @Test
    fun `contains no double-slash line comments (the flatten footgun)`() {
        // The script builds every URL in code (new URL(...)), so it legitimately contains no
        // literal "//". Any "//" therefore means a line comment sneaked in — which, once the
        // script is flattened, eats the rest of the script. Keep it out; use block comments.
        assertFalse(
            "a `//` would comment out the flattened script — use block comments and build URLs in code",
            script.contains("//"),
        )
    }

    @Test
    fun `hands the article back over the lumina-reader channel`() {
        assertTrue("bridge carries the lumina-reader channel", script.contains("luminaBridge(\"lumina-reader\""))
    }

    @Test
    fun `bridge uses a pristine iframe prompt, never the page's window prompt`() {
        // Sites (e.g. TechCrunch anti-adblock) override window.prompt; the bridge must read a fresh
        // native prompt from a throwaway iframe instead of calling the page's prompt directly.
        assertTrue("uses an iframe realm", script.contains("createElement('iframe')"))
        assertTrue("reads the iframe's own prompt", script.contains("contentWindow"))
        assertFalse(
            "must not call the page's own window.prompt bridge",
            script.contains("prompt(\"lumina-reader\""),
        )
    }

    @Test
    fun `handles the block types the parser expects`() {
        assertTrue("paragraphs", script.contains("type: 'p'"))
        assertTrue("headings", script.contains("type: 'h'"))
        assertTrue("code/pre", script.contains("type: 'pre'"))
        assertTrue("tables", script.contains("type: 'table'"))
        assertTrue("images", script.contains("type: 'img'"))
    }

    @Test
    fun `prefers schema-org articleBody and falls back to og-image`() {
        assertTrue("high-precision container", script.contains("itemprop=\"articleBody\""))
        assertTrue("lead-image fallback", script.contains("og:image"))
    }

    @Test
    fun `two builds are deterministic`() {
        assertTrue(script == ReaderExtractor.buildReaderExtractorScript())
    }
}
