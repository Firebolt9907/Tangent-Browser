package com.firebolt9907.tangentbrowser.ui.theme

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp

/**
 * **Material 3 Expressive** shape-morph-on-press.
 *
 * Expressive treats a container's *shape* as a state channel, not a fixed decoration: pressing a
 * surface pulls its corners in, and releasing springs them back. It is the signature Expressive
 * interaction, and the reason is legibility rather than decoration — a ripple washes out over a
 * large container and is easy to miss under a fingertip, whereas the whole outline reacting is
 * unmissable at any size.
 *
 * The corner shrinks on press (round → squarer), matching the direction M3 Expressive specifies for
 * buttons, and travels on [ExpressiveMotion.fastSpatialSpec] — a shape change is spatial, and a
 * tappable control is small, so `fast` is the right tier. The slight under-damped overshoot on
 * release is what makes it read as springy rather than as a linear resize.
 *
 * material3 1.4.0 has no shape-morph API of its own (no `ToggleButton`, no pressed-shape slot on
 * `Surface`/`Button` — see the loop notes on what Expressive leaves `internal` in this release), so
 * this is the app-side equivalent.
 *
 * Hoist the same [interactionSource] into both this call and the clickable it decorates, or the
 * shape will never see the press:
 *
 * ```
 * val interactionSource = remember { MutableInteractionSource() }
 * Surface(
 *     onClick = onClick,
 *     shape = pressMorphShape(interactionSource, resting = 16.dp),
 *     interactionSource = interactionSource,
 * ) { … }
 * ```
 *
 * @param resting corner radius at rest.
 * @param pressed corner radius while held. Defaults to half [resting], which keeps the morph
 *   proportional across call sites — a subtle card and a large one both compress by the same ratio.
 */
@Composable
fun pressMorphShape(
    interactionSource: InteractionSource,
    resting: Dp,
    pressed: Dp = resting / 2,
): Shape {
    val isPressed by interactionSource.collectIsPressedAsState()
    val corner by animateDpAsState(
        targetValue = if (isPressed) pressed else resting,
        animationSpec = ExpressiveMotion.fastSpatialSpec(),
        label = "pressMorphCorner",
    )
    return RoundedCornerShape(corner)
}
