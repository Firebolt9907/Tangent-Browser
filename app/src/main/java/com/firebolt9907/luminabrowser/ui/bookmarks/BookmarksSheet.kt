package com.firebolt9907.luminabrowser.ui.bookmarks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.firebolt9907.luminabrowser.ui.components.SheetSearchField
import com.firebolt9907.luminabrowser.ui.components.LuminaModalSheet
import com.firebolt9907.luminabrowser.ui.components.EmptyState
import com.firebolt9907.luminabrowser.ui.components.Favicon
import com.firebolt9907.luminabrowser.ui.components.SheetHeader
import com.firebolt9907.luminabrowser.ui.components.SwipeToRemoveRow
import kotlinx.coroutines.launch
import mozilla.appservices.places.BookmarkRoot
import mozilla.components.concept.storage.BookmarkNode
import mozilla.components.concept.storage.BookmarkNodeType
import mozilla.components.concept.storage.BookmarksStorage

/** Folder (under the Mobile bookmarks root) that backs the Safari-style Reading List. */
private const val READING_LIST_FOLDER = "Reading List"

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
        val topLevel = bookmarksStorage.getTree(BookmarkRoot.Mobile.id, recursive = true)
            .getOrNull()
            ?.children
            .orEmpty()
        val items = topLevel.filter { it.type == BookmarkNodeType.ITEM && !it.url.isNullOrBlank() }
        // Reading List pages live one folder deep; surface them alongside plain bookmarks so the
        // relocated "Add to Reading List" action (below) doesn't save into a place nothing shows.
        val readingListItems = topLevel
            .firstOrNull { it.type == BookmarkNodeType.FOLDER && it.title == READING_LIST_FOLDER }
            ?.children
            ?.filter { it.type == BookmarkNodeType.ITEM && !it.url.isNullOrBlank() }
            .orEmpty()
        bookmarks = (items + readingListItems).sortedByDescending { it.dateAdded }
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
            Spacer(Modifier.height(4.dp))
            // Add to Reading List (Safari-style save-for-later). Relocated from the aA sheet; backed
            // by a dedicated "Reading List" folder under the Mobile root, created on first use. These
            // items are flattened back into the list above by reload().
            TextButton(
                onClick = {
                    scope.launch {
                        runCatching {
                            val root = bookmarksStorage
                                .getTree(BookmarkRoot.Mobile.id, recursive = false).getOrNull()
                            val folderGuid = root?.children
                                ?.firstOrNull {
                                    it.type == BookmarkNodeType.FOLDER && it.title == READING_LIST_FOLDER
                                }
                                ?.guid
                                ?: bookmarksStorage
                                    .addFolder(BookmarkRoot.Mobile.id, READING_LIST_FOLDER, null)
                                    .getOrThrow()
                            bookmarksStorage.addItem(
                                parentGuid = folderGuid,
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
                Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null)
                Text(
                    text = "  Add to Reading List",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        SheetSearchField(
            query = searchQuery,
            onQueryChange = { searchQuery = it },
            placeholder = "Search bookmarks",
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
                        // Swipe end-to-start to remove — no per-row trash icon. See SwipeToRemoveRow.
                        SwipeToRemoveRow(
                            onRemove = {
                                scope.launch {
                                    runCatching { bookmarksStorage.deleteNode(node.guid) }
                                    reload()
                                }
                            },
                        ) {
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
                            )
                        }
                    }
                }
            }
        }
    }
}
