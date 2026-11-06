package com.firebolt9907.tangentbrowser.ui.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs
import kotlin.math.min

/** Observe at Initial so a two-finger pinch wins over text selection, scrolling and gallery paging.
 * Single-finger gestures remain available to those children until a pinch has claimed the stream. */
@Composable
internal fun Modifier.readerPinch(
    key: Any?,
    onZoom: (centroid: Offset, pan: Offset, factor: Float) -> Unit,
    onEnd: () -> Unit = {},
    canPan: () -> Boolean = { false },
): Modifier {
    val currentOnZoom by rememberUpdatedState(onZoom)
    val currentOnEnd by rememberUpdatedState(onEnd)
    val currentCanPan by rememberUpdatedState(canPan)
    return pointerInput(key) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var claimed = false
            var accumulatedZoom = 1f
            var accumulatedPan = Offset.Zero
            try {
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val multiTouch = event.changes.count { it.pressed } >= 2
                    val zoom = event.calculateZoom()
                    accumulatedZoom *= zoom
                    accumulatedPan += event.calculatePan()
                    if (multiTouch && abs(accumulatedZoom - 1f) > 0.025f) claimed = true
                    if (currentCanPan() && accumulatedPan.getDistance() > viewConfiguration.touchSlop) claimed = true
                    if (claimed) {
                        val centroid = event.calculateCentroid(useCurrent = false)
                        // The all-up event has no centroid; never feed NaN into image transforms.
                        if (centroid.x.isFinite() && centroid.y.isFinite()) {
                            currentOnZoom(centroid, event.calculatePan(), zoom)
                        }
                        event.changes.forEach { it.consume() }
                    }
                } while (event.changes.any { it.pressed })
            } finally {
                if (claimed) currentOnEnd()
            }
        }
    }
}

/** Bounds use the fitted image, not the viewport: a wide photo must not pan into blank space. */
internal fun readerImagePanBounds(viewport: Size, intrinsic: Size, zoom: Float): Offset {
    if (viewport.width <= 0f || viewport.height <= 0f || intrinsic.width <= 0f || intrinsic.height <= 0f) {
        return Offset.Zero
    }
    val fit = min(viewport.width / intrinsic.width, viewport.height / intrinsic.height)
    return Offset(
        ((intrinsic.width * fit * zoom - viewport.width) / 2f).coerceAtLeast(0f),
        ((intrinsic.height * fit * zoom - viewport.height) / 2f).coerceAtLeast(0f),
    )
}

internal fun clampReaderImagePan(pan: Offset, bounds: Offset) = Offset(
    pan.x.coerceIn(-bounds.x, bounds.x),
    pan.y.coerceIn(-bounds.y, bounds.y),
)

internal data class ReaderGalleryImage(
    val src: String,
    val caption: String? = null,
    val lightBackground: Boolean = false,
)

internal fun readerGalleryImages(article: ReaderArticle): List<ReaderGalleryImage> =
    article.blocks.flatMap { block ->
        when (block) {
            is ReaderBlock.Image -> listOf(ReaderGalleryImage(block.src, block.caption))
            is ReaderBlock.Figure -> listOf(ReaderGalleryImage(block.src, block.caption, lightBackground = true))
            is ReaderBlock.SocialPost -> block.media.map { ReaderGalleryImage(it.src) }
            is ReaderBlock.SocialComment -> block.media.map { ReaderGalleryImage(it.src) }
            else -> emptyList()
        }
    }.distinctBy { it.src }
