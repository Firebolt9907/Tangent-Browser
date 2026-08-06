package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.ui.PullAction
import com.firebolt9907.tangentbrowser.ui.nextPullAction
import com.firebolt9907.tangentbrowser.ui.pullSelectionPosition
import com.firebolt9907.tangentbrowser.ui.pullStretchFor
import com.firebolt9907.tangentbrowser.ui.slot
import com.firebolt9907.tangentbrowser.ui.content.rubberBandPull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Slot mapping for the multi-function overscroll pull. The load-bearing property is that the
 * *centre* band is Refresh: with the menu enabled, a straight-down pull must still behave exactly
 * like plain pull-to-refresh, which is what makes the feature safe to default on.
 */
class PullActionSlotTest {

    private val slot = 84f

    private fun walk(vararg dxs: Float): PullAction {
        var action = PullAction.Refresh
        dxs.forEach { action = nextPullAction(action, it, slot, readerAvailable = true) }
        return action
    }

    @Test
    fun `no horizontal travel refreshes`() {
        assertEquals(PullAction.Refresh, walk(0f))
    }

    @Test
    fun `small wobble stays on refresh`() {
        assertEquals(PullAction.Refresh, walk(30f))
        assertEquals(PullAction.Refresh, walk(-30f))
    }

    @Test
    fun `a slot left is new tab and a slot right is reader`() {
        assertEquals(PullAction.NewTab, walk(-slot))
        assertEquals(PullAction.Reader, walk(slot))
    }

    @Test
    fun `travel past the end slots clamps rather than wrapping`() {
        assertEquals(PullAction.NewTab, walk(-slot, -slot * 3, -slot * 6))
        assertEquals(PullAction.Reader, walk(slot, slot * 3, slot * 6))
    }

    /**
     * The hysteresis itself: jitter across the geometric midpoint must not flip the selection. With
     * a plain nearest-slot rounding every one of these samples would land on a different action than
     * the one before it, strobing the chips and firing the selection haptic each time.
     */
    @Test
    fun `jitter around the halfway point does not flip the selection`() {
        var action = PullAction.Refresh
        val samples = listOf(0.48f, 0.52f, 0.49f, 0.55f, 0.47f, 0.51f)
        samples.forEach { fraction ->
            action = nextPullAction(action, slot * fraction, slot, readerAvailable = true)
            assertEquals(PullAction.Refresh, action)
        }
    }

    @Test
    fun `committing needs more travel than the midpoint but the slot is still reachable`() {
        assertEquals(PullAction.Refresh, walk(slot * 0.58f))
        assertEquals(PullAction.Reader, walk(slot * 0.7f))
    }

    /** Coming back out of a slot is symmetric — the same band has to be crossed the other way. */
    @Test
    fun `leaving a committed slot needs the same band crossed back`() {
        // Committed to Reader at 0.7, a small retreat holds it…
        assertEquals(PullAction.Reader, walk(slot * 0.7f, slot * 0.5f))
        // …and a real retreat past the band gives Refresh back.
        assertEquals(PullAction.Refresh, walk(slot * 0.7f, slot * 0.3f))
    }

    @Test
    fun `unavailable reader falls back to refresh instead of a dead slot`() {
        var action = PullAction.Refresh
        action = nextPullAction(action, slot, slot, readerAvailable = false)
        assertEquals(PullAction.Refresh, action)
        action = nextPullAction(action, slot * 6, slot, readerAvailable = false)
        assertEquals(PullAction.Refresh, action)
        // The left slot is unaffected by reader availability.
        assertEquals(
            PullAction.NewTab,
            nextPullAction(PullAction.Refresh, -slot, slot, readerAvailable = false),
        )
    }

    @Test
    fun `a degenerate slot width holds the current selection rather than dividing by zero`() {
        assertEquals(PullAction.Refresh, nextPullAction(PullAction.Refresh, 500f, 0f, true))
    }

    @Test
    fun `the continuous highlight tracks between slots and clamps at the ends`() {
        assertEquals(0f, pullSelectionPosition(0f, slot, true), 0.001f)
        assertEquals(0.5f, pullSelectionPosition(slot * 0.5f, slot, true), 0.001f)
        assertEquals(1f, pullSelectionPosition(slot * 9f, slot, true), 0.001f)
        assertEquals(-1f, pullSelectionPosition(-slot * 9f, slot, true), 0.001f)
    }

    @Test
    fun `the highlight stops at centre when reader is unavailable`() {
        assertEquals(0f, pullSelectionPosition(slot * 9f, slot, false), 0.001f)
        assertEquals(-1f, pullSelectionPosition(-slot * 9f, slot, false), 0.001f)
    }

    /**
     * The stretch is what keeps the hysteresis band from reading as the menu having frozen, so its
     * load-bearing property is that it is *fully* extended exactly where the selection hands over —
     * the snap lands at the end of the stretch rather than out of nowhere.
     */
    @Test
    fun `the selected blob stretches toward the finger and maxes out at the handover`() {
        val centre = PullAction.Refresh.slot
        assertEquals(0f, pullStretchFor(0f, centre, centre), 0.001f)
        assertTrue(pullStretchFor(0.2f, centre, centre) > 0f)
        assertTrue(pullStretchFor(0.5f, centre, centre) > pullStretchFor(0.2f, centre, centre))
        // Full extension at the commit boundary, and never beyond it.
        assertEquals(1f, pullStretchFor(0.62f, centre, centre), 0.02f)
        assertEquals(1f, pullStretchFor(5f, centre, centre), 0.001f)
        // Symmetric the other way.
        assertEquals(-1f, pullStretchFor(-0.62f, centre, centre), 0.02f)
    }

    @Test
    fun `only the chip holding the selection stretches`() {
        val centre = PullAction.Refresh.slot
        assertEquals(0f, pullStretchFor(0.5f, PullAction.NewTab.slot, centre), 0.001f)
        assertEquals(0f, pullStretchFor(0.5f, PullAction.Reader.slot, centre), 0.001f)
    }

    /** After the handover the new owner starts near-unstretched — the blob resets, it doesn't jump. */
    @Test
    fun `the stretch resets onto the newly selected chip`() {
        val reader = PullAction.Reader.slot
        assertTrue(abs(pullStretchFor(0.7f, reader, reader)) < 0.5f)
    }
}

/**
 * The pull's resistance curve. Both properties here are what the gesture *feels* like: 1:1 tracking
 * at the start (so the indicator leaves with the finger rather than at half speed), and an asymptote
 * rather than a clamp (so it never dead-stops while the finger is still moving).
 */
class RubberBandPullTest {

    private val max = 400f

    @Test
    fun `no travel is no pull`() {
        assertEquals(0f, rubberBandPull(0f, max), 0.001f)
        assertEquals(0f, rubberBandPull(-50f, max), 0.001f)
    }

    @Test
    fun `tracks the finger one to one at the start`() {
        // Within the first few px the curve is indistinguishable from identity.
        assertEquals(4f, rubberBandPull(4f, max), 0.05f)
    }

    @Test
    fun `resistance grows with distance`() {
        val first = rubberBandPull(50f, max) - rubberBandPull(0f, max)
        val later = rubberBandPull(350f, max) - rubberBandPull(300f, max)
        assertTrue("resistance should increase with travel, got $first then $later", later < first)
    }

    @Test
    fun `never reaches the asymptote but never stops moving either`() {
        val far = rubberBandPull(max * 8, max)
        assertTrue("must stay under the asymptote, was $far", far < max)
        // Still responding: a further pull at an extreme distance moves the indicator.
        assertTrue(rubberBandPull(max * 9, max) > far)
    }

    @Test
    fun `an unconfigured band falls back to the linear rate`() {
        assertEquals(50f, rubberBandPull(100f, 0f), 0.001f)
    }
}
