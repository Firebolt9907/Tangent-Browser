package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.ui.customtab.CustomTabBackPreview
import org.junit.Assert.*
import org.junit.Test

class CustomTabBackPreviewTest {
    @Test fun gestureUsesHistoryPixelsAndFreezesThem() {
        val state = CustomTabBackPreview<String>()
        val token = state.start("custom", "https://previous", "previous pixels", "current pixels")
        state.loadPrevious("custom", "https://previous", "later cache pixels")
        state.captureCurrent(token, "custom", "fresh current pixels")
        assertEquals("previous pixels", state.preview.value?.previous)
        assertEquals("fresh current pixels", state.preview.value?.current)
    }

    @Test fun oldCaptureAndCancellationCannotReplaceANewGesture() {
        val state = CustomTabBackPreview<String>()
        val old = state.start("custom", "https://a", "A", "B")
        val current = state.start("custom", "https://b", "B", "C")
        state.captureCurrent(old, "custom", "stale B")
        state.cancel(old)
        state.commit(old)
        assertFalse(state.isCurrent(old))
        assertTrue(state.isCurrent(current))
        assertEquals(current, state.preview.value?.generation)
        assertEquals("C", state.preview.value?.current)
        assertFalse(state.preview.value!!.committed)
    }

    @Test fun diskLoadMustBelongToTheHistoryTarget() {
        val state = CustomTabBackPreview<String>()
        state.start("custom", "https://a", null, "B")
        state.loadPrevious("main", "https://a", "other tab")
        state.loadPrevious("custom", "https://b", "wrong URL")
        assertNull(state.preview.value?.previous)
        state.loadPrevious("custom", "https://a", "A")
        assertEquals("A", state.preview.value?.previous)
    }

    @Test fun committedCoverWaitsForMatchingPaintAndReleasesOutgoingPixels() {
        val state = CustomTabBackPreview<String>()
        val token = state.start("custom", "https://a", "A", "B")
        state.commit(token)
        state.captureCurrent(token, "custom", "late B")
        state.cancel(token)
        assertTrue(state.preview.value!!.committed)
        assertNull(state.preview.value?.current)
        state.painted("main", "https://a")
        state.painted("custom", "https://b")
        assertEquals("A", state.preview.value?.previous)
        state.painted("custom", "https://a")
        assertNull(state.preview.value)
    }

    @Test fun cancellationDropsPixelsWithoutNavigatingOrCreatingACover() {
        val state = CustomTabBackPreview<String>()
        val token = state.start("custom", "https://a", "A", "B")
        state.cancel(token)
        state.captureCurrent(token, "custom", "late capture")
        assertNull(state.preview.value)
    }

    @Test fun missingHistorySnapshotDoesNotCoverTheLivePageWithBlankPixels() {
        val state = CustomTabBackPreview<String>()
        val token = state.start("custom", "https://a", null, "B")
        state.commit(token)
        assertNull(state.preview.value)
    }
}
