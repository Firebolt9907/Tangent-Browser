package com.firebolt9907.tangentbrowser.ui

import android.graphics.Bitmap
import mozilla.components.browser.state.state.content.HistoryState

// Small state-free models and helpers for [TangentRoot], extracted from TangentRoot.kt. `internal`
// (were `private` when co-located) so they remain reachable from the main composable file.

internal class BarCallbacks(
    val onUrlTap: () -> Unit,
    val onEditingTextChange: (String) -> Unit,
    val onEditingSubmit: () -> Unit,
    val onEditingCancel: () -> Unit,
)

/** The link a just-copied "Send to device" banner offers to push to another Sync device. */
internal data class SendLinkPrompt(val url: String, val title: String)

internal enum class SnapshotRequestPurpose {
    PredictiveBack,
    TabSwitch,
    LinkPeek,
}

/**
 * Crop [full] (a capture of the whole engine view) to [rect] (element bounds in root px). The
 * capture may be at a different pixel scale than the layout, so [viewW]/[viewH] (the view's root-px
 * size) are used to rescale the rect into bitmap space. Returns null if the region is degenerate.
 */
internal fun cropBitmapToRect(
    full: Bitmap,
    rect: androidx.compose.ui.geometry.Rect,
    viewW: Float,
    viewH: Float,
): Bitmap? {
    if (viewW <= 0f || viewH <= 0f) return null
    val sx = full.width / viewW
    val sy = full.height / viewH
    val left = (rect.left * sx).toInt().coerceIn(0, full.width - 1)
    val top = (rect.top * sy).toInt().coerceIn(0, full.height - 1)
    val width = (rect.width * sx).toInt().coerceAtLeast(1).coerceAtMost(full.width - left)
    val height = (rect.height * sy).toInt().coerceAtLeast(1).coerceAtMost(full.height - top)
    if (width <= 0 || height <= 0) return null
    return runCatching { Bitmap.createBitmap(full, left, top, width, height) }.getOrNull()
}

internal fun predictiveBackTargetUrl(
    history: HistoryState,
    visibleUrl: String?,
    loading: Boolean,
): String? {
    val currentHistoryUrl = history.items.getOrNull(history.currentIndex)
        ?.uri
        ?.takeUnless(::isBlankHistoryUrl)
    if (loading && currentHistoryUrl != null && currentHistoryUrl != visibleUrl) {
        return currentHistoryUrl
    }
    val previousIndex = history.currentIndex - 1
    return history.items.getOrNull(previousIndex)?.uri?.takeUnless(::isBlankHistoryUrl)
}

internal fun isBlankHistoryUrl(url: String): Boolean {
    return url.isBlankPageUrl()
}

internal fun String?.isBlankPageUrl(): Boolean = isNullOrBlank() || this == "about:blank"

/**
 * A memory-light RGB_565 copy for the in-memory snapshot maps — half the heap of the ARGB_8888
 * captureThumbnail output, and opaque web pages lose nothing visually (the disk cache stores 565
 * for the same reason). Falls back to the original if the copy fails (OOM).
 */
internal fun Bitmap.toResidentSnapshot(): Bitmap {
    if (config == Bitmap.Config.RGB_565) return this
    return runCatching { copy(Bitmap.Config.RGB_565, false) }.getOrNull() ?: this
}
