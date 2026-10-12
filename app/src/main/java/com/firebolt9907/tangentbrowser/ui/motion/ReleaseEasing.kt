package com.firebolt9907.tangentbrowser.ui.motion

import androidx.compose.animation.core.Easing
import kotlin.math.abs

/** Shared normalized clock for a sheet and platform IME, which cannot overshoot its inset bounds. */
internal fun releaseEasing(distance: Float, velocity: Float, durationMillis: Int): Easing {
    // Limit the tangent to a monotone cubic. Opposing velocity cannot be followed by the IME
    // without pulling the two surfaces apart, so it starts from rest in that case.
    val tangent = if (abs(distance) < 1f) 0f else
        (velocity * durationMillis / 1000f / distance).coerceIn(0f, 3f)
    return Easing { t ->
        t * t * (3f - 2f * t) + tangent * t * (1f - t) * (1f - t)
    }
}
