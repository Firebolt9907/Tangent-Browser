package com.firebolt9907.luminabrowser

import com.firebolt9907.luminabrowser.state.WebDarkModeTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the global dark-mode trigger resolution (Off / On / Auto-follows-system). */
class WebDarkModeTriggerTest {

    @Test
    fun `off and on ignore the system theme`() {
        assertFalse(WebDarkModeTrigger.Off.resolve(systemDark = true))
        assertTrue(WebDarkModeTrigger.On.resolve(systemDark = false))
    }

    @Test
    fun `auto follows the system theme`() {
        assertTrue(WebDarkModeTrigger.Auto.resolve(systemDark = true))
        assertFalse(WebDarkModeTrigger.Auto.resolve(systemDark = false))
    }

    @Test
    fun `fromName falls back to Off for unknown or null`() {
        assertEquals(WebDarkModeTrigger.Off, WebDarkModeTrigger.fromName(null))
        assertEquals(WebDarkModeTrigger.Off, WebDarkModeTrigger.fromName("bogus"))
        assertEquals(WebDarkModeTrigger.Auto, WebDarkModeTrigger.fromName("Auto"))
    }
}
