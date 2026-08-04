package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.state.isSafeZapSelector
import com.firebolt9907.tangentbrowser.ui.content.ElementZap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Element zap's two host-side trust boundaries: what may be persisted/spliced into a stylesheet
 * ([isSafeZapSelector]) and what the page is allowed to say over the bridge ([ElementZap.parse]).
 * Both take page-derived strings — ids and class names are authored by the site — so the pinning
 * here is the real guard, not documentation.
 */
class ElementZapTest {

    @Test
    fun `accepts the selector shapes the picker generates`() {
        assertTrue(isSafeZapSelector("#promo-bar"))
        assertTrue(isSafeZapSelector(".cookie-banner"))
        assertTrue(isSafeZapSelector("div.wrapper > section.ad > div:nth-of-type(3)"))
        assertTrue(isSafeZapSelector("aside"))
    }

    @Test
    fun `rejects selectors that could break out of the style rule`() {
        // The whole point of the allowlist: a page-authored class name must not be able to close our
        // rule and open its own.
        assertFalse(isSafeZapSelector(".a}html{display:none"))
        assertFalse(isSafeZapSelector(".a{display:block}"))
        assertFalse(isSafeZapSelector(".a;color:red"))
        assertFalse(isSafeZapSelector(".a/*x*/"))
        assertFalse(isSafeZapSelector("@import url(x)"))
        assertFalse(isSafeZapSelector(".a\\3c/style\\3e"))
    }

    @Test
    fun `rejects whole-document selectors and blanks`() {
        assertFalse(isSafeZapSelector("html"))
        assertFalse(isSafeZapSelector("body"))
        assertFalse(isSafeZapSelector("body > div"))
        assertFalse(isSafeZapSelector("*"))
        assertFalse(isSafeZapSelector(":root"))
        assertFalse(isSafeZapSelector(""))
        assertFalse(isSafeZapSelector("   "))
        assertFalse(isSafeZapSelector("a".repeat(600)))
    }

    @Test
    fun `builds one rule per selector`() {
        assertEquals("", ElementZap.buildHideCss(emptyList()))
        assertEquals(
            ".ad{display:none!important}\n#promo{display:none!important}",
            ElementZap.buildHideCss(listOf(".ad", "#promo")),
        )
    }

    @Test
    fun `parses picker events`() {
        val selected = ElementZap.parse("""{"state":"selected","sel":".ad","label":"div.ad","canWiden":true}""")
        assertTrue(selected is ElementZap.Event.Selected)
        val selection = (selected as ElementZap.Event.Selected).selection
        assertEquals(".ad", selection.selector)
        assertEquals("div.ad", selection.label)
        assertTrue(selection.canWiden)

        val zapped = ElementZap.parse("""{"state":"zapped","sel":"#promo","label":"#promo"}""")
        assertTrue(zapped is ElementZap.Event.Zapped)
        assertFalse((zapped as ElementZap.Event.Zapped).selection.canWiden)

        assertEquals(ElementZap.Event.Cancelled, ElementZap.parse("""{"state":"cancel"}"""))
    }

    @Test
    fun `ignores malformed or empty payloads`() {
        assertNull(ElementZap.parse("not json"))
        assertNull(ElementZap.parse("""{"state":"selected"}"""))
        assertNull(ElementZap.parse("""{"state":"zapped","sel":""}"""))
        assertNull(ElementZap.parse("""{"state":"whatever","sel":".ad"}"""))
    }

    @Test
    fun `label falls back to the selector`() {
        val event = ElementZap.parse("""{"state":"selected","sel":".ad"}""") as ElementZap.Event.Selected
        assertEquals(".ad", event.selection.label)
    }
}
