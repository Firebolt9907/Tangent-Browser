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
    val twoOverN = 2.0 / exponent
    // All four corners sample the same superellipse quadrant, so compute the (cos^k, sin^k)
    // pairs once and reuse them per corner — the pow()/cos()/sin() calls are the expensive part,
    // and this cuts them 4×. Each corner just maps the shared samples onto its own axes.
    val cosPow = FloatArray(cornerSamples)
    val sinPow = FloatArray(cornerSamples)
    for (i in 0 until cornerSamples) {
        val theta = ((i + 1).toDouble() / cornerSamples) * (PI / 2.0)
        cosPow[i] = cos(theta).pow(twoOverN).toFloat()
        sinPow[i] = sin(theta).pow(twoOverN).toFloat()
    }
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
