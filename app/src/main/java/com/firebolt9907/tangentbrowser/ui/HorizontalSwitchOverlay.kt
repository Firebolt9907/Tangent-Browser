package com.firebolt9907.tangentbrowser.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import kotlin.math.abs

/** Ease the new page's arrival continuously, including the existing commit/cancel spring. */
internal fun newTabRevealProgress(offsetX: Float, pageStride: Float): Float {
    if (pageStride <= 0f) return 0f
    val p = (-offsetX / pageStride).coerceIn(0f, 1f)
    return p * p * (3f - 2f * p)
}

/**
 * The horizontal tab-switch drag overlay (invariant #5) — the sliding leaving/incoming page panes
 * drawn while a finger drags between adjacent tabs (and while the release commit-spring runs).
 * Extracted verbatim from TangentRoot's composition tree.
 *
 * The per-frame [offsetX][offsetXProvider] read stays in the `graphicsLayer` / `drawWithContent`
 * draw phase (via the provider lambda, the same idiom as TabGridOverlay's `carouselShift`), so a
 * swipe never recomposes this subtree. Everything else (which snapshot to show, top pads, new-tab
 * pane visibility) is a composition-phase input passed as a plain parameter. Rendered only on the
 * `overlayActive && !gridEngaged` path (invariant #5 — never simultaneously with the grid overlay).
 *
 * [depthSwipe] enables the wide-screen receding/dimming card-stack; portrait keeps the flat slide.
 */
@Composable
internal fun HorizontalSwitchOverlay(
    offsetXProvider: () -> Float,
    contentSize: IntSize,
    tabSwitchGapPx: Float,
    depthSwipe: Boolean,
    adjacentSnapshot: Bitmap?,
    adjacentUrl: String?,
    showNewTabPane: Boolean,
    currentUrl: String?,
    movingCurrentSnapshot: Bitmap?,
    switchPaneShape: Shape,
    topPadDpFor: (String?) -> Dp,
) {
    val width = contentSize.width.toFloat()
    val pageStride = width + tabSwitchGapPx
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    )
    adjacentSnapshot?.let { bitmap ->
        val adjacentPad = topPadDpFor(adjacentUrl)
        SnapshotImage(
            bitmap = bitmap,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = adjacentPad)
                .graphicsLayer {
                    val offsetX = offsetXProvider()
                    val side = if (offsetX > 0f) 1f else -1f
                    translationX = offsetX - side * pageStride
                    if (depthSwipe) {
                        // Arrival progress: 0 fully off-screen, 1 centred.
                        val f = (abs(offsetX) / pageStride).coerceIn(0f, 1f)
                        val s = DeckSwipeMinScale + (1f - DeckSwipeMinScale) * f
                        scaleX = s
                        scaleY = s
                    }
                }
                .then(
                    if (depthSwipe) Modifier.drawWithContent {
                        drawContent()
                        val f = (abs(offsetXProvider()) / pageStride).coerceIn(0f, 1f)
                        drawRect(Color.Black, alpha = DeckSwipeMaxDim * (1f - f))
                    } else Modifier,
                )
                .clip(switchPaneShape),
        )
    }
    if (showNewTabPane) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val offset = offsetXProvider()
                    val reveal = newTabRevealProgress(offset, pageStride)
                    translationX = offset + pageStride
                    transformOrigin = TransformOrigin(0f, 0.5f)
                    scaleX = 0.84f + 0.16f * reveal
                    scaleY = scaleX
                    alpha = 0.15f + 0.85f * reveal
                    shadowElevation = 18f * reveal
                }
                .clip(switchPaneShape)
                .background(MaterialTheme.colorScheme.surface),
        )
    }
    val currentPad = topPadDpFor(currentUrl)
    if (movingCurrentSnapshot == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = currentPad)
                .graphicsLayer { translationX = offsetXProvider() }
                .clip(switchPaneShape)
                .background(MaterialTheme.colorScheme.background),
        )
    } else {
        SnapshotImage(
            bitmap = movingCurrentSnapshot,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = currentPad)
                .graphicsLayer {
                    val offsetX = offsetXProvider()
                    translationX = offsetX
                    if (depthSwipe) {
                        // Departure progress: 0 centred, 1 fully swiped away.
                        val f = (abs(offsetX) / pageStride).coerceIn(0f, 1f)
                        val s = 1f - (1f - DeckSwipeMinScale) * f
                        scaleX = s
                        scaleY = s
                    }
                }
                .then(
                    if (depthSwipe) Modifier.drawWithContent {
                        drawContent()
                        val f = (abs(offsetXProvider()) / pageStride).coerceIn(0f, 1f)
                        drawRect(Color.Black, alpha = DeckSwipeMaxDim * f)
                    } else Modifier,
                )
                .clip(switchPaneShape),
        )
    }
}
