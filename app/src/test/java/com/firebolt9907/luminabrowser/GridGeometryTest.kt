package com.firebolt9907.luminabrowser

import com.firebolt9907.luminabrowser.ui.grid.DeckFrontCenterXFrac
import com.firebolt9907.luminabrowser.ui.grid.DeckFrontScale
import com.firebolt9907.luminabrowser.ui.grid.DeckPixelScale
import com.firebolt9907.luminabrowser.ui.grid.deckSlot
import com.firebolt9907.luminabrowser.ui.grid.pixelSlot
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
    fun `front slot is the largest and centred`() {
        val front = slot(0f)
        assertEquals(DeckFrontScale, front.scale, 0.001f)
        // Centre x = tx + (W*scale)/2 sits at the focus fraction of the width (dead centre).
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
    fun `the left stack tapers - each older sliver is narrower than the one in front`() {
        // The visible sliver of an older card is the gap between its centre and the centre of the
        // (newer) card in front of it. With a geometric left offset these gaps shrink with depth,
        // so the stack tapers into a vanishing point instead of marching off the edge.
        var prevGap = Float.MAX_VALUE
        for (p in 1..5) {
            val gap = (slot((p - 1).toFloat()).tx + w * slot((p - 1).toFloat()).scale / 2f) -
                (slot(p.toFloat()).tx + w * slot(p.toFloat()).scale / 2f)
            assertTrue("gap should be positive (older card is further left) at p=$p", gap > 0f)
            assertTrue("sliver gap should shrink with depth at p=$p", gap < prevGap)
            prevGap = gap
        }
    }

    @Test
    fun `a card scrolled in front slides off the right edge`() {
        // Its centre has passed the right viewport edge (a near-fullscreen card exits gradually).
        val ahead = slot(-3f)
        val centerX = ahead.tx + w * ahead.scale / 2f
        assertTrue("front-exiting card's centre has passed the right edge", centerX > w)
    }

    // ── Pixel mode: constant-scale horizontal filmstrip ─────────────────────────────────────────

    private fun px(p: Float) = pixelSlot(p, w, h, crop)

    @Test
    fun `pixel mode keeps every card the same size and centres the focus`() {
        assertEquals(DeckPixelScale, px(0f).scale, 0.001f)
        assertEquals(DeckPixelScale, px(1f).scale, 0.001f)
        assertEquals(DeckPixelScale, px(-2f).scale, 0.001f)
        // Focus centred; no depth fade anywhere.
        assertEquals(w * DeckFrontCenterXFrac, px(0f).tx + w * px(0f).scale / 2f, 0.5f)
        assertEquals(1f, px(3f).alpha, 0.001f)
    }

    @Test
    fun `pixel mode is an evenly spaced row on both sides`() {
        // Constant centre-to-centre step, symmetric about the focus (a plain scrollable list).
        val c0 = px(0f).tx + w * px(0f).scale / 2f
        val cLeft = px(1f).tx + w * px(1f).scale / 2f
        val cRight = px(-1f).tx + w * px(-1f).scale / 2f
        val stepLeft = c0 - cLeft
        val stepRight = cRight - c0
        assertEquals("step is symmetric", stepLeft, stepRight, 0.5f)
        // And uniform across further slots.
        val cLeft2 = px(2f).tx + w * px(2f).scale / 2f
        assertEquals("step is uniform", stepLeft, cLeft - cLeft2, 0.5f)
    }
}
