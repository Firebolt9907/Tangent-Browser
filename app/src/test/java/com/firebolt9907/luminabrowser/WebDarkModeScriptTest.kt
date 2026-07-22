package com.firebolt9907.luminabrowser

import com.firebolt9907.luminabrowser.ui.content.WebDarkMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the shape of the injected high-performance web-dark-mode scripts (Tier 2 fallback). The script
 * runs in Gecko and has no other coverage; these guards keep the load-bearing pieces from regressing.
 * See `ui/content/WebDarkMode.kt` for the two-tier rationale.
 */
class WebDarkModeScriptTest {

    @Test
    fun `apply is a single-line javascript url`() {
        val s = WebDarkMode.buildApplyScript()
        assertTrue("must be a javascript: URL", s.startsWith("javascript:"))
        assertFalse("flattened to one line so it stays a valid javascript: URL", s.contains("\n"))
    }

    @Test
    fun `apply uses a hue-preserving composited invert, not per-element recolor`() {
        val s = WebDarkMode.buildApplyScript()
        // The whole performance premise: one GPU-composited filter, not DarkReader-style color math.
        assertTrue(s.contains("invert(1) hue-rotate(180deg)"))
    }

    @Test
    fun `apply softens contrast for comfort but keeps media faithful`() {
        val s = WebDarkMode.buildApplyScript()
        // Page gets a mild contrast<1 so pure black/white become comfortable grays (DarkReader-like).
        assertTrue("page softened", s.contains("contrast(0.9)"))
        // Media carries the inverse contrast so photos net ~contrast(1.0) and stay crisp.
        assertTrue("media kept faithful", s.contains("contrast(1.1111)"))
    }

    @Test
    fun `apply re-inverts media so images and video render normally`() {
        val s = WebDarkMode.buildApplyScript()
        // Media must be double-inverted (→ identity) or photos come out as negatives.
        assertTrue(s.contains(" img,"))
        assertTrue(s.contains(" video,"))
        assertTrue(s.contains("[style*=\"background-image\"]"))
    }

    @Test
    fun `apply never inverts a directly-viewed image, video, or pdf document`() {
        val s = WebDarkMode.buildApplyScript()
        assertTrue("media-document guard present", s.contains("isMediaDoc"))
        assertTrue("checks the document content type", s.contains("document.contentType"))
        assertTrue("excludes pdf", s.contains("application/pdf"))
    }

    @Test
    fun `apply only darkens pages that stay light under the native override`() {
        val s = WebDarkMode.buildApplyScript()
        // The luminance gate is what lets Tier 1 (native dark themes) win with zero filter cost.
        assertTrue(s.contains("pageIsDark"))
        assertTrue(s.contains("getComputedStyle"))
    }

    @Test
    fun `default apply does not run the aggressive background scan`() {
        val s = WebDarkMode.buildApplyScript(aggressive = false)
        assertTrue("aggressive flag is off", s.contains("var AGG=false;"))
        // The scan is gated on AGG at runtime, so the default path pays no getComputedStyle walk.
    }

    @Test
    fun `aggressive apply tags class-based background images and observes new nodes`() {
        val s = WebDarkMode.buildApplyScript(aggressive = true)
        assertTrue("aggressive flag is on", s.contains("var AGG=true;"))
        assertTrue("tags url() backgrounds", s.contains("data-lumina-dark-bg"))
        assertTrue("only tags real image backgrounds, not gradients", s.contains("indexOf('url(')"))
        assertTrue("incremental observer for new nodes", s.contains("__luminaDarkObserver"))
        assertTrue("bounded scan", s.contains("SCAN_CAP"))
    }

    @Test
    fun `remove drops both the class and the injected style`() {
        val s = WebDarkMode.buildRemoveScript()
        assertTrue(s.startsWith("javascript:"))
        assertFalse(s.contains("\n"))
        assertTrue(s.contains("lumina-dark-fallback"))
        assertTrue(s.contains("lumina-dark-style"))
        assertTrue("disconnects the aggressive observer", s.contains("__luminaDarkObserver"))
    }
}
