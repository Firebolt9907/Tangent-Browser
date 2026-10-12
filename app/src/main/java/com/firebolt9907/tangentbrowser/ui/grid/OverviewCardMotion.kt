package com.firebolt9907.tangentbrowser.ui.grid

import androidx.compose.animation.core.Animatable
import androidx.compose.ui.util.lerp

/** Release velocities in screen units; progress is elapsed time, never spring progress. */
data class FlickReleaseArc(
    val upwardVelocityPxS: Float,
    val scaleVelocityPerSec: Float,
    val containerWidth: Float,
    val containerHeight: Float,
    val backgroundScaleVelocityPerSec: Float = scaleVelocityPerSec,
    val carouselScaleAtRelease: Float? = null,
) {
    // Shared clock keeps the foreground and background cards settling together.
    val progress = Animatable(0f)

    companion object {
        const val DurationMillis = 350
    }
}

/** Early travel with a long, flat landing; position, velocity and acceleration settle together. */
private fun landingBlend(t: Float): Float = t * t * (10f + t * (-20f + t * (15f - 4f * t)))

/** Carry measured release momentum, with zero velocity and acceleration at the destination. */
private fun coastTo(from: Float, to: Float, velocity: Float, t: Float, influence: Float): Float {
    val smooth = landingBlend(t)
    // Preserve every card's finger-tracked release velocity, then progressively damp the
    // background throw. A constant velocity multiplier would introduce a release discontinuity.
    // Do not boost the carry mid-flight: even with a matching endpoint derivative, that adds
    // energy in the first visible frames and makes a quick release feel like another upward kick.
    val remaining = 1f - t
    val coast = t * remaining * remaining * remaining * remaining * lerp(1f, influence, smooth)
    return lerp(from, to, smooth) + velocity * (FlickReleaseArc.DurationMillis / 1000f) * coast
}

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
    inertiaInfluence: Float = 1f,
    isBackgroundCard: Boolean = false,
): DeckSlot {
    val p = progress.coerceIn(0f, 1f)
    val from = releasePosition ?: Triple(browseX, 0f, 1f)
    return when {
        commitFromProgress > 0f -> {
            // Overview progress can already be 1 after a long drag. The independent 0→1
            // release spring still has its full range, regardless of the finger's distance.
            val t = releaseProgress.coerceIn(0f, 1f)
            flickReleaseArc?.let { arc ->
                // Keep elapsed time linear: ease position and momentum separately so the
                // stronger landing curve cannot multiply the finger's release velocity.
                // Track the bottom edge, just as the drag does. Shrinking around the top-left
                // would otherwise move the card sideways/downward the instant the finger lifts.
                val scaleVelocity = if (isBackgroundCard) arc.backgroundScaleVelocityPerSec else arc.scaleVelocityPerSec
                val scale = coastTo(from.third, slot.scale, scaleVelocity, t, inertiaInfluence)
                val bottom = coastTo(
                    from.second + arc.containerHeight * from.third,
                    slot.ty + arc.containerHeight * slot.scale,
                    -arc.upwardVelocityPxS, t, inertiaInfluence,
                )
                val center = coastTo(
                    from.first + arc.containerWidth * from.third / 2f,
                    slot.tx + arc.containerWidth * slot.scale / 2f,
                    (from.first + arc.containerWidth * from.third / 2f - arc.containerWidth / 2f) /
                        (arc.carouselScaleAtRelease ?: from.third) * arc.scaleVelocityPerSec, t, inertiaInfluence,
                )
                return DeckSlot(center - arc.containerWidth * scale / 2f,
                    bottom - arc.containerHeight * scale, scale,
                    lerp(1f, slot.alpha, landingBlend(t)))
            }
            DeckSlot(
                lerp(from.first, slot.tx, t),
                lerp(from.second, slot.ty, t),
                lerp(from.third, slot.scale, t),
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

/** Nearby cards follow most of the throw; deeper cards settle more directly into their slots. */
internal fun overviewInertiaInfluence(slotDelta: Int): Float =
    (1f / (1f + 0.45f * kotlin.math.abs(slotDelta))).coerceAtLeast(0.35f)
