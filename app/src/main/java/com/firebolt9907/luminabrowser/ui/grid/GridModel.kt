package com.firebolt9907.luminabrowser.ui.grid

import android.graphics.Bitmap
import androidx.core.net.toUri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.firebolt9907.luminabrowser.ui.shape.squirclePath
import kotlin.math.min

/**
 * Non-composable model, geometry, and small helpers for the tab grid — split out of `Grid.kt`
 * so [TabGridOverlay] there holds the rendering and this holds the plumbing.
 */

val GridSideMarginDp = 16.dp
val GridTopMarginDp = 64.dp
val GridBottomMarginDp = 32.dp
val GridGapDp = 14.dp
const val GridColumns = 2

/**
 * Frame-stable description of one tab card. Deliberately carries NO per-frame values: everything
 * that changes while the finger moves (carousel shift, drag position, scale, progress) is read
 * inside [TabGridOverlay]'s per-card `graphicsLayer` blocks via providers, so the list — and with
 * it the whole composition — stays untouched during the drag. It only changes when tabs, the
 * selection, snapshots, or a mid-switch handoff change.
 */
data class GridTab(
    val id: String,
    val url: String,
    val title: String,
    val snapshot: Bitmap?,
    /**
     * Static X of this tab's carousel slot (px): `(i - selectedIndex) * pageStride`, plus the
     * frozen mid-switch handoff offset when one is active. The overlay adds the live carousel
     * shift on top in the draw phase.
     */
    val browseBaseX: Float = 0f,
    /** Top padding (px) the browse layer applies (site-prefs top inset). */
    val browseTopPadPx: Int = 0,
    /** Slot offset from the selected tab; spreads cards around the finger during the gesture. */
    val slotDelta: Int = 0,
)

data class GridRect(val left: Float, val top: Float, val width: Float, val height: Float)

fun gridCellRect(
    index: Int,
    containerSize: IntSize,
    columns: Int,
    horizontalGapPx: Float,
    verticalGapPx: Float,
    sideMarginPx: Float,
    topMarginPx: Float,
): GridRect {
    val w = containerSize.width.toFloat()
    val h = containerSize.height.toFloat()
    if (w <= 0f || h <= 0f) return GridRect(0f, 0f, 0f, 0f)
    val cellW = (w - 2 * sideMarginPx - (columns - 1) * horizontalGapPx) / columns
    // Tab cards are a fixed 3:4 (portrait) aspect — the page is cropped to its top region rather
    // than showing the full (very tall) viewport. See [TabGridOverlay] for the crop/morph math.
    val cellH = cellW * 4f / 3f
    val col = index % columns
    val row = index / columns
    val left = sideMarginPx + col * (cellW + horizontalGapPx)
    val top = topMarginPx + row * (cellH + verticalGapPx)
    return GridRect(left, top, cellW, cellH)
}

/**
 * A squircle outline cropped to [cropHeightPx] regardless of the layout height. Lets a card keep
 * a constant layout size (no per-frame measure/layout) while the visible page slice animates from
 * the full viewport down to the 3:4 grid cell purely in the draw phase (assigned to
 * `graphicsLayer.shape` each frame).
 */
internal class CardCropShape(
    private val radiusPx: Float,
    private val cropHeightPx: Float,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val h = cropHeightPx.coerceIn(1f, size.height)
        val r = radiusPx.coerceAtMost(min(size.width, h) / 2f)
        if (r <= 0f) return Outline.Rectangle(Rect(0f, 0f, size.width, h))
        return Outline.Generic(squirclePath(size.width, h, r))
    }
}

internal class SnapshotTracker(initial: Bitmap?) {
    var previous by mutableStateOf<Bitmap?>(null)
    var current by mutableStateOf<Bitmap?>(initial)

    fun update(next: Bitmap?) {
        if (next != current) {
            previous = current
            current = next
        }
    }
}

internal fun hostLabel(url: String): String {
    if (url.isBlankTabUrl()) return ""
    return try {
        url.toUri().host.orEmpty().removePrefix("www.")
    } catch (_: Throwable) {
        ""
    }
}

internal fun String.isBlankTabUrl(): Boolean = isBlank() || this == "about:blank"

internal fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
