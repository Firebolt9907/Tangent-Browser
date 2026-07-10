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
    fun `boilerplate selector excludes layout wrappers and safeguards article and main`() {
        assertTrue("excludes has- class prefix", script.contains(":not([class*=\"has-\" i])"))
        assertTrue("excludes with- class prefix", script.contains(":not([class*=\"with-\" i])"))
        assertTrue("excludes article from boilerplate", script.contains("n.nodeName === 'ARTICLE'"))
        assertTrue("excludes main from boilerplate", script.contains("n.nodeName === 'MAIN'"))
    }

    @Test
    fun `two builds are deterministic`() {
        assertTrue(script == ReaderExtractor.buildReaderExtractorScript())
    }

    @Test
    fun `inlines the per-site rules table and its include and strip machinery`() {
        assertTrue("inlines the rules array", script.contains("var __luminaRules ="))
        assertTrue("matches a rule by hostname", script.contains("luminaMatchedRule"))
        assertTrue("honours include overrides", script.contains("__rule.include"))
        assertTrue("honours strip filters", script.contains("__rule.strip"))
        assertTrue("gates blocks on strip", script.contains("isStripped(el)"))
    }

    @Test
    fun `default build inlines an empty rules array`() {
        assertTrue("default has no overrides", script.contains("var __luminaRules = [];"))
    }

    @Test
    fun `a real ruleset is inlined verbatim and stays flatten-safe`() {
        val rules =
            """[{"match":["example.com"],"include":[".article-body"],"strip":[".ad","aside"]}]"""
        val withRules = ReaderExtractor.buildReaderExtractorScript(rules)
        assertTrue("rules inlined verbatim", withRules.contains("var __luminaRules = $rules;"))
        assertFalse("still a single line", withRules.contains("\n"))
        assertFalse("a `//` would eat the flattened script", withRules.contains("//"))
    }
}
