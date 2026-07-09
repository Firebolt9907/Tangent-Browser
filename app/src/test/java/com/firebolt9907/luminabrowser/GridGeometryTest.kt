package com.firebolt9907.luminabrowser

import androidx.compose.ui.unit.IntSize
import com.firebolt9907.luminabrowser.ui.grid.gridCellRect
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The tab-grid cell geometry (extracted to GridModel.kt). Pins the column/row placement, the fixed
 * 3:4 card aspect, and the zero-size guard so a regression can't silently misplace every tab card.
 */
class GridGeometryTest {

    // A generous, evenly-dividing container so the expected values are exact.
    private val container = IntSize(1000, 2000)
    private val columns = 2
    private val hGap = 14f
    private val vGap = 36f
    private val sideMargin = 16f
    private val topMargin = 64f

    private fun cell(index: Int) =
        gridCellRect(index, container, columns, hGap, vGap, sideMargin, topMargin)

    // cellW = (1000 - 2*16 - (2-1)*14) / 2 = 954 / 2 = 477 ; cellH = 477 * 4/3 = 636
    private val expectedW = 477f
    private val expectedH = 636f

    @Test
    fun `first cell sits at the top-left margins`() {
        val r = cell(0)
        assertEquals(sideMargin, r.left, 0.01f)
        assertEquals(topMargin, r.top, 0.01f)
        assertEquals(expectedW, r.width, 0.01f)
        assertEquals(expectedH, r.height, 0.01f)
    }

    @Test
    fun `cards keep a fixed 3 to 4 aspect ratio`() {
        val r = cell(0)
        assertEquals(r.width * 4f / 3f, r.height, 0.01f)
    }

    @Test
    fun `second cell is one column to the right`() {
        val r = cell(1)
        assertEquals(sideMargin + expectedW + hGap, r.left, 0.01f)
        assertEquals(topMargin, r.top, 0.01f)
    }

    @Test
    fun `third cell wraps to the next row`() {
        val r = cell(2)
        assertEquals(sideMargin, r.left, 0.01f)
        assertEquals(topMargin + expectedH + vGap, r.top, 0.01f)
    }

    @Test
    fun `an empty container yields a zero rect`() {
        val r = gridCellRect(0, IntSize(0, 0), columns, hGap, vGap, sideMargin, topMargin)
        assertEquals(0f, r.left, 0f)
        assertEquals(0f, r.top, 0f)
        assertEquals(0f, r.width, 0f)
        assertEquals(0f, r.height, 0f)
    }
}
