package com.firebolt9907.luminabrowser.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import com.firebolt9907.luminabrowser.ui.shape.SquircleShape

/**
 * Persistent rounded-corner mask: the live GeckoView surface can't be clipped to a rounded shape
 * directly, so we paint the four corner cut-outs with the background colour on top of the content.
 * This keeps the browsing viewport's rounded corners visible at all times (idle, scrolling, pill
 * drag) — it only fades out as the all-tabs grid opens, which draws its own rounded cards.
 * Extracted verbatim from LuminaRoot's composition tree.
 *
 * The grid-open fraction is read inside the `graphicsLayer` (draw phase) via
 * [gridOpenFractionProvider] so a grid drag never invalidates this full-screen Box per pointer
 * sample — the provider blends [GridGestureState.gridDragProgress] / `gridProgressState` at the
 * call site (invariant #4).
 */
@Composable
internal fun RoundedCornerViewportMask(
    cornerMaskDp: Dp,
    gridOpenFractionProvider: () -> Float,
) {
    // Always black, never the theme background: these cut-outs sit under the device's own physical
    // rounded screen corners, so in light mode a white background bled out past the bezel radius and
    // looked broken. Black blends into the bezel in both light and dark.
    val maskColor = Color.Black
    val maskShape = remember(cornerMaskDp) { SquircleShape(cornerMaskDp) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = (1f - gridOpenFractionProvider()).coerceIn(0f, 1f)
            }
            .drawWithCache {
                // SquircleShape yields Outline.Rectangle when the radius/size
                // resolves to 0 (e.g. a zero-size first draw); only a Generic
                // outline has corners to mask.
                val inner = (maskShape.createOutline(size, layoutDirection, this)
                    as? Outline.Generic)?.path
                val mask = if (inner != null) {
                    val full = Path().apply {
                        addRect(Rect(0f, 0f, size.width, size.height))
                    }
                    Path.combine(PathOperation.Difference, full, inner)
                } else {
                    null
                }
                onDrawBehind { mask?.let { drawPath(it, color = maskColor) } }
            },
    )
}
