package com.firebolt9907.tangentbrowser.ui.motion

import org.junit.Assert.*
import org.junit.Test

class ReleaseEasingTest {
    @Test fun carriesMeasuredSpeedAndArrivesAtRest() {
        val easing = releaseEasing(400f, 1800f, 200)
        val dt = 0.0001f
        assertEquals(1800f, easing.transform(dt / 0.2f) * 400f / dt, 5f)
        assertEquals(0f, easing.transform(0f), 0f)
        assertEquals(1f, easing.transform(1f), 0f)
        assertEquals(1f, easing.transform(0.9999f), 0.00001f)
    }

    @Test fun keyboardCurveNeverOvershootsOrReverses() {
        for (distance in listOf(-800f, -0.1f, 0f, 0.1f, 800f)) {
            for (velocity in listOf(-20000f, -500f, 0f, 500f, 20000f)) {
                val easing = releaseEasing(distance, velocity, 200)
                var previous = 0f
                for (step in 0..1000) {
                    val value = easing.transform(step / 1000f)
                    assertTrue(value in 0f..1f)
                    assertTrue(value >= previous - 0.000001f)
                    previous = value
                }
            }
        }
    }

    @Test fun fasterReleaseAdvancesFurtherOnTheSameSharedClock() {
        val slow = releaseEasing(400f, 500f, 200)
        val fast = releaseEasing(400f, 2500f, 200)
        assertTrue(fast.transform(0.25f) > slow.transform(0.25f))
        // Reversing the physical direction preserves the paired sheet/IME phase.
        assertEquals(fast.transform(0.4f), releaseEasing(-400f, -2500f, 200).transform(0.4f), 0f)
    }
}
