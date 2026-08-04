package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.state.SsoChainStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The progress-bar math behind the "Completing sign-in" cover. */
class SsoChainStatsTest {

    // ── updatedEstimate ───────────────────────────────────────────────────────

    @Test fun firstObservationSeedsTheEstimate() =
        assertEquals(6f, SsoChainStats.updatedEstimate(null, 6), 0.001f)

    @Test fun repeatedIdenticalChainsConverge() {
        var estimate = SsoChainStats.updatedEstimate(null, 6)
        repeat(5) { estimate = SsoChainStats.updatedEstimate(estimate, 6) }
        assertEquals(6f, estimate, 0.001f)
    }

    /** One unusual re-auth nudges the estimate; it must not redefine it. */
    @Test fun singleOutlierOnlyNudges() {
        val estimate = SsoChainStats.updatedEstimate(6f, 14)
        assertTrue("moved up", estimate > 6f)
        assertTrue("but nowhere near the outlier", estimate < 10f)
    }

    @Test fun estimateTracksASustainedShift() {
        var estimate = 6f
        repeat(20) { estimate = SsoChainStats.updatedEstimate(estimate, 10) }
        assertEquals(10f, estimate, 0.1f)
    }

    // ── progressFor ───────────────────────────────────────────────────────────

    @Test fun noHistory_isIndeterminate() = assertNull(SsoChainStats.progressFor(3, 0, null))

    @Test fun zeroEstimate_isIndeterminate() = assertNull(SsoChainStats.progressFor(3, 0, 0f))

    /** Three hops entered, third fully loaded ⇒ 3 of 6. */
    @Test fun midChain_isProportional() =
        assertEquals(0.5f, SsoChainStats.progressFor(3, 100, 6f)!!, 0.001f)

    @Test fun startOfChain_isZero() =
        assertEquals(0f, SsoChainStats.progressFor(0, 0, 6f)!!, 0.001f)

    /** The in-flight document subdivides its own hop: half of hop 3 ⇒ 2.5 of 6. */
    @Test fun partialPageLoad_subdividesTheHop() =
        assertEquals(2.5f / 6f, SsoChainStats.progressFor(3, 50, 6f)!!, 0.001f)

    /** The seam is continuous: a hop finishing == the next hop at 0%, so the bar never jumps back. */
    @Test fun hopBoundaryIsContinuous() {
        val endOfHop3 = SsoChainStats.progressFor(3, 100, 6f)!!
        val startOfHop4 = SsoChainStats.progressFor(4, 0, 6f)!!
        assertEquals(endOfHop3, startOfHop4, 0.0001f)
    }

    @Test fun progressWithinAHopIsMonotonic() {
        var previous = -1f
        for (percent in 0..100 step 5) {
            val p = SsoChainStats.progressFor(3, percent, 6f)!!
            assertTrue("percent=$percent went backwards", p >= previous)
            previous = p
        }
    }

    @Test fun outOfRangePercentIsClamped() {
        assertEquals(
            SsoChainStats.progressFor(3, 100, 6f)!!,
            SsoChainStats.progressFor(3, 500, 6f)!!,
            0.0001f,
        )
        assertEquals(
            SsoChainStats.progressFor(3, 0, 6f)!!,
            SsoChainStats.progressFor(3, -20, 6f)!!,
            0.0001f,
        )
    }

    /** Never full while still running — a bar parked at 100% reads as hung. */
    @Test fun atEstimate_stillShortOfFull() {
        val p = SsoChainStats.progressFor(6, 100, 6f)!!
        assertTrue("$p should be < 1", p < 1f)
    }

    /** A longer-than-usual login keeps creeping instead of pinning flat. */
    @Test fun overshootKeepsCreepingAndStaysBounded() {
        val first = SsoChainStats.progressFor(8, 100, 6f)!!
        val later = SsoChainStats.progressFor(12, 100, 6f)!!
        assertTrue("progress advances past the estimate", later > first)
        assertTrue("but never completes", later < 1f)
    }

    @Test fun neverNegative() =
        assertTrue(SsoChainStats.progressFor(0, 0, 12f)!! >= 0f)
}
