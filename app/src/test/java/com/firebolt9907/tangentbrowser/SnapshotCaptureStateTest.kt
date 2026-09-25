package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.ui.content.SnapshotCaptureState
import com.firebolt9907.tangentbrowser.state.snapshotSamplesHaveDetail
import org.junit.Assert.*
import org.junit.Test

class SnapshotCaptureStateTest {
    @Test fun loadingAndClearFramesCannotPopulateHistory() {
        val state = SnapshotCaptureState("https://a.test")
        assertNull(state.request())
        assertFalse(snapshotSamplesHaveDetail(IntArray(1024) { 0xffeeeeee.toInt() }))
        assertTrue(snapshotSamplesHaveDetail(intArrayOf(0xffffffff.toInt(), 0xff333333.toInt())))
        assertTrue(state.settle(state.url, state.generation))
        assertNotNull(state.request())
    }

    @Test fun navigationRejectsCaptureEvenWhenReturningToSameUrl() {
        val state = SnapshotCaptureState("https://a.test")
        state.settle(state.url, state.generation)
        val old = state.request()!!
        state.navigate("https://b.test")
        assertFalse(state.accept(old))
        state.navigate("https://a.test")
        state.settle(state.url, state.generation)
        assertFalse(state.accept(old))
    }

    @Test fun lateConversionCannotOverwriteNewerCapture() {
        val state = SnapshotCaptureState("https://a.test")
        state.settle(state.url, state.generation)
        val first = state.request()!!
        val second = state.request()!!
        assertTrue(state.accept(second))
        assertFalse(state.accept(first))
    }

    @Test fun oldSettleTimerCannotArmNewPageOrReload() {
        val state = SnapshotCaptureState("https://a.test")
        val generation = state.generation
        state.invalidate()
        assertFalse(state.settle(state.url, generation))
        assertNull(state.request())
        assertTrue(state.settle(state.url, state.generation))
        val ticket = state.request()!!
        state.active = false
        assertFalse(state.accept(ticket))
    }

    @Test fun spaNavigationGetsItsOwnSettledCapture() {
        val state = SnapshotCaptureState("https://a.test/one")
        state.navigate("https://a.test/two")
        assertFalse(state.settle("https://a.test/one", state.generation))
        assertTrue(state.settle("https://a.test/two", state.generation))
        assertEquals("https://a.test/two", state.request()!!.url)
    }
}
