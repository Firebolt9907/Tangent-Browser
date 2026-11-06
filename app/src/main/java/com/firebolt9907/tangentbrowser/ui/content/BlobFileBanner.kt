package com.firebolt9907.tangentbrowser.ui.content

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.Dp
import com.firebolt9907.tangentbrowser.state.BlobFile
import com.firebolt9907.tangentbrowser.ui.BannerCard
import com.firebolt9907.tangentbrowser.ui.BannerPriority
import com.firebolt9907.tangentbrowser.ui.BannerStackState
import com.firebolt9907.tangentbrowser.ui.MorphingBannerHost

/**
 * Offers to save the file behind the blob document currently on screen (see [BlobCapture]).
 *
 * Blob documents have no address to bookmark, no link to long-press and — being generated in the
 * page — frequently no download button either, so without this the file is only reachable for as
 * long as the tab lives. Shown once the bytes are safely in the cache, which is also the point at
 * which saving can't fail for lack of the original blob.
 *
 * Rendered by the shared [BannerCard], so it takes the same Expressive press-morph, emphasized
 * title and `Role.Button` semantics as the sync banners rather than a near-copy of their surface.
 */
@Composable
fun BlobFileBannerHost(
    file: BlobFile?,
    visible: Boolean,
    bottomPadding: Dp,
    collapseProgress: () -> Float = { 0f },
    gridProgress: () -> Float = { 0f },
    // True while the tab switcher is open: this banner belongs to the current tab's page, so it
    // must neither show nor take taps over the overview (see MorphingBannerHost.suppressed).
    suppressed: Boolean = false,
    stack: BannerStackState? = null,
    onSave: (BlobFile) -> Unit,
    onDismiss: () -> Unit,
) {
    MorphingBannerHost(
        visible = visible && file != null,
        bottomPadding = bottomPadding,
        collapseProgress = collapseProgress,
        gridProgress = gridProgress,
        suppressed = suppressed,
        stack = stack,
        stackId = "blobFile",
        stackPriority = BannerPriority.BlobFile,
    ) {
        // Held across the exit morph so the card doesn't blank out after the file clears.
        var last by remember { mutableStateOf(file) }
        if (file != null) last = file
        last?.let { blob ->
            BannerCard(
                icon = Icons.Default.Download,
                title = "${blob.typeLabel} · ${formatSize(blob.sizeBytes)}",
                subtitle = "Tap to save to Downloads",
                dismissDescription = "Dismiss save offer",
                onClick = { onSave(blob) },
                onDismiss = onDismiss,
            )
        }
    }
}

/** Compact human size — `1.2 MB`, `840 KB`. */
internal fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L ->
        String.format(java.util.Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024L -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
    bytes >= 1024L -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
