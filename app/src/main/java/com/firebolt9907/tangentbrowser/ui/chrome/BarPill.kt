package com.firebolt9907.tangentbrowser.ui.chrome

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.RoundedCornerShape
import com.firebolt9907.tangentbrowser.ui.shape.SquircleShape
import com.firebolt9907.tangentbrowser.ui.shape.squirclePath
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.ui.util.lerp as lerpFloat

/**
 * Describes the pill's scroll-collapse morph as two endpoint states plus a live [progress] provider
 * (0 = fully expanded, 1 = fully compact). When a [PillMorph] is supplied to [BarPill], every
 * per-frame quantity — size, corner radius, superellipse exponent, shadow — is interpolated inside
 * the layout/draw phases by reading [progress] there, NOT during composition. That is what lets the
 * collapse spring drive the pill continuously (real-time, every intermediate value) without
 * recomposing the pill/SafariBar/AnimatedContent subtree on every frame.
 */
internal class PillMorph(
    val progress: () -> Float,
    val expandedWidth: Dp,
    val compactWidth: Dp,
    val expandedHeight: Dp,
    val compactHeight: Dp,
    val expandedCorner: Dp,
    val compactCorner: Dp,
    val expandedExponent: Float,
    val compactExponent: Float,
    val expandedElevation: Dp,
    val compactElevation: Dp,
)

@Composable
internal fun BarPill(
    height: Dp,
    cornerRadius: Dp,
    onDragDelta: (Offset) -> Unit,
    onDragEnd: (totalDelta: Offset, velocity: Offset) -> Unit,
    modifier: Modifier = Modifier,
    onDragStart: () -> Unit = {},
    widthFraction: Float? = null,
    width: Dp? = null,
    interactive: Boolean = true,
    containerColor: Color? = null,
    onClick: (() -> Unit)? = null,
    exponent: Float = 3.0f,
    shadowElevation: Dp = 0.dp,
    // When supplied, the pill interpolates its size/corner/exponent/shadow from this descriptor in
    // the layout/draw phases (no per-frame recomposition), overriding the static `width`/`height`/
    // `cornerRadius`/`exponent`/`shadowElevation` above. See [PillMorph].
    morph: PillMorph? = null,
    content: @Composable () -> Unit,
) {
    val currentOnStart by rememberUpdatedState(onDragStart)
    val currentOnDelta by rememberUpdatedState(onDragDelta)
    val currentOnEnd by rememberUpdatedState(onDragEnd)

    val gestureModifier = if (interactive) {
        // Use Initial pass (parent-before-child) so move events are seen and consumed here before
        // child composables (URL clickable, IconButtons) process them. This is exactly what Compose's
        // built-in `draggable` does and is why `draggable(startDragImmediately=false)` worked:
        // the drag tracking ran at Initial pass, consuming moves once slop was exceeded so child
        // tap handlers couldn't mistake a swipe for a tap. Main pass (child-before-parent) would
        // cause child clickables to process — and potentially consume — move events before this
        // loop ever sees them, which broke finger tracking.
        Modifier.pointerInput(Unit) {
            val touchSlop = viewConfiguration.touchSlop
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val pointerId = down.id
                var totalDx = 0f
                var totalDy = 0f
                var started = false
                val velocityTracker = VelocityTracker()
                velocityTracker.addPointerInputChange(down)
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == pointerId }
                    if (change == null) {
                        // Tracked pointer lost (multitouch / palm rejection). Fire onEnd so
                        // TangentRoot cleans up offsetX and snapshot state —
                        // otherwise the gesture is left half-updated ("pill dissociating").
                        if (started) {
                            currentOnEnd(Offset(totalDx, totalDy), Offset.Zero)
                        }
                        break
                    }
                    if (!change.pressed) {
                        if (started) {
                            val v = velocityTracker.calculateVelocity()
                            currentOnEnd(Offset(totalDx, totalDy), Offset(v.x, v.y))
                        }
                        // Always consume the up so children don't fire a click at the end of a drag.
                        if (started) change.consume()
                        break
                    }
                    val delta = change.positionChange()
                    totalDx += delta.x
                    totalDy += delta.y
                    velocityTracker.addPointerInputChange(change)
                    if (!started) {
                        if (abs(totalDx) > touchSlop || abs(totalDy) > touchSlop) {
                            started = true
                            currentOnStart()
                            currentOnDelta(Offset(totalDx, totalDy))
                            change.consume()
                        }
                        // Do NOT consume pre-slop moves — lets child clickables still receive
                        // hover/press-state updates so the ripple feels responsive on a tap.
                    } else {
                        currentOnDelta(delta)
                        change.consume()
                    }
                }
            }
        }
    } else {
        Modifier
    }

    val clickModifier = if (onClick != null) {
        Modifier.clickable(onClick = onClick)
    } else {
        Modifier
    }

    if (morph != null) {
        // ---- Deferred morph path ----
        // Everything that changes with collapse progress is read inside the layout/draw lambdas
        // below, so a spring frame invalidates only layout + draw (never composition).
        val pillColor = containerColor ?: MaterialTheme.colorScheme.surfaceContainerHighest
        // A stable Shape whose outline reads the LIVE progress at outline-time (layout/draw phase).
        // The graphicsLayer recomputes this outline whenever the layer's size changes — which happens
        // every frame because the .layout below reports a progress-driven size — so the squircle
        // corner/exponent stay in lock-step with the size without a single recomposition.
        val morphShape = remember(morph) {
            object : Shape {
                override fun createOutline(
                    size: Size,
                    layoutDirection: LayoutDirection,
                    density: Density,
                ): Outline {
                    val p = morph.progress().coerceIn(0f, 1f)
                    val exp = lerpFloat(morph.expandedExponent, morph.compactExponent, p)
                    val cornerPx = with(density) { lerp(morph.expandedCorner, morph.compactCorner, p).toPx() }
                    val w = size.width
                    val h = size.height
                    val r = cornerPx.coerceAtMost(min(w, h) / 2f)
                    if (r <= 0f) return Outline.Rectangle(Rect(0f, 0f, w, h))
                    return Outline.Generic(squirclePath(w, h, r, exp))
                }
            }
        }
        androidx.compose.foundation.layout.Box(
            modifier = modifier
                .layout { measurable, constraints ->
                    val p = morph.progress().coerceIn(0f, 1f)
                    val w = lerp(morph.expandedWidth, morph.compactWidth, p).roundToPx()
                    val h = lerp(morph.expandedHeight, morph.compactHeight, p).roundToPx()
                    val placeable = measurable.measure(
                        constraints.copy(minWidth = w, maxWidth = w, minHeight = h, maxHeight = h),
                    )
                    layout(w, h) { placeable.place(0, 0) }
                }
                .graphicsLayer {
                    val p = morph.progress().coerceIn(0f, 1f)
                    // `this.` disambiguates from the outer `shadowElevation: Dp` parameter.
                    this.shadowElevation = lerp(morph.expandedElevation, morph.compactElevation, p).toPx()
                    shape = morphShape
                    clip = true
                }
                .then(gestureModifier)
                .then(clickModifier),
            contentAlignment = Alignment.Center,
        ) {
            // Rectangular Surface (no shape of its own) supplies the tonal-elevated fill exactly as
            // the static path does; the parent graphicsLayer clips it into the squircle.
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = pillColor,
                tonalElevation = 6.dp,
            ) {
                content()
            }
        }
        return
    }

    // ---- Static path (BarCompact and any non-morphing caller) ----
    val sizing = when {
        width != null -> Modifier.width(width)
        widthFraction != null -> Modifier.fillMaxWidth(widthFraction)
        else -> Modifier.fillMaxWidth()
    }.height(height)

    val shadowModifier = Modifier.shadow(
        elevation = shadowElevation,
        shape = RoundedCornerShape(cornerRadius),
        clip = false
    )

    Surface(
        modifier = modifier.then(sizing).then(shadowModifier).then(gestureModifier).then(clickModifier),
        shape = SquircleShape(cornerRadius, exponent = exponent),
        color = containerColor ?: MaterialTheme.colorScheme.surfaceContainerHighest,
        tonalElevation = 6.dp,
    ) {
        content()
    }
}
