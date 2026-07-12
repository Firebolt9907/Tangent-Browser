package com.firebolt9907.luminabrowser

import com.firebolt9907.luminabrowser.ui.grid.DeckFrontCenterXFrac
import com.firebolt9907.luminabrowser.ui.grid.DeckFrontScale
import com.firebolt9907.luminabrowser.ui.grid.DeckVisibleDepth
import com.firebolt9907.luminabrowser.ui.grid.deckSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The receding 3D deck geometry ([deckSlot]). Pins the front-slot placement, the monotonic
 * recede (each older card is smaller and further left), and the depth fade so a regression can't
 * silently break the app-switcher stack.
 */
class GridGeometryTest {

    private val w = 1000f
    private val h = 2000f
    // Card crop height in the card's own coordinate space; only affects ty, not the invariants below.
    private val crop = 1700f

    private fun slot(p: Float) = deckSlot(p, w, h, crop)

    @Test
    fun `front slot is the largest and biased right`() {
        val front = slot(0f)
        assertEquals(DeckFrontScale, front.scale, 0.001f)
        // Centre x = tx + (W*scale)/2 sits at the right-biased fraction of the width.
        val centerX = front.tx + w * front.scale / 2f
        assertEquals(w * DeckFrontCenterXFrac, centerX, 0.5f)
    }

    @Test
    fun `older cards recede smaller and to the left`() {
        var prev = slot(0f)
        for (p in 1..5) {
            val cur = slot(p.toFloat())
            val prevCenterX = prev.tx + w * prev.scale / 2f
            val curCenterX = cur.tx + w * cur.scale / 2f
            assertTrue("scale should shrink with depth at p=$p", cur.scale < prev.scale)
            assertTrue("centre should move left with depth at p=$p", curCenterX < prevCenterX)
            prev = cur
        }
    }

    @Test
    fun `a card scrolled in front of the front grows toward fullscreen`() {
        val front = slot(0f)
        val ahead = slot(-1f)
        assertTrue(ahead.scale > front.scale)
    }

    @Test
    fun `deep cards slide off the left edge instead of fading`() {
        // The front card is on-screen; a deep card's right edge has passed the left viewport edge.
        val front = slot(0f)
        assertTrue("front card is on-screen", front.tx + w * front.scale / 2f > 0f)
        val deep = slot(DeckVisibleDepth + 1f)
        val deepRightEdge = deep.tx + w * deep.scale
        assertTrue("deep card has slid off the left edge", deepRightEdge < 0f)
    }

    @Test
    fun `a card scrolled in front slides off the right edge`() {
        // Its centre has passed the right viewport edge (a near-fullscreen card exits gradually).
        val ahead = slot(-3f)
        val centerX = ahead.tx + w * ahead.scale / 2f
        assertTrue("front-exiting card's centre has passed the right edge", centerX > w)
    }
}
