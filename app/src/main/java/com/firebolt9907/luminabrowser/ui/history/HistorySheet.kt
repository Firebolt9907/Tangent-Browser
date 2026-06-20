package com.firebolt9907.luminabrowser.ui.history

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.firebolt9907.luminabrowser.ui.components.EmptyState
import kotlinx.coroutines.launch
import mozilla.components.concept.storage.HistoryStorage
import mozilla.components.concept.storage.VisitInfo
import mozilla.components.concept.storage.VisitType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistorySheet(
    historyStorage: HistoryStorage,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
    bottomInsetDp: Dp = 0.dp,
) {
    val scope = rememberCoroutineScope()
    var visits by remember { mutableStateOf<List<VisitInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }
    var showClearConfirm by remember { mutableStateOf(false) }

    suspend fun reload() {
        visits = historyStorage.getVisitsPaginated(
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        contentWindowInsets = { WindowInsets(bottom = bottomInsetDp) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("History", style = MaterialTheme.typography.titleLarge)
                if (visits.isNotEmpty()) {
                    TextButton(onClick = { showClearConfirm = true }) {
                        Text("Clear all")
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search history") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear search")
                        }
                    }
                },
            )

            Spacer(Modifier.height(8.dp))

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
                        items(filtered, key = { it.visitTime }) { visit ->
                            ListItem(
                                modifier = Modifier.clickable { onOpenUrl(visit.url) },
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
                                trailingContent = {
                                    IconButton(onClick = {
                                        scope.launch {
                                            historyStorage.deleteVisitsFor(visit.url)
                                            reload()
                                        }
                                    }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Remove")
                                    }
                                },
                            )
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
                        historyStorage.deleteEverything()
                        reload()
                    }
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

private val timeFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

private fun formatVisitTime(epochMillis: Long): String =
    timeFormat.format(Date(epochMillis))
