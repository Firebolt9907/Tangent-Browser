package com.firebolt9907.tangentbrowser.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.firebolt9907.tangentbrowser.ui.TangentMotion
import com.firebolt9907.tangentbrowser.ui.theme.ExpressiveMotion

/** Which way the indicator's slot opens up in its parent. */
enum class BusyIndicatorAxis { Horizontal, Vertical }

/**
 * A "we're working on it" spinner that arrives and leaves instead of blinking.
 *
 * Every call site used to be a bare `if (state.isBusy) { CircularProgressIndicator(…) }`, which is a
 * hard cut twice over: the spinner itself pops, and — because it is laid out *next to* something
 * that measures against it — the neighbour snaps in the same frame. In `SheetHeader`'s trailing slot
 * the title carries `weight(1f)`, so the title box loses (then regains) the spinner's width in one
 * frame and can re-ellipsize; inside a button the label jumps sideways by the spinner plus its gap.
 * A spinner is also the worst kind of thing to cut: it is visibly animating right up to the frame it
 * stops existing on.
 *
 * So the size change is part of the motion (flat-damped [TangentMotion.RevealExpand]/
 * [TangentMotion.RevealCollapse] — a spatial tier's overshoot on an `IntSize` would push the
 * neighbour past its resting place), and the spinner scales/fades within it so it reads as one
 * object appearing rather than a wipe. Arriving takes the default effects spring, leaving the fast
 * one: the work starting is worth announcing, the work finishing should get out of the way.
 *
 * [leadingSpace]/[trailingSpace] are the gap to whatever sits next to it, and belong *inside* the
 * animation — a spacer left outside would keep its width while the spinner collapsed.
 */
@Composable
fun BusyIndicator(
    visible: Boolean,
    modifier: Modifier = Modifier,
    axis: BusyIndicatorAxis = BusyIndicatorAxis.Horizontal,
    size: Dp = 22.dp,
    strokeWidth: Dp = 2.dp,
    color: Color = ProgressIndicatorDefaults.circularColor,
    leadingSpace: Dp = 0.dp,
    trailingSpace: Dp = 0.dp,
) {
    val grow = if (axis == BusyIndicatorAxis.Horizontal) {
        expandHorizontally(TangentMotion.RevealExpand, clip = false)
    } else {
        expandVertically(TangentMotion.RevealExpand, clip = false)
    }
    val shrink = if (axis == BusyIndicatorAxis.Horizontal) {
        shrinkHorizontally(TangentMotion.RevealCollapse, clip = false)
    } else {
        shrinkVertically(TangentMotion.RevealCollapse, clip = false)
    }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(ExpressiveMotion.defaultEffectsSpec()) +
            scaleIn(ExpressiveMotion.defaultEffectsSpec(), initialScale = 0.7f) +
            grow,
        exit = fadeOut(ExpressiveMotion.fastEffectsSpec()) +
            scaleOut(ExpressiveMotion.fastEffectsSpec(), targetScale = 0.7f) +
            shrink,
    ) {
        val spinner: @Composable () -> Unit = {
            CircularProgressIndicator(
                modifier = Modifier.size(size),
                strokeWidth = strokeWidth,
                color = color,
            )
        }
        if (axis == BusyIndicatorAxis.Horizontal) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (leadingSpace > 0.dp) Spacer(Modifier.width(leadingSpace))
                spinner()
                if (trailingSpace > 0.dp) Spacer(Modifier.width(trailingSpace))
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (leadingSpace > 0.dp) Spacer(Modifier.height(leadingSpace))
                spinner()
                if (trailingSpace > 0.dp) Spacer(Modifier.height(trailingSpace))
            }
        }
    }
}
