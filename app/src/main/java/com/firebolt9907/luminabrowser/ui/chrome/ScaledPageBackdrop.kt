package com.firebolt9907.luminabrowser.ui.chrome

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.firebolt9907.luminabrowser.ui.content.LocalSiteTint
import com.firebolt9907.luminabrowser.ui.content.siteTintOverlay

/**
 * iOS-style "card behind a sheet" backdrop: an opaque cover (so the live engine never shows through)
 * with [snapshot] scaled down over it as a foregrounded sheet rises, leaving the page's top peeking
 * below the status bar.
 *
 * **Efficiency:** [fraction] (1 = fully scaled down / sheet open, 0 = full-size page) is read lazily
 * *inside* the graphics layers, so animating or dragging it invalidates only the render layer — no
 * recomposition per frame, and the bitmap is rasterized once into a layer and merely transformed.
 * Pass a lambda that reads your animating state (`{ anim.value }`), not a pre-read `Float`.
 */
@Composable
fun ScaledPageBackdrop(
    snapshot: Bitmap?,
    fraction: () -> Float,
    statusBarTopPad: Dp,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
) {
    val density = LocalDensity.current
    Box(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        if (snapshot != null) {
            val image = remember(snapshot) { snapshot.asImageBitmap() }
            val topShiftPx = with(density) { (statusBarTopPad + 6.dp).toPx() }
            val cornerPx = with(density) { 28.dp.toPx() }

            var imageModifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val f = fraction()
                    val scale = lerp(1f, 0.93f, f)
                    scaleX = scale
                    scaleY = scale
                    translationY = topShiftPx * f
                    transformOrigin = TransformOrigin(0.5f, 0f)
                    clip = true
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(lerp(0f, cornerPx, f))
                }
            if (onTap != null) {
                imageModifier = imageModifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onTap,
                )
            }
            // Match the live page's system-tint boost on this scaled snapshot (no-op when off).
            imageModifier = imageModifier.siteTintOverlay(LocalSiteTint.current)
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = imageModifier,
            )
            // Subtle dim over the page card so the foreground sheet reads as the active surface.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val f = fraction()
                        val scale = lerp(1f, 0.93f, f)
                        scaleX = scale
                        scaleY = scale
                        translationY = topShiftPx * f
                        transformOrigin = TransformOrigin(0.5f, 0f)
                        alpha = 0.18f * f
                    }
                    .background(Color.Black),
            )
        }
    }
}
