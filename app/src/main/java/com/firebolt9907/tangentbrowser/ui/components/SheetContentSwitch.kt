package com.firebolt9907.tangentbrowser.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.firebolt9907.tangentbrowser.ui.TangentMotion
import com.firebolt9907.tangentbrowser.ui.theme.EaseMotion

/** The three states every library sheet moves between while it loads its rows. */
enum class SheetContentPhase { Loading, Empty, Content }

/**
 * Cross-fades a library sheet between its spinner, its empty state, and its list.
 *
 * Every one of these sheets used to write the triad as a bare `if (loading) … else if (empty) …
 * else …`, so the spinner and the rows swapped in a **single frame** — and because the three
 * branches are wildly different heights, the whole sheet jumped at that same instant. The spinner
 * was animating right up to the frame it disappeared on, which makes the cut especially loud: a
 * moving thing stops existing.
 *
 * The outgoing branch fades away quickly while incoming content eases into view,
 * plus a size animation, because the
 * height change is most of what the eye actually catches here.
 *
 * The size curve is [TangentMotion.RevealExpand]/[TangentMotion.RevealCollapse], not an expressive
 * spatial: every spatial tier is 0.8-damped, and overshoot on a container's *size* grows it past its
 * content and flashes a gap (see those specs' own note). `clip = false` because the branches fade
 * rather than slide — clipping them to the mid-animation box would chop rows off instead of
 * dissolving them.
 */
@Composable
fun SheetContentSwitch(
    phase: SheetContentPhase,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopCenter,
    content: @Composable AnimatedContentScope.(SheetContentPhase) -> Unit,
) {
    AnimatedContent(
        targetState = phase,
        modifier = modifier,
        contentAlignment = contentAlignment,
        transitionSpec = {
            (
                fadeIn(EaseMotion.enterSpec(240)) togetherWith
                    fadeOut(EaseMotion.exitSpec(120))
                ).using(
                SizeTransform(clip = false) { _, _ ->
                    // Leaving the spinner is the sheet filling in; leaving anything else is it
                    // giving space back. Arriving is deliberate, leaving gets out of the way.
                    if (targetState == SheetContentPhase.Loading) {
                        TangentMotion.RevealCollapse
                    } else {
                        TangentMotion.RevealExpand
                    }
                },
            )
        },
        label = "sheetContent",
        content = content,
    )
}
