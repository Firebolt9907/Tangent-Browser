package com.firebolt9907.luminabrowser.ui.downloads

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.firebolt9907.luminabrowser.ui.rememberLuminaHaptics
import com.firebolt9907.luminabrowser.ui.components.LuminaModalSheet
import com.firebolt9907.luminabrowser.ui.components.EmptyState
import com.firebolt9907.luminabrowser.ui.components.SheetHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * In-app downloads list. The app routes file downloads to the platform [DownloadManager]
 * (see `MainActivity.downloadsFeature` / `AndroidDownloadManager`), so this sheet reads back
 * from that same queue — `DownloadManager.query` only returns downloads owned by this app, and
 * the OS keeps the list across process death. We poll while running downloads exist so progress
 * bars advance without a manual refresh.
 */
private data class DownloadEntry(
    val id: Long,
    val title: String,
    val status: Int,
    val bytesSoFar: Long,
    val totalBytes: Long,
    val lastModified: Long,
    val mimeType: String?,
)

private val DownloadEntry.isActive: Boolean
    get() = status == DownloadManager.STATUS_RUNNING ||
        status == DownloadManager.STATUS_PENDING ||
        status == DownloadManager.STATUS_PAUSED

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsSheet(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var downloads by remember { mutableStateOf<List<DownloadEntry>>(emptyList()) }
    var showClearConfirm by remember { mutableStateOf(false) }

    // Initial load + poll while anything is still in flight so progress updates live.
    // queryDownloads runs a DownloadManager content-provider query + cursor walk, so keep it off
    // the main thread to avoid jank/ANR during the 1.2s polling loop.
    LaunchedEffect(Unit) {
        while (true) {
            downloads = withContext(Dispatchers.IO) { queryDownloads(context) }
            if (downloads.none { it.isActive }) break
            delay(1200)
        }
    }

    LuminaModalSheet(onDismissRequest = onDismiss, bottomPadding = 12.dp) {
        SheetHeader("Downloads") {
            if (downloads.isNotEmpty()) {
                TextButton(onClick = { showClearConfirm = true }) {
                    Text("Clear all")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        if (downloads.isEmpty()) {
            EmptyState(
                icon = Icons.Default.Download,
                title = "No downloads yet",
                description = "Files you download will show up here.",
            )
        } else {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(downloads, key = { it.id }) { entry ->
                    DownloadRow(
                        entry = entry,
                        onOpen = { openDownload(context, entry) },
                        onRemove = {
                            // remove() hits the DownloadManager content provider and can throw;
                            // guard it so a failure can't crash the sheet. The row is dropped
                            // from the UI regardless so the action still feels responsive.
                            runCatching { downloadManager(context)?.remove(entry.id) }
                            downloads = downloads.filterNot { it.id == entry.id }
                        },
                    )
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear all downloads?") },
            text = {
                Text(
                    "This removes the download entries and their files from this device.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    val dm = downloadManager(context)
                    // remove() can throw; guard the whole sweep so one bad id can't crash the sheet.
                    runCatching { downloads.forEach { dm?.remove(it.id) } }
                    downloads = emptyList()
                }) {
                    Text("Clear all")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun DownloadRow(
    entry: DownloadEntry,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val completed = entry.status == DownloadManager.STATUS_SUCCESSFUL
    val haptics = rememberLuminaHaptics()
    ListItem(
        modifier = Modifier.clickable(enabled = completed) { onOpen() },
        leadingContent = {
            val (icon, tint) = when {
                completed -> Icons.Default.CheckCircle to MaterialTheme.colorScheme.primary
                entry.status == DownloadManager.STATUS_FAILED ->
                    Icons.Default.ErrorOutline to MaterialTheme.colorScheme.error
                else -> Icons.Default.Download to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Icon(icon, contentDescription = null, tint = tint)
        },
        headlineContent = {
            Text(
                text = entry.title.ifBlank { "Download" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            Column {
                Text(
                    text = entry.statusLine(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.isActive && entry.status != DownloadManager.STATUS_PENDING) {
                    Spacer(Modifier.height(4.dp))
                    if (entry.totalBytes > 0) {
                        LinearProgressIndicator(
                            progress = {
                                (entry.bytesSoFar.toFloat() / entry.totalBytes).coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        trailingContent = {
            IconButton(onClick = {
                haptics.remove()
                onRemove()
            }) {
                Icon(Icons.Default.Delete, contentDescription = "Remove")
            }
        },
    )
}

private fun DownloadEntry.statusLine(): String = when (status) {
    DownloadManager.STATUS_SUCCESSFUL ->
        "${formatBytes(totalBytes.takeIf { it > 0 } ?: bytesSoFar)}  ·  ${formatTime(lastModified)}"
    DownloadManager.STATUS_FAILED -> "Failed"
    DownloadManager.STATUS_PAUSED -> "Paused  ·  ${progressText()}"
    DownloadManager.STATUS_PENDING -> "Pending"
    DownloadManager.STATUS_RUNNING -> "Downloading  ·  ${progressText()}"
    else -> ""
}

private fun DownloadEntry.progressText(): String =
    if (totalBytes > 0) {
        "${formatBytes(bytesSoFar)} / ${formatBytes(totalBytes)}"
    } else {
        formatBytes(bytesSoFar)
    }

// Nullable: getSystemService can return null on unusual/restricted device configurations, so a
// hard `as` cast would NPE. Callers degrade gracefully on null instead.
private fun downloadManager(context: Context): DownloadManager? =
    context.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager

private fun queryDownloads(context: Context): List<DownloadEntry> {
    val dm = downloadManager(context) ?: return emptyList()
    val result = mutableListOf<DownloadEntry>()
    val cursor = dm.query(DownloadManager.Query()) ?: return emptyList()
    cursor.use { c ->
        val idCol = c.getColumnIndex(DownloadManager.COLUMN_ID)
        val titleCol = c.getColumnIndex(DownloadManager.COLUMN_TITLE)
        val statusCol = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
        val soFarCol = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
        val totalCol = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
        val modifiedCol = c.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
        val mimeCol = c.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
        while (c.moveToNext()) {
            result += DownloadEntry(
                id = if (idCol >= 0) c.getLong(idCol) else continue,
                title = if (titleCol >= 0) c.getString(titleCol).orEmpty() else "",
                status = if (statusCol >= 0) c.getInt(statusCol) else 0,
                bytesSoFar = if (soFarCol >= 0) c.getLong(soFarCol) else 0,
                totalBytes = if (totalCol >= 0) c.getLong(totalCol) else 0,
                lastModified = if (modifiedCol >= 0) c.getLong(modifiedCol) else 0,
                mimeType = if (mimeCol >= 0) c.getString(mimeCol) else null,
            )
        }
    }
    // Most recent first.
    return result.sortedByDescending { it.lastModified }
}

private fun openDownload(context: Context, entry: DownloadEntry) {
    val dm = downloadManager(context)
    val uri: Uri? = dm?.getUriForDownloadedFile(entry.id)
    if (uri == null) {
        Toast.makeText(context, "File not available", Toast.LENGTH_SHORT).show()
        return
    }
    val mime = entry.mimeType ?: dm?.getMimeTypeForDownloadedFile(entry.id) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app can open this file", Toast.LENGTH_SHORT).show()
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) {
        value /= 1024
        unit++
    }
    return if (unit == 0) "${bytes} B" else String.format(Locale.getDefault(), "%.1f %s", value, units[unit])
}

// DateTimeFormatter is immutable and thread-safe, unlike the shared SimpleDateFormat it replaces.
// The pattern is locale-independent; the locale is bound per call so a runtime locale change is
// reflected rather than frozen at class-load time.
private val timeFormat = DateTimeFormatter.ofPattern("MMM d, h:mm a")

private fun formatTime(epochMillis: Long): String =
    if (epochMillis <= 0) "" else
        timeFormat.withLocale(Locale.getDefault())
            .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
