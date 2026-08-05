package com.firebolt9907.tangentbrowser.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.firebolt9907.tangentbrowser.ui.theme.ExpressiveMotion

/**
 * One page-indicator pill: the stretching dot under a horizontal pager.
 *
 * A dot has **two visual channels**, and the recurring defect is that only one of them moves — the
 * pill grows smoothly while its colour snaps in a single frame, or (in the reader's image gallery)
 * neither moves at all and the whole indicator jumps a page-swipe ahead of the pager it is meant to
 * be reporting on. Both halves have to be animated, and they have to finish together.
 *
 * The stretch takes the **fast spatial** tier — a dot is a small control answering a swipe — and the
 * tint takes the **default effects** tier, which is the pairing that lands them at the same moment.
 *
 * Colours are parameters rather than read from the theme because the two call sites sit on different
 * surfaces: the spaces carousel draws on the sheet background, the reader gallery draws white over a
 * photograph.
 */
@Composable
fun PageIndicatorDot(
    selected: Boolean,
    activeColor: Color,
    inactiveColor: Color,
    modifier: Modifier = Modifier,
    activeWidth: Dp = 18.dp,
    inactiveWidth: Dp = 6.dp,
    height: Dp = 6.dp,
    spacing: Dp = 3.dp,
) {
    val width by animateDpAsState(
        targetValue = if (selected) activeWidth else inactiveWidth,
        animationSpec = ExpressiveMotion.fastSpatialSpec(),
        label = "dotWidth",
    )
    val color by animateColorAsState(
        targetValue = if (selected) activeColor else inactiveColor,
        animationSpec = ExpressiveMotion.defaultEffectsSpec(),
        label = "dotColor",
    )
    Box(
        modifier = modifier
            .padding(horizontal = spacing)
            .height(height)
            .width(width)
            .clip(CircleShape)
            .background(color),
    )
}
