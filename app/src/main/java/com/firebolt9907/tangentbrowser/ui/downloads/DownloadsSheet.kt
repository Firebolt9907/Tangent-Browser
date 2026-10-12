package com.firebolt9907.tangentbrowser.ui.downloads

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.firebolt9907.tangentbrowser.ui.rememberTangentHaptics
import com.firebolt9907.tangentbrowser.ui.components.TangentModalSheet
import com.firebolt9907.tangentbrowser.ui.components.EmptyState
import com.firebolt9907.tangentbrowser.ui.components.SheetContentPhase
import com.firebolt9907.tangentbrowser.ui.components.SheetContentSwitch
import com.firebolt9907.tangentbrowser.ui.components.SheetHeader
import com.firebolt9907.tangentbrowser.ui.components.SheetSearchField
import com.firebolt9907.tangentbrowser.ui.components.SwipeToRemoveRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
    val reason: Int,
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
    val scope = rememberCoroutineScope()
    val haptics = rememberTangentHaptics()
    // Failures used to be android.widget.Toast — an unthemed platform surface that draws *outside*
    // the sheet and can't offer a way out of the failure. A SnackbarHost anchored to the sheet's
    // own bottom edge is the M3 surface for this, and it can carry the recovery action: a download
    // whose file is gone is a dead row, so the message offers to remove it.
    val snackbarHostState = remember { SnackbarHostState() }
    var downloads by remember { mutableStateOf<List<DownloadEntry>>(emptyList()) }
    var showClearConfirm by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    // True until the first query comes back. Without it the sheet renders its "No downloads yet"
    // empty state for the duration of that query and then swaps to the list — the same reason every
    // other library sheet keeps a loading flag.
    var loading by remember { mutableStateOf(true) }
    // A failed read of the DownloadManager queue used to be indistinguishable from an empty one:
    // queryDownloads swallowed the null service / null cursor and the sheet then stated "No
    // downloads yet" — a confident, wrong claim about the user's files, with no way to retry.
    var loadFailed by remember { mutableStateOf(false) }
    // Bumping this re-keys the loader, so "Try again" is one state change rather than a second
    // parallel load path.
    var reloadKey by remember { mutableStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    val visibleDownloads = downloads.filter {
        it.title.ifBlank { "Download" }.contains(searchQuery.trim(), ignoreCase = true)
    }

    // Initial load + poll while anything is still in flight so progress updates live.
    // queryDownloads runs a DownloadManager content-provider query + cursor walk, so keep it off
    // the main thread to avoid jank/ANR during the 1.2s polling loop.
    LaunchedEffect(reloadKey) {
        // Keep the existing list visible while a manual refresh checks for new files.
        loading = downloads.isEmpty()
        loadFailed = false
        while (true) {
            val loaded = withContext(Dispatchers.IO) { queryDownloads(context) }
            loading = false
            if (loaded == null) {
                // Keep whatever the last good poll produced rather than blanking a live list on one
                // transient provider failure. The error state only shows when there is nothing;
                // otherwise explain why refresh didn't update the visible list.
                loadFailed = true
                if (downloads.isNotEmpty()) {
                    scope.launch {
                        snackbarHostState.showSnackbar(
                            message = "Couldn't refresh downloads",
                            duration = SnackbarDuration.Short,
                        )
                    }
                }
                break
            }
            loadFailed = false
            downloads = loaded
            if (downloads.none { it.isActive }) break
            delay(1200)
        }
    }

    // Drops an entry from both the DownloadManager queue and the list. Shared by the swipe gesture
    // and the "file is gone" snackbar action.
    fun removeEntry(id: Long) {
        val removedEntry = downloads.firstOrNull { it.id == id } ?: return
        // Hide the row immediately, then restore it if the provider rejects the deletion.
        downloads = downloads.filterNot { it.id == id }
        scope.launch {
            val removed = withContext(Dispatchers.IO) {
                runCatching { (downloadManager(context)?.remove(id) ?: 0) > 0 }
                    .getOrDefault(false)
            }
            if (!removed) {
                if (downloads.none { it.id == id }) {
                    downloads = sortDownloads(downloads + removedEntry)
                }
                snackbarHostState.showSnackbar(
                    message = "Couldn't remove that download",
                    duration = SnackbarDuration.Short,
                )
            }
        }
    }

    TangentModalSheet(onDismissRequest = onDismiss, bottomPadding = 12.dp) {
        // The snackbar overlays the sheet's own content rather than sitting after it in the column,
        // so a message doesn't shove the list around as it comes and goes.
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                SheetHeader("Downloads") {
                    IconButton(onClick = { reloadKey++ }, enabled = !loading) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh downloads")
                    }
                    if (downloads.isNotEmpty() || clearing) {
                        TextButton(
                            onClick = { showClearConfirm = true },
                            enabled = !clearing,
                        ) {
                            Text(if (clearing) "Clearing…" else "Clear all")
                        }
                    }
                }

                if (downloads.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    SheetSearchField(
                        query = searchQuery,
                        onQueryChange = { searchQuery = it },
                        placeholder = "Search downloads",
                    )
                }

                Spacer(Modifier.height(12.dp))

                // Spinner → empty/list is a cross-fade, not a swap: see SheetContentSwitch.
                val phase = when {
                    loading -> SheetContentPhase.Loading
                    visibleDownloads.isEmpty() -> SheetContentPhase.Empty
                    else -> SheetContentPhase.Content
                }
                SheetContentSwitch(phase, modifier = Modifier.fillMaxWidth()) { current ->
                    when (current) {
                        SheetContentPhase.Loading -> {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                CircularProgressIndicator()
                            }
                        }

                        SheetContentPhase.Empty -> {
                            // Two very different nothings share this phase: a genuinely empty queue,
                            // and a queue we couldn't read. Only the latter gets an action.
                            if (searchQuery.isNotBlank() && downloads.isNotEmpty()) {
                                EmptyState(
                                    icon = Icons.Default.Download,
                                    title = "No matching downloads",
                                    description = "Try a different file name.",
                                    action = {
                                        TextButton(onClick = { searchQuery = "" }) {
                                            Text("Clear search")
                                        }
                                    },
                                )
                            } else if (loadFailed) {
                                EmptyState(
                                    icon = Icons.Default.ErrorOutline,
                                    title = "Couldn't load downloads",
                                    description =
                                        "Something went wrong reading your downloads.",
                                    action = {
                                        Button(onClick = { reloadKey++ }) { Text("Try again") }
                                    },
                                )
                            } else {
                                EmptyState(
                                    icon = Icons.Default.Download,
                                    title = "No downloads yet",
                                    description = "Files you download will show up here.",
                                )
                            }
                        }

                        SheetContentPhase.Content -> {
                            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                                items(visibleDownloads, key = { it.id }) { entry ->
                                    // Swipe end-to-start to remove — no per-row trash icon.
                                    // See SwipeToRemoveRow.
                                    SwipeToRemoveRow(
                                        onRemove = { removeEntry(entry.id) },
                                        settleOnRemove = false,
                                        removeLabel = "Remove download",
                                    ) {
                                        DownloadRow(
                                            entry = entry,
                                            onOpen = {
                                                when (openDownload(context, entry)) {
                                                    OpenResult.Opened -> Unit
                                                    OpenResult.FileMissing -> scope.launch {
                                                        val result = snackbarHostState.showSnackbar(
                                                            message = "File no longer on this device",
                                                            actionLabel = "Remove",
                                                            duration = SnackbarDuration.Long,
                                                        )
                                                        if (result == SnackbarResult.ActionPerformed) {
                                                            haptics.remove()
                                                            removeEntry(entry.id)
                                                        }
                                                    }
                                                    OpenResult.NoHandler -> scope.launch {
                                                        snackbarHostState.showSnackbar(
                                                            message = "No app can open this file",
                                                            duration = SnackbarDuration.Short,
                                                        )
                                                    }
                                                }
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
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
                    haptics.remove()
                    val ids = downloads.map { it.id }
                    clearing = true
                    scope.launch {
                        val removedIds = withContext(Dispatchers.IO) {
                            val dm = downloadManager(context)
                            ids.filter { id ->
                                runCatching { (dm?.remove(id) ?: 0) > 0 }.getOrDefault(false)
                            }.toSet()
                        }
                        downloads = downloads.filterNot { it.id in removedIds }
                        clearing = false
                        if (removedIds.size != ids.size) {
                            snackbarHostState.showSnackbar(
                                message = "Couldn't clear every download",
                                duration = SnackbarDuration.Short,
                            )
                        }
                    }
                }) {
                    Text("Clear all", color = MaterialTheme.colorScheme.error)
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
) {
    val completed = entry.status == DownloadManager.STATUS_SUCCESSFUL
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
    )
}

private fun DownloadEntry.statusLine(): String = when (status) {
    DownloadManager.STATUS_SUCCESSFUL ->
        "${formatBytes(totalBytes.takeIf { it > 0 } ?: bytesSoFar)}  ·  ${formatTime(lastModified)}"
    DownloadManager.STATUS_FAILED -> failureReason(reason)?.let { "Failed  ·  $it" } ?: "Failed"
    DownloadManager.STATUS_PAUSED -> pauseReason(reason)?.let { "Paused  ·  $it" }
        ?: "Paused  ·  ${progressText()}"
    DownloadManager.STATUS_PENDING -> "Pending"
    DownloadManager.STATUS_RUNNING -> "Downloading  ·  ${progressText()}"
    else -> ""
}

private fun failureReason(reason: Int): String? = when (reason) {
    DownloadManager.ERROR_INSUFFICIENT_SPACE -> "Not enough storage"
    DownloadManager.ERROR_DEVICE_NOT_FOUND -> "Storage unavailable"
    DownloadManager.ERROR_FILE_ALREADY_EXISTS -> "File already exists"
    DownloadManager.ERROR_CANNOT_RESUME -> "Couldn't resume"
    DownloadManager.ERROR_TOO_MANY_REDIRECTS -> "Too many redirects"
    DownloadManager.ERROR_HTTP_DATA_ERROR,
    DownloadManager.ERROR_UNHANDLED_HTTP_CODE -> "Network error"
    DownloadManager.ERROR_FILE_ERROR -> "File error"
    in 400..599 -> "HTTP $reason"
    else -> null
}

private fun pauseReason(reason: Int): String? = when (reason) {
    DownloadManager.PAUSED_QUEUED_FOR_WIFI -> "Waiting for Wi-Fi"
    DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "Waiting for network"
    DownloadManager.PAUSED_WAITING_TO_RETRY -> "Retrying soon"
    else -> null
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

/**
 * Reads back this app's DownloadManager queue, or `null` if the queue couldn't be read at all —
 * no download service, no cursor, or a throwing content-provider query. `null` is deliberately not
 * folded into an empty list: the sheet renders those two cases differently.
 */
private fun queryDownloads(context: Context): List<DownloadEntry>? = runCatching {
    val dm = downloadManager(context) ?: return null
    val result = mutableListOf<DownloadEntry>()
    val cursor = dm.query(DownloadManager.Query()) ?: return null
    cursor.use { c ->
        val idCol = c.getColumnIndex(DownloadManager.COLUMN_ID)
        val titleCol = c.getColumnIndex(DownloadManager.COLUMN_TITLE)
        val statusCol = c.getColumnIndex(DownloadManager.COLUMN_STATUS)
        val soFarCol = c.getColumnIndex(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
        val totalCol = c.getColumnIndex(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
        val modifiedCol = c.getColumnIndex(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)
        val mimeCol = c.getColumnIndex(DownloadManager.COLUMN_MEDIA_TYPE)
        val reasonCol = c.getColumnIndex(DownloadManager.COLUMN_REASON)
        while (c.moveToNext()) {
            result += DownloadEntry(
                id = if (idCol >= 0) c.getLong(idCol) else continue,
                title = if (titleCol >= 0) c.getString(titleCol).orEmpty() else "",
                status = if (statusCol >= 0) c.getInt(statusCol) else 0,
                bytesSoFar = if (soFarCol >= 0) c.getLong(soFarCol) else 0,
                totalBytes = if (totalCol >= 0) c.getLong(totalCol) else 0,
                lastModified = if (modifiedCol >= 0) c.getLong(modifiedCol) else 0,
                mimeType = if (mimeCol >= 0) c.getString(mimeCol) else null,
                reason = if (reasonCol >= 0) c.getInt(reasonCol) else 0,
            )
        }
    }
    // Keep downloads that still need attention visible above completed files.
    sortDownloads(result)
}.getOrNull()

private fun sortDownloads(entries: List<DownloadEntry>): List<DownloadEntry> =
    entries.sortedWith(
        compareByDescending<DownloadEntry> { it.isActive }
            .thenByDescending { it.lastModified },
    )

/** Outcome of tapping a completed download, so the caller can surface the failure as a snackbar. */
private enum class OpenResult { Opened, FileMissing, NoHandler }

private fun openDownload(context: Context, entry: DownloadEntry): OpenResult {
    val dm = downloadManager(context)
    val uri: Uri? = dm?.getUriForDownloadedFile(entry.id)
    if (uri == null) return OpenResult.FileMissing
    val mime = entry.mimeType ?: dm.getMimeTypeForDownloadedFile(entry.id) ?: "*/*"
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    return try {
        context.startActivity(intent)
        OpenResult.Opened
    } catch (_: ActivityNotFoundException) {
        OpenResult.NoHandler
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
