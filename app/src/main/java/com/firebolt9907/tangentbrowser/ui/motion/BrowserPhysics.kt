package com.firebolt9907.tangentbrowser.ui.motion

import androidx.compose.animation.core.spring

/** One physical response for app-owned page/chrome movement, independent of its destination. */
internal object BrowserPhysics {
    const val Damping = 1f
    // From rest: ~185 ms to cover 90% of the distance, ~315 ms for 99%.
    // A brisk approach with a soft landing, without a second bounce/settle phase.
    // Keep the same response in both directions so interruptions retain their character.
    const val Stiffness = 440f

    fun <T> spring(visibilityThreshold: T? = null) = spring(
        dampingRatio = Damping,
        stiffness = Stiffness,
        visibilityThreshold = visibilityThreshold,
    )

    val Progress = spring<Float>(visibilityThreshold = 0.001f)
    val Pixels = spring(visibilityThreshold = 0.5f)
}
