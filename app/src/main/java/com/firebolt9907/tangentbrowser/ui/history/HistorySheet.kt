package com.firebolt9907.tangentbrowser.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.firebolt9907.tangentbrowser.ui.components.SheetSearchField
import com.firebolt9907.tangentbrowser.ui.components.TangentModalSheet
import com.firebolt9907.tangentbrowser.ui.components.EmptyState
import com.firebolt9907.tangentbrowser.ui.components.Favicon
import com.firebolt9907.tangentbrowser.ui.components.SheetHeader
import com.firebolt9907.tangentbrowser.ui.components.SwipeToRemoveRow
import kotlinx.coroutines.launch
import mozilla.components.concept.storage.HistoryStorage
import mozilla.components.concept.storage.VisitInfo
import mozilla.components.concept.storage.VisitType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(
    historyStorage: HistoryStorage,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
    recentlyClosed: List<com.firebolt9907.tangentbrowser.state.ClosedTabInfo> = emptyList(),
    onReopenClosed: (com.firebolt9907.tangentbrowser.state.ClosedTabInfo) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var visits by remember { mutableStateOf<List<VisitInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }

    suspend fun reload() {
        // Guard the read so a storage error shows an empty list instead of crashing the sheet
        // (and never clearing the loading state).
        visits = runCatching {
            historyStorage.getVisitsPaginated(
                offset = 0,
                count = 500,
                excludeTypes = listOf(
                    VisitType.RELOAD,
                    VisitType.REDIRECT_PERMANENT,
                    VisitType.REDIRECT_TEMPORARY,
                    VisitType.EMBED,
                    VisitType.DOWNLOAD,
                ),
            ).reversed()
        }.getOrDefault(emptyList())
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    val filtered = remember(visits, searchQuery) {
        if (searchQuery.isBlank()) {
            visits
        } else {
            val q = searchQuery.trim().lowercase()
            visits.filter {
                it.url.lowercase().contains(q) || it.title.orEmpty().lowercase().contains(q)
            }
        }
    }

    // Group visits by calendar day (Today / Yesterday / date) for iOS-style sectioned history.
    // filtered is newest-first, so groupBy's insertion order keeps the sections newest-first too.
    val grouped = remember(filtered) {
        filtered.groupBy { visit ->
            Instant.ofEpochMilli(visit.visitTime).atZone(ZoneId.systemDefault()).toLocalDate()
        }
    }

    TangentModalSheet(onDismissRequest = onDismiss, bottomPadding = 12.dp) {
        SheetHeader("History") {
            if (visits.isNotEmpty()) {
                TextButton(onClick = { showClearConfirm = true }) {
                    Text("Clear all")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        SheetSearchField(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = "Search history",
        )

        Spacer(Modifier.height(8.dp))

        // Recently closed tabs (URL+title, most-recent first). Hidden while searching so the query
        // filters history only. Tapping a row reopens the tab and dismisses the sheet.
        if (searchQuery.isBlank() && recentlyClosed.isNotEmpty()) {
            DateSectionHeader("Recently closed")
            recentlyClosed.take(5).forEach { closed ->
                ListItem(
                    modifier = Modifier.clickable {
                        onReopenClosed(closed)
                        onDismiss()
                    },
                    leadingContent = { Favicon(closed.url) },
                    headlineContent = {
                        Text(
                            text = closed.title.takeIf { it.isNotBlank() } ?: closed.url,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = {
                        Text(
                            text = closed.url,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        when {
            loading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            filtered.isEmpty() -> {
                EmptyState(
                    icon = Icons.Default.History,
                    title = if (searchQuery.isBlank()) "No history yet" else "No matches",
                    description = if (searchQuery.isBlank()) {
                        "Pages you visit will show up here."
                    } else {
                        null
                    },
                )
            }

            else -> {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    grouped.forEach { (date, dayVisits) ->
                        item(key = "header-$date") {
                            DateSectionHeader(dayLabel(date))
                        }
                        items(dayVisits, key = { it.visitTime }) { visit ->
                            // Swipe end-to-start to remove — keeps rows clean instead of a trash
                            // icon on every one. See SwipeToRemoveRow.
                            SwipeToRemoveRow(
                                onRemove = {
                                    scope.launch {
                                        runCatching { historyStorage.deleteVisitsFor(visit.url) }
                                        reload()
                                    }
                                },
                            ) {
                                ListItem(
                                    modifier = Modifier.clickable { onOpenUrl(visit.url) },
                                    leadingContent = { Favicon(visit.url) },
                                    headlineContent = {
                                        Text(
                                            text = visit.title?.takeIf { it.isNotBlank() } ?: visit.url,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                    supportingContent = {
                                        Text(
                                            text = "${visit.url}  ·  ${formatVisitTime(visit.visitTime)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear all history?") },
            text = { Text("This permanently removes your browsing history on this device.") },
            confirmButton = {
                TextButton(onClick = {
                    showClearConfirm = false
                    scope.launch {
                        runCatching { historyStorage.deleteEverything() }
                        reload()
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

// DateTimeFormatter is immutable and thread-safe, unlike the shared SimpleDateFormat it replaces.
// The pattern is locale-independent; the locale is bound per call so a runtime locale change is
// reflected rather than frozen at class-load time. Rows show only the clock time now that the
// calendar day lives in the section header above them.
private val timeFormat = DateTimeFormatter.ofPattern("h:mm a")
private val dayHeaderFormat = DateTimeFormatter.ofPattern("EEEE, MMMM d")
private val dayHeaderFormatWithYear = DateTimeFormatter.ofPattern("MMMM d, yyyy")

private fun formatVisitTime(epochMillis: Long): String =
    timeFormat.withLocale(Locale.getDefault())
        .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

/** "Today" / "Yesterday" / a weekday-and-date (adding the year only when it isn't the current one). */
private fun dayLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when {
        date == today -> "Today"
        date == today.minusDays(1) -> "Yesterday"
        date.year == today.year -> date.format(dayHeaderFormat.withLocale(Locale.getDefault()))
        else -> date.format(dayHeaderFormatWithYear.withLocale(Locale.getDefault()))
    }
}

@Composable
private fun DateSectionHeader(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 4.dp),
    )
}
