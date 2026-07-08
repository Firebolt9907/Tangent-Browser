package com.firebolt9907.luminabrowser.ui.chrome

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.firebolt9907.luminabrowser.ui.shape.SquircleShape
import kotlin.math.abs

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
    content: @Composable () -> Unit,
) {
    val currentOnStart by rememberUpdatedState(onDragStart)
    val currentOnDelta by rememberUpdatedState(onDragDelta)
    val currentOnEnd by rememberUpdatedState(onDragEnd)

    val sizing = when {
        width != null -> Modifier.width(width)
        widthFraction != null -> Modifier.fillMaxWidth(widthFraction)
        else -> Modifier.fillMaxWidth()
    }.height(height)

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
                        // LuminaRoot cleans up offsetX and snapshot state —
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

    Surface(
        modifier = modifier.then(sizing).then(gestureModifier).then(clickModifier),
        shape = SquircleShape(cornerRadius, exponent = exponent),
        color = containerColor ?: MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 6.dp,
    ) {
        content()
    }
}
