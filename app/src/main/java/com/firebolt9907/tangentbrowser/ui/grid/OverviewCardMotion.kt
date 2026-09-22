package com.firebolt9907.tangentbrowser.ui.grid

import androidx.compose.ui.util.lerp

/** Shared by the snapshot and its caption so their positions cannot diverge during settling. */
internal fun overviewCardPose(
    slot: DeckSlot,
    browseX: Float,
    progress: Float,
    releaseProgress: Float,
    commitFromProgress: Float,
    cancelFromProgress: Float,
    releasePosition: Triple<Float, Float, Float>?,
): DeckSlot {
    val p = progress.coerceIn(0f, 1f)
    val from = releasePosition ?: Triple(browseX, 0f, 1f)
    return when {
        commitFromProgress > 0f -> {
            // Overview progress can already be 1 after a long drag. The independent 0→1
            // release spring still has its full range, regardless of the finger's distance.
            val t = releaseProgress.coerceIn(0f, 1f)
            DeckSlot(lerp(from.first, slot.tx, t), lerp(from.second, slot.ty, t),
                lerp(from.third, slot.scale, t), lerp(1f, slot.alpha, t))
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
