package com.firebolt9907.luminabrowser.ui.bookmarks

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import com.firebolt9907.luminabrowser.ui.components.LuminaModalSheet
import com.firebolt9907.luminabrowser.ui.components.EmptyState
import com.firebolt9907.luminabrowser.ui.components.Favicon
import com.firebolt9907.luminabrowser.ui.components.SheetHeader
import kotlinx.coroutines.launch
import mozilla.appservices.places.BookmarkRoot
import mozilla.components.concept.storage.BookmarkNode
import mozilla.components.concept.storage.BookmarkNodeType
import mozilla.components.concept.storage.BookmarksStorage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksSheet(
    bookmarksStorage: BookmarksStorage,
    currentPageUrl: String,
    currentPageTitle: String,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var bookmarks by remember { mutableStateOf<List<BookmarkNode>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var searchQuery by remember { mutableStateOf("") }

    suspend fun reload() {
        bookmarks = bookmarksStorage.getTree(BookmarkRoot.Mobile.id, recursive = true)
            .getOrNull()
            ?.children
            ?.filter { it.type == BookmarkNodeType.ITEM && !it.url.isNullOrBlank() }
            ?.sortedByDescending { it.dateAdded }
            .orEmpty()
        loading = false
    }

    LaunchedEffect(Unit) { reload() }

    val canBookmarkCurrent = currentPageUrl.isNotBlank() &&
        currentPageUrl != "about:blank" &&
        bookmarks.none { it.url == currentPageUrl }

    val filtered = remember(bookmarks, searchQuery) {
        if (searchQuery.isBlank()) {
            bookmarks
        } else {
            val q = searchQuery.trim().lowercase()
            bookmarks.filter {
                it.url.orEmpty().lowercase().contains(q) ||
                    it.title.orEmpty().lowercase().contains(q)
            }
        }
    }

    LuminaModalSheet(onDismissRequest = onDismiss, bottomPadding = 12.dp) {
        SheetHeader("Bookmarks")

        Spacer(Modifier.height(12.dp))

        if (canBookmarkCurrent) {
            FilledTonalButton(
                onClick = {
                    scope.launch {
                        // Guard the write so a storage error can't crash the sheet; reload
                        // afterward to reflect whatever actually persisted.
                        runCatching {
                            bookmarksStorage.addItem(
                                parentGuid = BookmarkRoot.Mobile.id,
                                url = currentPageUrl,
                                title = currentPageTitle.ifBlank { currentPageUrl },
                                position = null,
                            )
                        }
                        reload()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.height(0.dp))
                Text(
                    text = "  Add this page",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Search bookmarks") },
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
                    icon = Icons.Outlined.BookmarkBorder,
                    title = if (searchQuery.isBlank()) "No bookmarks yet" else "No matches",
                    description = if (searchQuery.isBlank()) {
                        "Save a page with \"Add this page\" to find it here later."
                    } else {
                        null
                    },
                )
            }

            else -> {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(filtered, key = { it.guid }) { node ->
                        val url = node.url.orEmpty()
                        ListItem(
                            modifier = Modifier.clickable { onOpenUrl(url) },
                            leadingContent = { Favicon(url) },
                            headlineContent = {
                                Text(
                                    text = node.title?.takeIf { it.isNotBlank() } ?: url,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            supportingContent = {
                                Text(
                                    text = url,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            trailingContent = {
                                IconButton(onClick = {
                                    scope.launch {
                                        runCatching { bookmarksStorage.deleteNode(node.guid) }
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
