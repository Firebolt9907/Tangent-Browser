package com.firebolt9907.tangentbrowser.ui.downloads

import android.content.Intent
import android.text.format.Formatter
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import com.firebolt9907.tangentbrowser.engine.TangentBrowserComponents
import com.firebolt9907.tangentbrowser.service.TangentDownloadService
import com.firebolt9907.tangentbrowser.ui.BannerStackState
import com.firebolt9907.tangentbrowser.ui.MorphingBannerHost
import mozilla.components.browser.state.state.content.DownloadState
import mozilla.components.browser.state.state.content.DownloadState.Status
import mozilla.components.feature.downloads.AbstractFetchDownloadService
import mozilla.components.feature.downloads.INTENT_EXTRA_DOWNLOAD_ID

internal fun downloadProgress(bytes: Long, total: Long?): Float? =
    total?.takeIf { it > 0 }?.let { (bytes.toDouble() / it).coerceIn(0.0, 1.0).toFloat() }

internal fun visibleDownloads(downloads: Collection<DownloadState>, private: Boolean) =
    downloads.filter { it.private == private && it.status != Status.CANCELLED }
        .sortedByDescending { it.createdTime }

@Composable
internal fun DownloadBannerHost(
    components: TangentBrowserComponents,
    private: Boolean,
    bottomPadding: Dp,
    collapseProgress: () -> Float,
    stack: BannerStackState,
) {
    val state by components.browserState.collectAsStateWithLifecycle()
    var dismissed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    val openedAt = remember { System.currentTimeMillis() }
    val download = visibleDownloads(state.downloads.values, private).firstOrNull {
        it.id !in dismissed && (it.status != Status.COMPLETED || it.createdTime >= openedAt)
    }
    var last by remember { mutableStateOf<DownloadState?>(null) }
    if (download != null) last = download
    MorphingBannerHost(
        visible = download != null,
        bottomPadding = bottomPadding,
        collapseProgress = collapseProgress,
        stack = stack,
        stackId = "download",
        stackPriority = 55,
    ) {
        last?.let {
            ManagedDownloadCard(it, components, onDismiss = { dismissed = dismissed + it.id })
        }
    }
}

@Composable
internal fun ManagedDownloadCard(
    download: DownloadState,
    components: TangentBrowserComponents,
    onDismiss: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    var error by remember(download.id) { mutableStateOf<String?>(null) }
    val active = download.status == Status.DOWNLOADING || download.status == Status.INITIATED
    val progress = downloadProgress(download.currentBytesCopied, download.contentLength)
    val size = Formatter.formatFileSize(context, download.currentBytesCopied.coerceAtLeast(0))
    val total = download.contentLength?.takeIf { it > 0 }?.let { Formatter.formatFileSize(context, it) }
    Surface(shape = MaterialTheme.shapes.large, tonalElevation = 3.dp, shadowElevation = 6.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row {
                Column(Modifier.weight(1f)) {
                    Text(download.fileName ?: "Download", maxLines = 1, overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall)
                    Text(error ?: "${download.status.name.lowercase().replaceFirstChar { it.uppercase() }} · $size${total?.let { " / $it" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall)
                }
                if (onDismiss != null) IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Dismiss download banner")
                }
            }
            if (active || download.status == Status.PAUSED) {
                Spacer(Modifier.height(6.dp))
                if (progress != null) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                else if (active) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            TextButton(onClick = {
                error = null
                when {
                    active -> context.sendBroadcast(Intent(AbstractFetchDownloadService.ACTION_PAUSE)
                        .setPackage(context.packageName).putExtra(INTENT_EXTRA_DOWNLOAD_ID, download.id))
                    download.status == Status.PAUSED && TangentDownloadService.canResume(download.id) ->
                        context.sendBroadcast(Intent(AbstractFetchDownloadService.ACTION_RESUME)
                            .setPackage(context.packageName).putExtra(INTENT_EXTRA_DOWNLOAD_ID, download.id))
                    download.status == Status.PAUSED || download.status == Status.FAILED -> {
                        // A restored job has no live byte offset: offer an explicit restart.
                        ContextCompat.startForegroundService(context,
                            Intent(context, TangentDownloadService::class.java)
                                .setAction(AbstractFetchDownloadService.ACTION_TRY_AGAIN)
                                .putExtra(android.app.DownloadManager.EXTRA_DOWNLOAD_ID, download.id))
                    }
                    download.status == Status.COMPLETED -> {
                        if (!runCatching { components.downloadFileUtils.openFile(
                            fileName = download.fileName, directoryPath = download.directoryPath,
                            contentType = download.contentType,
                        ) }.getOrDefault(false)) error = "Couldn't open this file"
                    }
                }
            }) {
                Text(when (download.status) {
                    Status.COMPLETED -> "Open"
                    Status.PAUSED -> if (TangentDownloadService.canResume(download.id)) "Resume" else "Restart"
                    Status.FAILED -> "Try again"
                    else -> "Pause"
                })
            }
        }
    }
}
