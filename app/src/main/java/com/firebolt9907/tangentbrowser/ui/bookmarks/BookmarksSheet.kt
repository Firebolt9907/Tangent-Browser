package com.firebolt9907.tangentbrowser.ui.bookmarks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
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
import com.firebolt9907.tangentbrowser.ui.rememberTangentHaptics
import com.firebolt9907.tangentbrowser.ui.components.SheetSearchField
import com.firebolt9907.tangentbrowser.ui.components.TangentModalSheet
import com.firebolt9907.tangentbrowser.ui.components.EmptyState
import com.firebolt9907.tangentbrowser.ui.components.SheetContentPhase
import com.firebolt9907.tangentbrowser.ui.components.SheetContentSwitch
import com.firebolt9907.tangentbrowser.ui.components.Favicon
import com.firebolt9907.tangentbrowser.ui.components.SheetHeader
import com.firebolt9907.tangentbrowser.ui.components.SwipeToRemoveRow
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
    val haptics = rememberTangentHaptics()
    var bookmarks by remember { mutableStateOf<List<BookmarkNode>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadFailed by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    // Deleting a bookmark is the one swipe-remove in the app that is genuinely reversible: the node
    // carries everything addItem() needs to put it back. So it gets a snackbar undo rather than a
    // confirmation dialog. (History loses the original visit timestamp on re-record and Downloads
    // deletes the file, which is why neither of those offers one.)
    val snackbarHostState = remember { SnackbarHostState() }

    suspend fun reload() {
        // The read is guarded and its failure *remembered* rather than swallowed: a failed tree read
        // used to fall through to an empty list and render as "No bookmarks yet", which is a
        // different — and wrong — claim about the user's data, with nothing to press to try again.
        val tree = runCatching {
            bookmarksStorage.getTree(BookmarkRoot.Mobile.id, recursive = true).getOrThrow()
        }
        loadFailed = tree.isFailure
        // On failure keep the last good list: a reload runs after every add/delete too, and blanking
        // a populated sheet because one refresh failed is worse than showing slightly stale rows.
        tree.getOrNull()?.let { root ->
            val topLevel = root.children.orEmpty()
            val items = topLevel.filter { it.type == BookmarkNodeType.ITEM && !it.url.isNullOrBlank() }
            // Reading List pages live one folder deep; surface them alongside plain bookmarks so the
            // relocated "Add to Reading List" action (below) doesn't save into a place nothing shows.
            val readingListItems = topLevel
                .firstOrNull { it.type == BookmarkNodeType.FOLDER && it.title == READING_LIST_FOLDER }
                ?.children
                ?.filter { it.type == BookmarkNodeType.ITEM && !it.url.isNullOrBlank() }
                .orEmpty()
            bookmarks = (items + readingListItems).sortedByDescending { it.dateAdded }
        }
        loading = false
    }

    fun retry() {
        loading = true
        scope.launch { reload() }
    }

    /** A write that failed used to be indistinguishable from one that worked — the list just didn't change. */
    suspend fun notifyFailure(message: String) {
        snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Short)
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

    TangentModalSheet(onDismissRequest = onDismiss, bottomPadding = 12.dp) {
        // The snackbar overlays the sheet's own content rather than sitting after it in the column,
        // so a message doesn't shove the list around as it comes and goes (same shape as
        // DownloadsSheet).
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.fillMaxWidth()) {
                SheetHeader("Bookmarks")

                Spacer(Modifier.height(12.dp))

                if (canBookmarkCurrent) {
                    // Two save destinations for the same page, so they read as one choice on one line
                    // instead of two stacked full-width buttons pushing the list off the first screenful.
                    // "Add to Reading List" is Safari-style save-for-later, backed by a dedicated folder
                    // under the Mobile root created on first use; reload() flattens those items back into
                    // the list below.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilledTonalButton(
                            onClick = {
                                haptics.create()
                                scope.launch {
                                    // Guard the write so a storage error can't crash the sheet; reload
                                    // afterward to reflect whatever actually persisted.
                                    val saved = runCatching {
                                        bookmarksStorage.addItem(
                                            parentGuid = BookmarkRoot.Mobile.id,
                                            url = currentPageUrl,
                                            title = currentPageTitle.ifBlank { currentPageUrl },
                                            position = null,
                                        )
                                    }
                                    reload()
                                    if (saved.isFailure) notifyFailure("Couldn't save that bookmark")
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize),
                            )
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(
                                text = "Add page",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        OutlinedButton(
                            onClick = {
                                haptics.create()
                                scope.launch {
                                    val saved = runCatching {
                                        val root = bookmarksStorage
                                            .getTree(BookmarkRoot.Mobile.id, recursive = false).getOrNull()
                                        val folderGuid = root?.children
                                            ?.firstOrNull {
                                                it.type == BookmarkNodeType.FOLDER &&
                                                    it.title == READING_LIST_FOLDER
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
                                    if (saved.isFailure) notifyFailure("Couldn't save to Reading List")
                                }
                            },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.MenuBook,
                                contentDescription = null,
                                modifier = Modifier.size(ButtonDefaults.IconSize),
                            )
                            Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                            Text(
                                text = "Read later",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }

                SheetSearchField(
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    placeholder = "Search bookmarks",
                )

                Spacer(Modifier.height(8.dp))

                // Spinner → empty/list is a cross-fade, not a swap: see SheetContentSwitch.
                val phase = when {
                    loading -> SheetContentPhase.Loading
                    filtered.isEmpty() -> SheetContentPhase.Empty
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
                            if (loadFailed) {
                                EmptyState(
                                    icon = Icons.Default.ErrorOutline,
                                    title = "Couldn't load bookmarks",
                                    description = "Something went wrong reading your saved pages.",
                                    action = {
                                        Button(onClick = { retry() }) { Text("Try again") }
                                    },
                                )
                            } else {
                                EmptyState(
                                    icon = Icons.Outlined.BookmarkBorder,
                                    title = if (searchQuery.isBlank()) "No bookmarks yet" else "No matches",
                                    description = if (searchQuery.isBlank()) {
                                        "Save a page with \"Add page\" to find it here later."
                                    } else {
                                        null
                                    },
                                )
                            }
                        }

                        SheetContentPhase.Content -> {
                            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                                items(filtered, key = { it.guid }) { node ->
                                    val url = node.url.orEmpty()
                                    // Swipe end-to-start to remove — no per-row trash icon.
                                    // See SwipeToRemoveRow.
                                    SwipeToRemoveRow(
                                        onRemove = {
                                            scope.launch {
                                                val removed =
                                                    runCatching { bookmarksStorage.deleteNode(node.guid) }
                                                reload()
                                                // The row swipes away either way, so a failed delete has
                                                // to say so — otherwise reload() silently puts the row
                                                // back under a snackbar claiming it was removed.
                                                if (removed.isFailure) {
                                                    notifyFailure("Couldn't remove that bookmark")
                                                    return@launch
                                                }
                                                // Everything addItem() needs survives on the node, so the
                                                // delete is fully reversible — including for a Reading List
                                                // item, whose parentGuid puts it back in its own folder.
                                                val result = snackbarHostState.showSnackbar(
                                                    message = "Bookmark removed",
                                                    actionLabel = "Undo",
                                                    duration = SnackbarDuration.Long,
                                                )
                                                if (result == SnackbarResult.ActionPerformed) {
                                                    val restored = runCatching {
                                                        bookmarksStorage.addItem(
                                                            parentGuid = node.parentGuid
                                                                ?: BookmarkRoot.Mobile.id,
                                                            url = url,
                                                            title = node.title.orEmpty().ifBlank { url },
                                                            position = node.position,
                                                        )
                                                    }
                                                    haptics.create()
                                                    reload()
                                                    if (restored.isFailure) {
                                                        notifyFailure("Couldn't restore that bookmark")
                                                    }
                                                }
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

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}
