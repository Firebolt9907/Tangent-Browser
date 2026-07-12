package com.firebolt9907.luminabrowser.ui.shape

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

private data class SquircleSamples(val cosPow: FloatArray, val sinPow: FloatArray)

// Grid crop shapes are rebuilt by the render layer as their corner/crop values animate.  The
// superellipse sample points do not depend on size or radius, so calculating trig/pow values for
// every card on every frame is pure avoidable CPU work.
private val defaultSquircleSamples = createSquircleSamples(exponent = 5f, cornerSamples = 16)

private fun createSquircleSamples(exponent: Float, cornerSamples: Int): SquircleSamples {
    val twoOverN = 2.0 / exponent
    return SquircleSamples(
        cosPow = FloatArray(cornerSamples) { i ->
            val theta = ((i + 1).toDouble() / cornerSamples) * (PI / 2.0)
            cos(theta).pow(twoOverN).toFloat()
        },
        sinPow = FloatArray(cornerSamples) { i ->
            val theta = ((i + 1).toDouble() / cornerSamples) * (PI / 2.0)
            sin(theta).pow(twoOverN).toFloat()
        },
    )
}

/**
 * iOS-style continuous-corner rounded rectangle (sampled superellipse). `exponent` is
 * the superellipse `n`: 2 = circle, 5 = the iOS look. Radius is clamped to `min(w,h)/2`.
 */
class SquircleShape(
    private val radius: Dp,
    private val exponent: Float = 5f,
    private val cornerSamples: Int = 16,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val w = size.width
        val h = size.height
        val r = with(density) { radius.toPx() }.coerceAtMost(min(w, h) / 2f)
        if (r <= 0f) {
            return Outline.Rectangle(Rect(0f, 0f, w, h))
        }
        return Outline.Generic(squirclePath(w, h, r, exponent, cornerSamples))
    }
}

/**
 * The raw superellipse-corner path shared by [SquircleShape] and callers that need a squircle at
 * a size other than the layout size (e.g. the tab grid's draw-phase crop shape). [r] must already
 * be clamped to `min(w, h) / 2`.
 */
fun squirclePath(
    w: Float,
    h: Float,
    r: Float,
    exponent: Float = 5f,
    cornerSamples: Int = 16,
): Path {
    val samples = if (exponent == 5f && cornerSamples == 16) {
        defaultSquircleSamples
    } else {
        createSquircleSamples(exponent, cornerSamples)
    }
    val cosPow = samples.cosPow
    val sinPow = samples.sinPow
    return Path().apply {
        moveTo(0f, r)
        for (i in 0 until cornerSamples) {
            lineTo(r - r * cosPow[i], r - r * sinPow[i])
        }
        lineTo(w - r, 0f)
        for (i in 0 until cornerSamples) {
            lineTo((w - r) + r * sinPow[i], r - r * cosPow[i])
        }
        lineTo(w, h - r)
        for (i in 0 until cornerSamples) {
            lineTo((w - r) + r * cosPow[i], (h - r) + r * sinPow[i])
        }
        lineTo(r, h)
        for (i in 0 until cornerSamples) {
            lineTo(r - r * sinPow[i], (h - r) + r * cosPow[i])
        }
        lineTo(0f, r)
        close()
    }
}
