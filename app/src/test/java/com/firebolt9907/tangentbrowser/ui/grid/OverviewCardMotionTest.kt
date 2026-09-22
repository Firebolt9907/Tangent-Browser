package com.firebolt9907.tangentbrowser.ui.grid

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
}
