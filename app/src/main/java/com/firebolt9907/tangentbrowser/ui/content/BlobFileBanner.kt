package com.firebolt9907.tangentbrowser.ui.content

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.firebolt9907.tangentbrowser.state.BlobFile
import com.firebolt9907.tangentbrowser.ui.BannerPriority
import com.firebolt9907.tangentbrowser.ui.BannerStackState
import com.firebolt9907.tangentbrowser.ui.MorphingBannerHost
import com.firebolt9907.tangentbrowser.ui.bannerSwipeToDismiss
import com.firebolt9907.tangentbrowser.ui.shape.SquircleShape

/**
 * Offers to save the file behind the blob document currently on screen (see [BlobCapture]).
 *
 * Blob documents have no address to bookmark, no link to long-press and — being generated in the
 * page — frequently no download button either, so without this the file is only reachable for as
 * long as the tab lives. Shown once the bytes are safely in the cache, which is also the point at
 * which saving can't fail for lack of the original blob.
 */
@Composable
fun BlobFileBannerHost(
    file: BlobFile?,
    visible: Boolean,
    bottomPadding: Dp,
    collapseProgress: () -> Float = { 0f },
    stack: BannerStackState? = null,
    onSave: (BlobFile) -> Unit,
    onDismiss: () -> Unit,
) {
    MorphingBannerHost(
        visible = visible && file != null,
        bottomPadding = bottomPadding,
        collapseProgress = collapseProgress,
        stack = stack,
        stackId = "blobFile",
        stackPriority = BannerPriority.BlobFile,
    ) {
        // Held across the exit morph so the card doesn't blank out after the file clears.
        var last by remember { mutableStateOf(file) }
        if (file != null) last = file
        last?.let { blob ->
            Surface(
                shape = SquircleShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHighest,
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .bannerSwipeToDismiss(onDismiss),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSave(blob) }
                        .padding(start = 16.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${blob.typeLabel} · ${formatSize(blob.sizeBytes)}",
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "Tap to save to Downloads",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Dismiss",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
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
