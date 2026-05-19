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
        val twoOverN = (2.0 / exponent).toDouble()
        val path = Path().apply {
            moveTo(0f, r)
            for (i in 1..cornerSamples) {
                val theta = (i.toDouble() / cornerSamples) * (PI / 2.0)
                val c = cos(theta).pow(twoOverN).toFloat()
                val s = sin(theta).pow(twoOverN).toFloat()
                lineTo(r - r * c, r - r * s)
            }
            lineTo(w - r, 0f)
            for (i in 1..cornerSamples) {
                val theta = (i.toDouble() / cornerSamples) * (PI / 2.0)
                val c = cos(theta).pow(twoOverN).toFloat()
                val s = sin(theta).pow(twoOverN).toFloat()
                lineTo((w - r) + r * s, r - r * c)
            }
            lineTo(w, h - r)
            for (i in 1..cornerSamples) {
                val theta = (i.toDouble() / cornerSamples) * (PI / 2.0)
                val c = cos(theta).pow(twoOverN).toFloat()
                val s = sin(theta).pow(twoOverN).toFloat()
                lineTo((w - r) + r * c, (h - r) + r * s)
            }
            lineTo(r, h)
            for (i in 1..cornerSamples) {
                val theta = (i.toDouble() / cornerSamples) * (PI / 2.0)
                val c = cos(theta).pow(twoOverN).toFloat()
                val s = sin(theta).pow(twoOverN).toFloat()
                lineTo(r - r * s, (h - r) + r * c)
            }
            lineTo(0f, r)
            close()
        }
        return Outline.Generic(path)
    }
}
