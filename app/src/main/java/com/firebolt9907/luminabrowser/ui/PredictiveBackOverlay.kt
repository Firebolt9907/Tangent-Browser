package com.firebolt9907.luminabrowser.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

/**
 * The predictive-back gesture overlay (invariant #5/#7). While a back-swipe is in progress it
 * layers the previous-history page ([primarySnapshot], revealed with a parallax/dim curve) under
 * the current page ([gestureCurrentSnapshot], slid off to the right with a shadow). Extracted
 * verbatim from LuminaRoot's composition tree.
 *
 * The per-frame progress ([predictiveBackOffsetPxProvider]) is read inside the graphicsLayer /
 * drawBehind draw phase (provider idiom) so a back-swipe never recomposes this subtree.
 * [contentWidth] and [easing] are stable composition-phase inputs.
 */
@Composable
internal fun PredictiveBackOverlay(
    predictiveBackOffsetPxProvider: () -> Float,
    contentWidth: Float,
    easing: Easing,
    primarySnapshot: Bitmap?,
    gestureCurrentSnapshot: Bitmap?,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    )
    primarySnapshot?.let { bitmap ->
        SnapshotImage(
            bitmap = bitmap,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val reveal = easing.transform(
                        if (contentWidth > 0f) {
                            (predictiveBackOffsetPxProvider() / contentWidth).coerceIn(0f, 1f)
                        } else 0f
                    )
                    translationX =
                        if (contentWidth > 0f) -contentWidth * 0.14f * (1f - reveal) else 0f
                },
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    val reveal = easing.transform(
                        if (contentWidth > 0f) {
                            (predictiveBackOffsetPxProvider() / contentWidth).coerceIn(0f, 1f)
                        } else 0f
                    )
                    drawRect(Color.Black, alpha = 0.16f * (1f - reveal))
                },
        )
    }
    gestureCurrentSnapshot?.let { bitmap ->
        SnapshotImage(
            bitmap = bitmap,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    translationX = predictiveBackOffsetPxProvider()
                    shadowElevation = 18f * (
                        if (contentWidth > 0f) {
                            (predictiveBackOffsetPxProvider() / contentWidth).coerceIn(0f, 1f)
                        } else 0f
                    )
                    clip = true
                },
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = predictiveBackOffsetPxProvider() - 10f }
                .drawBehind {
                    val p = if (contentWidth > 0f) {
                        (predictiveBackOffsetPxProvider() / contentWidth).coerceIn(0f, 1f)
                    } else 0f
                    drawRect(Color.Black, alpha = 0.10f * p)
                },
        )
    }
}
