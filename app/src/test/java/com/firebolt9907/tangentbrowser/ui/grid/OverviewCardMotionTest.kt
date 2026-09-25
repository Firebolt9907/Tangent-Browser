package com.firebolt9907.tangentbrowser.ui.grid

import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverviewCardMotionTest {
    @Test fun fullyDraggedCardStillTravelsFromReleaseToSlot() {
        val slot = DeckSlot(180f, 240f, 0.75f, 0.9f)
        val release = Triple(-90f, -400f, 0.35f)
        fun pose(t: Float) = overviewCardPose(slot, 0f, 1f, t, 1f, 0f, release)
        assertEquals(DeckSlot(-90f, -400f, 0.35f, 1f), pose(0f))
        assertTrue(pose(0.5f).tx > release.first)
        assertTrue(pose(0.5f).ty < slot.ty)
        assertTrue(pose(0.5f).scale > release.third)
        assertEquals(slot, pose(1f))
    }

    @Test fun releasePathIsIndependentOfClampedOverviewProgress() {
        val slot = DeckSlot(100f, 200f, 0.7f, 1f)
        val release = Triple(20f, -100f, 0.4f)
        assertEquals(
            overviewCardPose(slot, 0f, 0.8f, 0.3f, 0.7f, 0f, release),
            overviewCardPose(slot, 0f, 1f, 0.3f, 1f, 0f, release),
        )
    }

    @Test fun quickFlickArcsAboveAndShrinksBelowItsDeckSlotBeforeLanding() {
        // Arc bend is temporarily disabled (FLICK_ARC_BEND_ENABLED = false). Keep the test wired —
        // it re-validates the visuals as soon as the kill-switch is flipped — but skip it meanwhile.
        assumeTrue(FLICK_ARC_BEND_ENABLED)

        val slot = DeckSlot(100f, 220f, 0.7f, 1f)
        val release = Triple(0f, -20f, 0.94f)
        val arc = FlickReleaseArc(heightPx = 320f, scaleDip = 0.2f)

        val start = overviewCardPose(slot, 0f, 0f, 0f, 1f, 0f, release, arc)
        val middle = overviewCardPose(slot, 0f, 1f, 0.5f, 1f, 0f, release, arc)
        val end = overviewCardPose(slot, 0f, 1f, 1f, 1f, 0f, release, arc)

        assertEquals(DeckSlot(0f, -20f, 0.94f, 1f), start)
        assertTrue("flick should throw the card above both endpoints", middle.ty < release.second)
        assertTrue("flick should briefly shrink below its resting deck scale", middle.scale < slot.scale)
        assertEquals(slot, end)
    }
}
