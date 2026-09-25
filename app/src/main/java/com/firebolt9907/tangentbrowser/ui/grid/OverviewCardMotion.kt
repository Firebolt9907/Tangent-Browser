package com.firebolt9907.tangentbrowser.ui.grid

import androidx.compose.ui.util.lerp

/**
 * Extra release trajectory reserved for a velocity-committed upward flick. The endpoints remain
 * the finger pose and the deck slot; [heightPx] and [scaleDip] only bend the middle of that path,
 * so an interrupted/re-targeted release can never pop at either end.
 */
data class FlickReleaseArc(
    val heightPx: Float,
    val scaleDip: Float,
)

/**
 * Temporary kill-switch: the velocity-commit arc wiring (trigger, intensity, [GridFlickArc]
 * storage, param threading) stays live, but this zeroes the visual bend so the release renders
 * exactly like the pre-arc path. Flip to `true` to restore the effect; a smart model should then
 * tune the arc/spread in `onPillDragEnd` (TangentRoot) and re-verify `OverviewCardMotionTest`.
 */
internal const val FLICK_ARC_BEND_ENABLED: Boolean = false

/** Shared by the snapshot and its caption so their positions cannot diverge during settling. */
internal fun overviewCardPose(
    slot: DeckSlot,
    browseX: Float,
    progress: Float,
    releaseProgress: Float,
    commitFromProgress: Float,
    cancelFromProgress: Float,
    releasePosition: Triple<Float, Float, Float>?,
    flickReleaseArc: FlickReleaseArc? = null,
): DeckSlot {
    val p = progress.coerceIn(0f, 1f)
    val from = releasePosition ?: Triple(browseX, 0f, 1f)
    return when {
        commitFromProgress > 0f -> {
            // Overview progress can already be 1 after a long drag. The independent 0→1
            // release spring still has its full range, regardless of the finger's distance.
            val t = releaseProgress.coerceIn(0f, 1f)
            // A quick upward flick otherwise has almost no visible throw: it can commit on
            // velocity before the finger has travelled far. Bend that release into a high arc and
            // briefly shrink below its destination size, then let it curve back and grow into the
            // deck slot. `4t(1-t)` is zero at both endpoints, preserving seamless handoff.
            val arcT = 4f * t * (1f - t)
            val arc = if (FLICK_ARC_BEND_ENABLED) flickReleaseArc else null
            DeckSlot(
                lerp(from.first, slot.tx, t),
                lerp(from.second, slot.ty, t) - (arc?.heightPx ?: 0f) * arcT,
                lerp(from.third, slot.scale, t) - (arc?.scaleDip ?: 0f) * arcT,
                lerp(1f, slot.alpha, t),
            )
        }
        cancelFromProgress > 0f -> {
            val t = (1f - p / cancelFromProgress).coerceIn(0f, 1f)
            DeckSlot(lerp(from.first, browseX, t), lerp(from.second, 0f, t),
                lerp(from.third, 1f, t), 1f)
        }
        else -> DeckSlot(lerp(browseX, slot.tx, releaseProgress.coerceIn(0f, 1f)),
            lerp(0f, slot.ty, p), lerp(1f, slot.scale, p), lerp(1f, slot.alpha, p))
    }
}
