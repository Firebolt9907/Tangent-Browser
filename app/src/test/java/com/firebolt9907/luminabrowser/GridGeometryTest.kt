package com.firebolt9907.luminabrowser

import androidx.compose.ui.unit.IntSize
import com.firebolt9907.luminabrowser.ui.grid.DeckFrontCenterXFrac
import com.firebolt9907.luminabrowser.ui.grid.DeckFrontScale
import com.firebolt9907.luminabrowser.ui.grid.DeckMinAlpha
import com.firebolt9907.luminabrowser.ui.grid.DeckPixelScale
import com.firebolt9907.luminabrowser.ui.grid.deckSlot
import com.firebolt9907.luminabrowser.ui.grid.gridCellRect
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

    @Test
    fun `depth alpha is opaque to one slot left, then fades to a floor`() {
        // Focus, the newer/right card, and the nearest older card all stay fully opaque.
        assertEquals(1f, slot(0f).alpha, 0.001f)
        assertEquals(1f, slot(-1f).alpha, 0.001f)
        assertEquals(1f, slot(1f).alpha, 0.001f)
        // Deeper left-stack cards fade, monotonically, never below the floor.
        assertTrue("p=2 fades", slot(2f).alpha < 1f)
        assertTrue("p=3 fades more than p=2", slot(3f).alpha < slot(2f).alpha)
        assertTrue("alpha never dips below the floor", slot(6f).alpha >= DeckMinAlpha - 0.001f)
        assertEquals("deep cards reach the floor", DeckMinAlpha, slot(10f).alpha, 0.001f)
    }

    @Test
    fun `pixel mode never fades`() {
        assertEquals(1f, px(0f).alpha, 0.001f)
        assertEquals(1f, px(3f).alpha, 0.001f)
        assertEquals(1f, px(-2f).alpha, 0.001f)
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

    // ── Legacy GRID mode: 2-column cell layout ──────────────────────────────────────────────────

    @Test
    fun `grid cells lay out in two columns of 3-4 aspect`() {
        val side = 16f
        val hGap = 14f
        val vGap = 30f
        val top = 64f
        val size = IntSize(1000, 2000)
        val c0 = gridCellRect(0, size, 2, hGap, vGap, side, top)
        val c1 = gridCellRect(1, size, 2, hGap, vGap, side, top)
        val c2 = gridCellRect(2, size, 2, hGap, vGap, side, top)

        val expectedW = (1000f - 2 * side - hGap) / 2f
        assertEquals("cell width fills the two columns", expectedW, c0.width, 0.5f)
        assertEquals("cells are 3:4 (portrait)", expectedW * 4f / 3f, c0.height, 0.5f)
        // Index 0 = col 0, index 1 = col 1 of the same row.
        assertEquals(side, c0.left, 0.5f)
        assertEquals(side + expectedW + hGap, c1.left, 0.5f)
        assertEquals("cols 0 and 1 share a row", c0.top, c1.top, 0.5f)
        // Index 2 wraps to the next row, col 0.
        assertEquals(side, c2.left, 0.5f)
        assertTrue("index 2 drops to the next row", c2.top > c0.top)
        assertEquals(c0.top + c0.height + vGap, c2.top, 0.5f)
    }

    @Test
    fun `grid cell rect is empty for a zero-size container`() {
        val r = gridCellRect(0, IntSize(0, 0), 2, 14f, 30f, 16f, 64f)
        assertEquals(0f, r.width, 0f)
        assertEquals(0f, r.height, 0f)
    }
}
