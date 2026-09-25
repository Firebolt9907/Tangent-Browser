package com.firebolt9907.tangentbrowser.state

/** Reject clear/black frames while retaining dark pages with visible text or images. */
internal fun snapshotSamplesHaveDetail(samples: IntArray): Boolean {
    if (samples.isEmpty()) return false
    // Transparent pixels cannot supply visible detail, regardless of their hidden RGB channels.
    fun visibleRgb(color: Int): Int = if ((color ushr 24) < 16) 0 else color and 0x00ffffff
    val first = visibleRgb(samples.first())
    var hasVisibleContent = false
    var hasContrast = false
    for (sample in samples) {
        val color = visibleRgb(sample)
        val red = (color shr 16) and 255
        val green = (color shr 8) and 255
        val blue = color and 255
        if (maxOf(red, green, blue) > 32) hasVisibleContent = true
        if (kotlin.math.abs(red - ((first shr 16) and 255)) > 12 ||
            kotlin.math.abs(green - ((first shr 8) and 255)) > 12 ||
            kotlin.math.abs(blue - (first and 255)) > 12
        ) hasContrast = true
        if (hasVisibleContent && hasContrast) return true
    }
    return false
}

/** Sample off main, away from the edge where compositor decorations can hide a blank frame. */
internal fun android.graphics.Bitmap.hasSnapshotDetail(): Boolean {
    if (isRecycled || width < 2 || height < 2) return false
    val samples = IntArray(32 * 32) { index ->
        val x = ((index % 32 + 1).toLong() * width / 33).toInt().coerceAtMost(width - 1)
        val y = ((index / 32 + 1).toLong() * height / 33).toInt().coerceAtMost(height - 1)
        getPixel(x, y)
    }
    return snapshotSamplesHaveDetail(samples)
}
