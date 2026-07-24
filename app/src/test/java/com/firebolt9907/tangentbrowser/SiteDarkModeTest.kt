package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.state.SiteDarkMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the per-site dark-mode override resolution against the global toggle. */
class SiteDarkModeTest {

    @Test
    fun `default follows the global toggle`() {
        assertTrue(SiteDarkMode.Default.resolve(globalEnabled = true))
        assertFalse(SiteDarkMode.Default.resolve(globalEnabled = false))
    }

    @Test
    fun `explicit on and off override the global toggle both ways`() {
        // Off wins even when global dark is on (the "this site looks wrong inverted" escape hatch).
        assertFalse(SiteDarkMode.Off.resolve(globalEnabled = true))
        // On wins even when global dark is off (darken just this one site).
        assertTrue(SiteDarkMode.On.resolve(globalEnabled = false))
    }

    @Test
    fun `fromName falls back to Default for unknown or null`() {
        assertEquals(SiteDarkMode.Default, SiteDarkMode.fromName(null))
        assertEquals(SiteDarkMode.Default, SiteDarkMode.fromName("bogus"))
        assertEquals(SiteDarkMode.On, SiteDarkMode.fromName("On"))
        assertEquals(SiteDarkMode.Off, SiteDarkMode.fromName("Off"))
    }
}
