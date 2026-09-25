package com.firebolt9907.tangentbrowser

import com.firebolt9907.tangentbrowser.state.snapshotSamplesHaveDetail
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotQualityTest {
    @Test fun rejectsBlackAndUniformCompositorFrames() {
        for (color in listOf(0xff000000.toInt(), 0xff121212.toInt(), 0xffffffff.toInt())) {
            assertFalse(snapshotSamplesHaveDetail(IntArray(1024) { color }))
        }
    }

    @Test fun rejectsNearBlackNoiseAndTransparentRgbDetail() {
        assertFalse(snapshotSamplesHaveDetail(IntArray(1024) {
            0xff000000.toInt() or ((it % 24) * 0x010101)
        }))
        assertFalse(snapshotSamplesHaveDetail(intArrayOf(0x00ffffff, 0x00000000, 0x00ff0000)))
    }

    @Test fun keepsDarkPagesWithSparseVisibleText() {
        val samples = IntArray(1024) { 0xff121212.toInt() }
        samples[510] = 0xffcccccc.toInt()
        samples[511] = 0xffcccccc.toInt()
        assertTrue(snapshotSamplesHaveDetail(samples))
    }

    @Test fun keepsLightPagesAndColoredContent() {
        assertTrue(snapshotSamplesHaveDetail(intArrayOf(0xffffffff.toInt(), 0xff222222.toInt())))
        assertTrue(snapshotSamplesHaveDetail(intArrayOf(0xff000000.toInt(), 0xff0055aa.toInt())))
        assertFalse(snapshotSamplesHaveDetail(intArrayOf()))
    }
}
