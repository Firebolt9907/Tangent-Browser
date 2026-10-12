package com.firebolt9907.tangentbrowser.ui.grid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverviewCardMotionTest {
    @Test fun heldBackgroundSizeHasNoShrinkVelocityOnRelease() {
        val release = Triple(-400f, -200f, 0.62f)
        val slot = DeckSlot(-100f, 200f, 0.665f, 1f)
        val arc = FlickReleaseArc(2400f, -1.4f, 1000f, 2000f,
            backgroundScaleVelocityPerSec = 0f)
        fun pose(t: Float) = overviewCardPose(slot, 0f, 1f, t, 1f, 0f,
            release, arc, overviewInertiaInfluence(1), isBackgroundCard = true)
        assertEquals(DeckSlot(release.first, release.second, release.third, 1f), pose(0f))
        val dt = 0.0001f
        val next = pose(dt / (FlickReleaseArc.DurationMillis / 1000f))
        assertEquals(0f, (next.scale - release.third) / dt, 0.005f)
        assertEquals(-2400f, (next.ty + 2000f * next.scale -
            (release.second + 2000f * release.third)) / dt, 8f)
        assertEquals(slot, pose(1f))
    }

    @Test fun landingSpendsItsTailEasingIntoTheSlot() {
        val release = Triple(0f, 0f, 1f)
        val slot = DeckSlot(150f, 220f, 0.7f, 1f)
        val arc = FlickReleaseArc(0f, 0f, 1000f, 2000f)
        fun pose(t: Float) = overviewCardPose(slot, 0f, 1f, t, 0.2f, 0f, release, arc)
        assertTrue("at least 80% of the scale change should happen in the first half",
            pose(0.5f).scale - slot.scale < (release.third - slot.scale) * 0.2f)
        assertTrue("the last fifth should be a gentle landing with less than 1% left",
            pose(0.8f).scale - slot.scale < (release.third - slot.scale) * 0.01f)
        assertEquals(slot, pose(1f))
    }

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
        val slot = DeckSlot(100f, 220f, 0.7f, 1f)
        val release = Triple(0f, -20f, 0.94f)
        val arc = FlickReleaseArc(5000f, -4f, 1000f, 2000f)

        val start = overviewCardPose(slot, 0f, 0f, 0f, 1f, 0f, release, arc)
        val middle = overviewCardPose(slot, 0f, 1f, 0.5f, 1f, 0f, release, arc)
        val end = overviewCardPose(slot, 0f, 1f, 1f, 1f, 0f, release, arc)

        assertEquals(DeckSlot(0f, -20f, 0.94f, 1f), start)
        assertTrue("flick should throw the card above both endpoints", middle.ty + 2000f * middle.scale < release.second + 2000f * release.third)
        assertTrue("flick should briefly shrink below its resting deck scale", (1..99).any {
            overviewCardPose(slot, 0f, 1f, it / 100f, 1f, 0f, release, arc).scale < slot.scale
        })
        assertEquals(slot, end)
    }
    @Test fun firstFramePreservesBottomEdgeAndScaleVelocity() {
        val release = Triple(30f, 100f, 0.9f)
        val arc = FlickReleaseArc(2400f, -1.4f, 1000f, 2000f)
        val slot = DeckSlot(150f, 220f, 0.7f, 1f)
        fun pose(t: Float) = overviewCardPose(slot, 0f, 1f, t, 0.2f, 0f, release, arc)
        val dt = 0.0001f
        val next = pose(dt / (FlickReleaseArc.DurationMillis / 1000f))
        assertEquals(-2400f, (next.ty + 2000f * next.scale -
            (release.second + 2000f * release.third)) / dt, 8f)
        assertEquals(-1.4f, (next.scale - release.third) / dt, 0.005f)
        val nearEnd = pose(1f - dt / (FlickReleaseArc.DurationMillis / 1000f))
        assertEquals(slot.ty, nearEnd.ty, 0.002f)
        assertEquals(slot.scale, nearEnd.scale, 0.00001f)
    }

    @Test fun fasterFlickCarriesFurtherBeforeReturning() {
        val slot = DeckSlot(150f, 220f, 0.7f, 1f)
        val release = Triple(30f, 100f, 0.9f)
        fun pose(speed: Float) = overviewCardPose(slot, 0f, 1f, 0.3f, 0.2f, 0f,
            release, FlickReleaseArc(speed, -speed / 2000f, 1000f, 2000f))
        val slow = pose(1200f)
        val fast = pose(3000f)
        assertTrue(fast.scale < slow.scale)
        assertTrue(fast.ty + 2000f * fast.scale < slow.ty + 2000f * slow.scale)
    }

    @Test fun backgroundCardsShareReleaseVelocityButHaveLessThrow() {
        val slot = DeckSlot(150f, 220f, 0.7f, 1f)
        val release = Triple(30f, 100f, 0.9f)
        val arc = FlickReleaseArc(2400f, -1.4f, 1000f, 2000f)
        fun pose(t: Float, depth: Int) = overviewCardPose(slot, 0f, 1f, t, 0.2f, 0f,
            release, arc, overviewInertiaInfluence(depth))
        for (depth in listOf(-4, -1, 0, 1, 4)) {
            assertEquals(pose(0f, 0), pose(0f, depth))
            assertEquals(slot, pose(1f, depth))
            val dt = 0.0001f
            val next = pose(dt / (FlickReleaseArc.DurationMillis / 1000f), depth)
            assertEquals(-2400f, (next.ty + 2000f * next.scale - 1900f) / dt, 8f)
        }
        val front = pose(0.5f, 0)
        val back = pose(0.5f, 3)
        assertTrue(back.scale > front.scale)
        assertTrue(back.ty + 2000f * back.scale > front.ty + 2000f * front.scale)
        assertEquals(pose(0.5f, -3), back)
    }

    @Test fun fastReleaseDeceleratesAtDisplayFrameIntervalsBeforeReturning() {
        val release = Triple(30f, 100f, 0.9f)
        val slot = DeckSlot(150f, 220f, 0.7f, 1f)
        val duration = FlickReleaseArc.DurationMillis / 1000f
        // Include endpoints ahead of, level with, and behind the finger's release anchor.
        for (bottomTravel in listOf(-250f, 0f, 250f)) {
            val target = slot.copy(ty = 1900f + bottomTravel - 2000f * slot.scale)
            for (fps in listOf(60, 120)) {
                val arc = FlickReleaseArc(5000f, 0f, 1000f, 2000f)
                fun bottom(seconds: Float): Float {
                    val pose = overviewCardPose(target, 0f, 1f, seconds / duration,
                        0.2f, 0f, release, arc)
                    return pose.ty + 2000f * pose.scale
                }
                var previousBottom = bottom(0f)
                var previousVelocity = -arc.upwardVelocityPxS
                var turned = false
                for (frame in 1..(fps * duration).toInt()) {
                    val currentBottom = bottom(frame.toFloat() / fps)
                    val velocity = (currentBottom - previousBottom) * fps
                    if (velocity >= 0f) {
                        turned = true
                    } else if (!turned) {
                        assertTrue("upward momentum must decelerate at $fps Hz, travel=$bottomTravel",
                            velocity >= previousVelocity - 1f)
                    }
                    previousBottom = currentBottom
                    previousVelocity = velocity
                }
                assertTrue("release must coast upward before returning", bottom(1f / fps) < 1900f)
                assertTrue("release must turn toward its slot", turned)
                assertEquals(target, overviewCardPose(target, 0f, 1f, 1f,
                    0.2f, 0f, release, arc))
            }
        }
    }

}
