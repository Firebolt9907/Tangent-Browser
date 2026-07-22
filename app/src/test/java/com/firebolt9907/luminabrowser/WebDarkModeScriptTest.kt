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
    fun `apply re-inverts media so images and video render normally`() {
        val s = WebDarkMode.buildApplyScript()
        // Media must be double-inverted (→ identity) or photos come out as negatives.
        assertTrue(s.contains(" img,"))
        assertTrue(s.contains(" video,"))
        assertTrue(s.contains("[style*=\"background-image\"]"))
    }

    @Test
    fun `apply only darkens pages that stay light under the native override`() {
        val s = WebDarkMode.buildApplyScript()
        // The luminance gate is what lets Tier 1 (native dark themes) win with zero filter cost.
        assertTrue(s.contains("pageIsDark"))
        assertTrue(s.contains("getComputedStyle"))
    }

    @Test
    fun `remove drops both the class and the injected style`() {
        val s = WebDarkMode.buildRemoveScript()
        assertTrue(s.startsWith("javascript:"))
        assertFalse(s.contains("\n"))
        assertTrue(s.contains("lumina-dark-fallback"))
        assertTrue(s.contains("lumina-dark-style"))
    }
}
