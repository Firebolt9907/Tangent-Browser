package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.ui.newTabRevealProgress
import org.junit.Assert.*
import org.junit.Test

class NewTabRevealProgressTest {
    @Test fun commitAndCancellationReachTheExactEndpoints() {
        assertEquals(0f, newTabRevealProgress(0f, 400f), 0f)
        assertEquals(1f, newTabRevealProgress(-400f, 400f), 0f)
        assertEquals(1f, newTabRevealProgress(-450f, 400f), 0f)
        assertEquals(0f, newTabRevealProgress(40f, 400f), 0f)
    }

    @Test fun revealGrowsThroughoutTheDragRatherThanJumpingAtCommitThreshold() {
        val frames = (0..100).map { newTabRevealProgress(-it.toFloat(), 100f) }
        assertTrue(frames.zipWithNext().all { (a, b) -> b >= a })
        assertTrue(newTabRevealProgress(-35f, 100f) in 0.1f..0.5f)
        assertEquals(0.5f, newTabRevealProgress(-50f, 100f), 0.0001f)
        assertEquals(0f, newTabRevealProgress(-50f, 0f), 0f)
    }
}
